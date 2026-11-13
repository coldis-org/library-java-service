package org.coldis.library.test.service.warmup;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.warmup.ServiceWarmUpProperties;
import org.coldis.library.service.warmup.ServiceWarmUpResult;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponent;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponentTestSeam;
import org.coldis.library.service.warmup.ServiceWarmUpSideEffectException;
import org.coldis.library.service.warmup.ServiceWarmUpStopReason;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cache.CacheManager;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Service warm-up test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ServiceWarmUpTest extends ContainerTestHelper {

	/**
	 * SQL state of a write refused in a read-only transaction ({@code read_only_sql_transaction}).
	 */
	private static final String READ_ONLY_SQL_TRANSACTION = "25006";

	/**
	 * Inputs that outlast any test.
	 */
	private static final List<Integer> UNENDING_INPUTS = IntStream.range(0, 200_000).boxed().toList();

	/**
	 * Time limit long enough for the condition under test to stop the warm-up first.
	 */
	private static final Duration LONG_TIME_LIMIT = Duration.ofSeconds(60);

	/**
	 * Longest a test waits for the warm-up.
	 */
	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * Server port.
	 */
	@Value("${local.server.port}")
	private Integer port;

	/**
	 * Warm-up service.
	 */
	@Autowired
	private ServiceWarmUpServiceComponent serviceWarmUpServiceComponent;

	/**
	 * Warm-up properties of the service.
	 */
	@Autowired
	private ServiceWarmUpProperties serviceWarmUpProperties;

	/**
	 * Operations with side effects.
	 */
	@Autowired
	private ServiceWarmUpTestServiceComponent serviceWarmUpTestServiceComponent;

	/**
	 * Key-value service.
	 */
	@Autowired
	private KeyValueServiceComponent keyValueServiceComponent;

	/**
	 * REST template.
	 */
	@Autowired
	private RestTemplate restTemplate;

	/**
	 * Local cache manager.
	 */
	@Autowired
	@Qualifier("minutesExpirationLocalCacheManager")
	private CacheManager localCacheManager;

	/**
	 * Central cache manager.
	 */
	@Autowired
	@Qualifier("minutesExpirationCentralCacheManager")
	private CacheManager centralCacheManager;

	/**
	 * Gets whether an error, or one of its causes, is of a type.
	 *
	 * @param  error Error.
	 * @param  type  Type.
	 * @return       Whether an error, or one of its causes, is of the type.
	 */
	private static boolean hasCause(
			final Throwable error,
			final Class<? extends Throwable> type) {
		boolean isFound = false;
		Throwable cause = error;
		while (!isFound && (cause != null)) {
			isFound = type.isInstance(cause);
			cause = cause.getCause();
		}
		return isFound;
	}

	/**
	 * Gets whether an error, or one of its causes, has a message with a text.
	 *
	 * @param  error Error.
	 * @param  text  Text.
	 * @return       Whether an error, or one of its causes, has a message with the text.
	 */
	private static boolean hasCauseMessage(
			final Throwable error,
			final String text) {
		boolean isFound = false;
		Throwable cause = error;
		while (!isFound && (cause != null)) {
			isFound = ((cause.getMessage() != null) && cause.getMessage().contains(text));
			cause = cause.getCause();
		}
		return isFound;
	}

	/**
	 * Gets whether an error, or one of its causes, is an SQL error with a state.
	 *
	 * @param  error    Error.
	 * @param  sqlState SQL state.
	 * @return          Whether an error, or one of its causes, is an SQL error with the state.
	 */
	private static boolean hasSqlState(
			final Throwable error,
			final String sqlState) {
		boolean isFound = false;
		Throwable cause = error;
		while (!isFound && (cause != null)) {
			isFound = ((cause instanceof final SQLException sqlException) && sqlState.equals(sqlException.getSQLState()));
			cause = cause.getCause();
		}
		return isFound;
	}

	/**
	 * Creates warm-up properties from the service's, with one thread, a long time limit and a JIT that never counts as
	 * idle.
	 *
	 * @return The properties.
	 */
	private ServiceWarmUpProperties createProperties() {
		final ServiceWarmUpProperties properties = ServiceWarmUpServiceComponentTestSeam.copy(this.serviceWarmUpProperties);
		properties.setParallelism(1);
		properties.setTimeLimit(ServiceWarmUpTest.LONG_TIME_LIMIT);
		properties.setIdleCompilationMillisPerSecond(0L);
		return properties;
	}

	/**
	 * Runs a warm-up with one input and gets its result.
	 *
	 * @param  <InputType> Input type.
	 * @param  input       Input.
	 * @param  call        Call.
	 * @return             The result.
	 */
	private <InputType> ServiceWarmUpResult warmUpOnce(
			final InputType input,
			final ServiceWarmUpTestRoutine.Call<InputType> call) {
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> List.of(input), call)), this.createProperties());
		Assertions.assertEquals(1, results.size());
		Assertions.assertEquals(1L, results.get(0).getCallCount());
		return results.get(0);
	}

	/**
	 * Gets the status of the health check.
	 *
	 * @return The status of the health check.
	 */
	private Integer getHealthCheckStatus() {
		Integer status = null;
		try {
			status = this.restTemplate.getForEntity("http://localhost:" + this.port + "/health-check", String.class).getStatusCode().value();
		}
		catch (final HttpStatusCodeException exception) {
			status = exception.getStatusCode().value();
		}
		return status;
	}

	/**
	 * Tests that, without warm-up beans, nothing holds the health check.
	 */
	@Test
	@DisplayName("Without warm-up beans, nothing holds the health check")
	public void testNoWarmUpBean() {
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
		Assertions.assertEquals(200, this.getHealthCheckStatus());
	}

	/**
	 * Tests that a disabled warm-up makes no calls.
	 */
	@Test
	@DisplayName("A disabled warm-up makes no calls")
	public void testDisabled() {
		final AtomicInteger calls = new AtomicInteger();
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setEnabled(false);
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> List.of(1), input -> calls.incrementAndGet())), properties);
		Assertions.assertTrue(results.isEmpty());
		Assertions.assertEquals(0, calls.get());
	}

	/**
	 * Tests that a warm-up uses each input once and stops when they end.
	 */
	@Test
	@DisplayName("A warm-up uses each input once and stops when they end")
	public void testInputsExhausted() {
		final Set<Integer> calledInputs = ConcurrentHashMap.newKeySet();
		final AtomicInteger calls = new AtomicInteger();
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setParallelism(4);
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent,
				List.of(new ServiceWarmUpTestRoutine<>(() -> IntStream.range(0, 50).boxed().toList(), input -> {
					calledInputs.add(input);
					calls.incrementAndGet();
				})), properties);
		Assertions.assertEquals(1, results.size());
		Assertions.assertEquals(ServiceWarmUpStopReason.INPUTS_EXHAUSTED, results.get(0).getStopReason());
		Assertions.assertEquals(50L, results.get(0).getCallCount());
		Assertions.assertEquals(0L, results.get(0).getFailedCallCount());
		Assertions.assertEquals(50, calls.get());
		Assertions.assertEquals(IntStream.range(0, 50).boxed().collect(Collectors.toSet()), calledInputs);
		Assertions.assertNotNull(results.get(0).getFirstCallsMedianMillis());
		Assertions.assertNotNull(results.get(0).getLastCallsMedianMillis());
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
	}

	/**
	 * Tests that an idle JIT stops the warm-up once the minimum of calls is made.
	 */
	@Test
	@DisplayName("An idle JIT stops the warm-up once the minimum of calls is made")
	public void testCompilationIdle() {
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setParallelism(2);
		properties.setMinimumCalls(20);
		// Any compilation rate counts as idle.
		properties.setIdleCompilationMillisPerSecond(Long.MAX_VALUE);
		properties.setIdleCompilationPeriod(Duration.ofSeconds(1));
		final long startNanos = System.nanoTime();
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> Thread.sleep(1))), properties);
		final Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
		Assertions.assertEquals(ServiceWarmUpStopReason.COMPILATION_IDLE, results.get(0).getStopReason());
		Assertions.assertTrue(results.get(0).getCallCount() >= 20L);
		Assertions.assertTrue(duration.compareTo(ServiceWarmUpTest.TEST_TIMEOUT) < 0, duration.toString());
	}

	/**
	 * Tests that an idle JIT does not stop the warm-up before the minimum of calls is made.
	 */
	@Test
	@DisplayName("An idle JIT does not stop the warm-up before the minimum of calls is made")
	public void testCompilationIdleWaitsForMinimumCalls() {
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setParallelism(2);
		properties.setMinimumCalls(Integer.MAX_VALUE);
		// Any compilation rate counts as idle, so only the unreachable minimum keeps the warm-up running.
		properties.setIdleCompilationMillisPerSecond(Long.MAX_VALUE);
		properties.setIdleCompilationPeriod(Duration.ofSeconds(1));
		properties.setTimeLimit(Duration.ofSeconds(3));
		final long startNanos = System.nanoTime();
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> Thread.sleep(1))), properties);
		final Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
		Assertions.assertEquals(ServiceWarmUpStopReason.TIME_LIMIT, results.get(0).getStopReason());
		Assertions.assertTrue(duration.compareTo(Duration.ofSeconds(3)) >= 0, duration.toString());
	}

	/**
	 * Tests that the time limit stops the warm-up.
	 */
	@Test
	@DisplayName("The time limit stops the warm-up")
	public void testTimeLimit() {
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setTimeLimit(Duration.ofSeconds(2));
		final long startNanos = System.nanoTime();
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> Thread.sleep(1))), properties);
		final Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
		Assertions.assertEquals(ServiceWarmUpStopReason.TIME_LIMIT, results.get(0).getStopReason());
		Assertions.assertTrue(duration.compareTo(Duration.ofSeconds(2)) >= 0, duration.toString());
		Assertions.assertTrue(duration.compareTo(ServiceWarmUpTest.TEST_TIMEOUT) < 0, duration.toString());
	}

	/**
	 * Tests that consecutive errors stop the warm-up, and that the health check opens.
	 */
	@Test
	@DisplayName("Consecutive errors stop the warm-up and the health check opens")
	public void testConsecutiveErrors() {
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setMaximumConsecutiveErrors(3);
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> {
					throw new IllegalArgumentException("Test error.");
				})), properties);
		Assertions.assertEquals(ServiceWarmUpStopReason.ERRORS, results.get(0).getStopReason());
		Assertions.assertEquals(3L, results.get(0).getCallCount());
		Assertions.assertEquals(3L, results.get(0).getFailedCallCount());
		Assertions.assertInstanceOf(IllegalArgumentException.class, results.get(0).getFirstError());
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
	}

	/**
	 * Tests that an error from a call, other than one the JVM cannot recover from, fails the call without ending its
	 * thread.
	 */
	@Test
	@DisplayName("An error from a call fails the call without ending its thread")
	public void testCallError() {
		final ServiceWarmUpProperties properties = this.createProperties();
		properties.setMaximumConsecutiveErrors(3);
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> {
					throw new AssertionError("Test error.");
				})), properties);
		Assertions.assertEquals(ServiceWarmUpStopReason.ERRORS, results.get(0).getStopReason());
		Assertions.assertEquals(3L, results.get(0).getFailedCallCount());
		Assertions.assertInstanceOf(AssertionError.class, results.get(0).getFirstError());
	}

	/**
	 * Tests that a warm-up whose inputs cannot be read makes no calls.
	 */
	@Test
	@DisplayName("A warm-up whose inputs cannot be read makes no calls")
	public void testInputsFailure() {
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<Integer>(() -> {
			throw new IllegalStateException("Test inputs error.");
		}, input -> {})), this.createProperties());
		Assertions.assertEquals(ServiceWarmUpStopReason.INPUTS_FAILED, results.get(0).getStopReason());
		Assertions.assertEquals(0L, results.get(0).getCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCauseMessage(results.get(0).getFirstError(), "Test inputs error."));
	}

	/**
	 * Tests that a run that fails opens the health check and returns no results.
	 */
	@Test
	@DisplayName("A run that fails opens the health check and returns no results")
	public void testRunFailureOpensHealthCheck() {
		final ServiceWarmUpProperties properties = this.createProperties();
		// The run needs a time limit, so it fails without one.
		properties.setTimeLimit(null);
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent,
				List.of(new ServiceWarmUpTestRoutine<>(() -> List.of(1), input -> {})), properties);
		Assertions.assertTrue(results.isEmpty());
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
	}

	/**
	 * Tests that call threads ended by an error the JVM cannot recover from stop the run as such, and open the health
	 * check.
	 */
	@Test
	@DisplayName("Call threads ended by a JVM error stop the run as such and open the health check")
	public void testCallThreadsFailed() {
		final List<ServiceWarmUpResult> results = ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent,
				List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> {
					throw new StackOverflowError("Test error.");
				})), this.createProperties());
		Assertions.assertEquals(ServiceWarmUpStopReason.CALL_THREADS_FAILED, results.get(0).getStopReason());
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
	}

	/**
	 * Tests that interrupting the thread that waits for the calls stops the run as interrupted, and opens the health
	 * check.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Interrupting the waiting thread stops the run as interrupted and opens the health check")
	public void testInterrupted() throws Exception {
		final CountDownLatch callStarted = new CountDownLatch(1);
		final AtomicReference<List<ServiceWarmUpResult>> results = new AtomicReference<>();
		final Thread caller = Thread.ofPlatform().start(() -> results.set(ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent,
				List.of(new ServiceWarmUpTestRoutine<>(() -> ServiceWarmUpTest.UNENDING_INPUTS, input -> {
					callStarted.countDown();
					Thread.sleep(1);
				})), this.createProperties())));
		Assertions.assertTrue(callStarted.await(ServiceWarmUpTest.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
		caller.interrupt();
		caller.join(ServiceWarmUpTest.TEST_TIMEOUT.toMillis());
		Assertions.assertEquals(ServiceWarmUpStopReason.INTERRUPTED, results.get().get(0).getStopReason());
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
	}

	/**
	 * Tests that the health check answers 503 while a warm-up runs, and passes after it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The health check answers 503 while a warm-up runs and passes after it")
	public void testHealthCheckHeld() throws Exception {
		final CountDownLatch callStarted = new CountDownLatch(1);
		final CountDownLatch callReleased = new CountDownLatch(1);
		final CompletableFuture<List<ServiceWarmUpResult>> warmUp = CompletableFuture.supplyAsync(() -> ServiceWarmUpServiceComponentTestSeam.warmUp(this.serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> List.of(1), input -> {
					callStarted.countDown();
					callReleased.await(ServiceWarmUpTest.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
				})), this.createProperties()));
		try {
			Assertions.assertTrue(callStarted.await(ServiceWarmUpTest.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
			Assertions.assertFalse(this.serviceWarmUpServiceComponent.isTrafficAllowed());
			Assertions.assertEquals(503, this.getHealthCheckStatus());
		}
		finally {
			callReleased.countDown();
		}
		Assertions.assertEquals(ServiceWarmUpStopReason.INPUTS_EXHAUSTED,
				warmUp.get(ServiceWarmUpTest.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS).get(0).getStopReason());
		Assertions.assertTrue(this.serviceWarmUpServiceComponent.isTrafficAllowed());
		Assertions.assertEquals(200, this.getHealthCheckStatus());
	}

	/**
	 * Tests that the database refuses the writes of the service's transactional methods, in the call's transaction and
	 * in a new one, and that the same writes work outside the warm-up.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The database refuses writes of transactional methods, also in a new transaction")
	public void testDatabaseWritesRefused() throws Exception {
		final String key = "service-warm-up-test-" + UUID.randomUUID();
		// Step 1: the writes, during a warm-up.
		final ServiceWarmUpResult sameTransactionResult = this.warmUpOnce(key,
				input -> this.keyValueServiceComponent.lock(input, LockBehavior.WAIT_AND_LOCK));
		final ServiceWarmUpResult newTransactionResult = this.warmUpOnce(key, input -> this.serviceWarmUpTestServiceComponent.lockInNewTransaction(input));
		// Verify: the database refused them.
		for (final ServiceWarmUpResult result : List.of(sameTransactionResult, newTransactionResult)) {
			Assertions.assertEquals(1L, result.getFailedCallCount());
			Assertions.assertTrue(ServiceWarmUpTest.hasSqlState(result.getFirstError(), ServiceWarmUpTest.READ_ONLY_SQL_TRANSACTION),
					String.valueOf(result.getFirstError()));
		}
		Assertions.assertNull(this.keyValueServiceComponent.findById(key, LockBehavior.NO_LOCK, true));
		// Step 2: the same write outside a warm-up is stored.
		this.serviceWarmUpTestServiceComponent.lockInNewTransaction(key);
		Assertions.assertNotNull(this.keyValueServiceComponent.findById(key, LockBehavior.NO_LOCK, true));
		// Clean up.
		this.keyValueServiceComponent.delete(key);
	}

	/**
	 * Tests that a read-write transaction a transaction template starts is refused, and works outside the warm-up.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A read-write transaction a transaction template starts is refused")
	public void testProgrammaticNewTransactionRefused() throws Exception {
		final String key = "service-warm-up-test-" + UUID.randomUUID();
		// Step 1: the write, during a warm-up.
		final ServiceWarmUpResult result = this.warmUpOnce(key, input -> this.serviceWarmUpTestServiceComponent.saveInProgrammaticNewTransaction(input));
		Assertions.assertEquals(1L, result.getFailedCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCause(result.getFirstError(), ServiceWarmUpSideEffectException.class),
				String.valueOf(result.getFirstError()));
		Assertions.assertNull(this.keyValueServiceComponent.findById(key, LockBehavior.NO_LOCK, true));
		// Step 2: the same write outside a warm-up is stored.
		this.serviceWarmUpTestServiceComponent.saveInProgrammaticNewTransaction(key);
		Assertions.assertNotNull(this.keyValueServiceComponent.findById(key, LockBehavior.NO_LOCK, true));
		// Clean up.
		this.keyValueServiceComponent.delete(key);
	}

	/**
	 * Tests that a read-write transaction started with the call's transaction suspended is refused, and works outside
	 * the warm-up.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A read-write transaction started with the call's transaction suspended is refused")
	public void testNewTransactionAfterSuspensionRefused() throws Exception {
		final String key = "service-warm-up-test-" + UUID.randomUUID();
		// Step 1: the write, during a warm-up.
		final ServiceWarmUpResult result = this.warmUpOnce(key, input -> this.serviceWarmUpTestServiceComponent.saveWithTransactionSuspended(input));
		Assertions.assertEquals(1L, result.getFailedCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCause(result.getFirstError(), ServiceWarmUpSideEffectException.class),
				String.valueOf(result.getFirstError()));
		Assertions.assertNull(this.keyValueServiceComponent.findById(key, LockBehavior.NO_LOCK, true));
		// Step 2: the same write outside a warm-up is stored.
		this.serviceWarmUpTestServiceComponent.saveWithTransactionSuspended(key);
		Assertions.assertNotNull(this.keyValueServiceComponent.findById(key, LockBehavior.NO_LOCK, true));
		// Clean up.
		this.keyValueServiceComponent.delete(key);
	}

	/**
	 * Tests that warm-up calls run cached methods without filling the caches, and that the same calls fill them outside
	 * the warm-up.
	 */
	@Test
	@DisplayName("Warm-up calls run cached methods without filling the caches")
	public void testCacheWritesDropped() {
		final String key = UUID.randomUUID().toString();
		final int runsBefore = ServiceWarmUpTestServiceComponent.CACHED_METHOD_RUNS.get();
		// Step 1: the cached methods, during a warm-up, run without filling the caches.
		final ServiceWarmUpResult result = this.warmUpOnce(key, input -> {
			this.serviceWarmUpTestServiceComponent.getFromLocalCache(input);
			this.serviceWarmUpTestServiceComponent.getFromCentralCache(input);
		});
		Assertions.assertEquals(0L, result.getFailedCallCount());
		Assertions.assertEquals(runsBefore + 2, ServiceWarmUpTestServiceComponent.CACHED_METHOD_RUNS.get());
		Assertions.assertNull(this.localCacheManager.getCache(ServiceWarmUpTestServiceComponent.LOCAL_CACHE).get(key));
		Assertions.assertNull(this.centralCacheManager.getCache(ServiceWarmUpTestServiceComponent.CENTRAL_CACHE).get(key));
		// Step 2: the same calls outside a warm-up fill them.
		this.serviceWarmUpTestServiceComponent.getFromLocalCache(key);
		this.serviceWarmUpTestServiceComponent.getFromCentralCache(key);
		Assertions.assertNotNull(this.localCacheManager.getCache(ServiceWarmUpTestServiceComponent.LOCAL_CACHE).get(key));
		Assertions.assertNotNull(this.centralCacheManager.getCache(ServiceWarmUpTestServiceComponent.CENTRAL_CACHE).get(key));
	}

	/**
	 * Tests that warm-up calls cannot send messages: the pooled connection factory refuses the connection before the
	 * send.
	 */
	@Test
	@DisplayName("Warm-up calls cannot send messages")
	public void testMessagesRefused() {
		final ServiceWarmUpResult result = this.warmUpOnce("message", input -> this.serviceWarmUpTestServiceComponent.send(input));
		Assertions.assertEquals(1L, result.getFailedCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCause(result.getFirstError(), ServiceWarmUpSideEffectException.class),
				String.valueOf(result.getFirstError()));
	}

	/**
	 * Tests that warm-up calls cannot make HTTP requests, and that the same request works outside the warm-up.
	 */
	@Test
	@DisplayName("Warm-up calls cannot make HTTP requests")
	public void testHttpRequestsRefused() {
		final ServiceWarmUpResult result = this.warmUpOnce(this.port, input -> this.serviceWarmUpTestServiceComponent.getHealthCheck(input));
		Assertions.assertEquals(1L, result.getFailedCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCause(result.getFirstError(), ServiceWarmUpSideEffectException.class),
				String.valueOf(result.getFirstError()));
		Assertions.assertNotNull(this.serviceWarmUpTestServiceComponent.getHealthCheck(this.port));
	}

	/**
	 * Tests that warm-up calls cannot record statistics events.
	 */
	@Test
	@DisplayName("Warm-up calls cannot record statistics events")
	public void testStatisticsRefused() {
		final ServiceWarmUpResult result = this.warmUpOnce(1, input -> this.serviceWarmUpTestServiceComponent.recordStatisticsEvent());
		Assertions.assertEquals(1L, result.getFailedCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCause(result.getFirstError(), ServiceWarmUpSideEffectException.class),
				String.valueOf(result.getFirstError()));
	}

	/**
	 * Tests that warm-up calls cannot buffer statistics summary deltas, which the event guard does not cover.
	 */
	@Test
	@DisplayName("Warm-up calls cannot buffer statistics summary deltas")
	public void testStatisticsSummaryRefused() {
		final ServiceWarmUpResult result = this.warmUpOnce(1, input -> this.serviceWarmUpTestServiceComponent.bufferStatisticsSummaryDelta());
		Assertions.assertEquals(1L, result.getFailedCallCount());
		Assertions.assertTrue(ServiceWarmUpTest.hasCause(result.getFirstError(), ServiceWarmUpSideEffectException.class),
				String.valueOf(result.getFirstError()));
	}

}
