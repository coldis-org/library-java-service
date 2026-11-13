package org.coldis.library.service.warmup;

import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Runs the {@link ServiceWarmUp} beans when the application is ready and holds the health check (503) until they
 * stop. It exists only when the service turns the warm-up on ({@value ServiceWarmUpProperties#ENABLED_PROPERTY}); even
 * then, without warm-up beans nothing runs and the health check is never held.
 *
 * <p>
 * The calls run on their own threads, apart from the request threads, taking the warm-ups in turns. They stop, for
 * every warm-up still running, when the JIT stays idle for the idle period after each of them made the minimum of
 * calls, or at the time limit; a warm-up also stops alone when its inputs end or after too many consecutive errors.
 * Any failure of the warm-up itself opens the health check: the warm-up only makes the start faster, so the service
 * then takes traffic as it would without it.
 * </p>
 */
@Component
@ConditionalOnProperty(
		name = ServiceWarmUpProperties.ENABLED_PROPERTY,
		havingValue = "true"
)
public class ServiceWarmUpServiceComponent implements ApplicationListener<ApplicationReadyEvent>, Ordered, DisposableBean {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceWarmUpServiceComponent.class);

	/**
	 * Interval between checks of the stop conditions, in nanoseconds.
	 */
	private static final long CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

	/**
	 * Nanoseconds per millisecond.
	 */
	private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;

	/**
	 * Bean factory.
	 */
	private final ListableBeanFactory beanFactory;

	/**
	 * Properties.
	 */
	private final ServiceWarmUpProperties properties;

	/**
	 * Transaction manager provider.
	 */
	private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

	/**
	 * Meter registry provider.
	 */
	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	/**
	 * Whether the warm-up beans have yet to run, which holds the health check from the start.
	 */
	private final AtomicBoolean isStartupWarmUpPending;

	/**
	 * Whether the warm-up beans were started.
	 */
	private final AtomicBoolean hasStartupWarmUpStarted = new AtomicBoolean();

	/**
	 * Whether the service is stopping.
	 */
	private final AtomicBoolean isDestroyed = new AtomicBoolean();

	/**
	 * Number of running warm-ups.
	 */
	private final AtomicInteger runningWarmUps = new AtomicInteger();

	/**
	 * Executors of the calls, stopped when the service stops.
	 */
	private final Set<ExecutorService> executors = ConcurrentHashMap.newKeySet();

	/**
	 * Constructor.
	 *
	 * @param beanFactory                Bean factory.
	 * @param properties                 Properties.
	 * @param transactionManagerProvider Transaction manager provider.
	 * @param meterRegistryProvider      Meter registry provider.
	 */
	public ServiceWarmUpServiceComponent(
			final ListableBeanFactory beanFactory,
			final ServiceWarmUpProperties properties,
			final ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
			final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		super();
		this.beanFactory = beanFactory;
		this.properties = properties;
		this.transactionManagerProvider = transactionManagerProvider;
		this.meterRegistryProvider = meterRegistryProvider;
		// The health check is held from the start, since the server answers it before the application is ready.
		this.isStartupWarmUpPending = new AtomicBoolean(beanFactory.getBeanNamesForType(ServiceWarmUp.class, true, false).length > 0);
	}

	/**
	 * Gets the next warm-up with calls to make, taking them in turns.
	 *
	 * @param  executions Warm-up executions.
	 * @param  turn       Turn counter.
	 * @param  isStopped  Whether the calls stopped.
	 * @return            The next warm-up, or null when no call is left to make.
	 */
	private static ServiceWarmUpExecution<?> getNextExecution(
			final List<ServiceWarmUpExecution<?>> executions,
			final AtomicLong turn,
			final AtomicBoolean isStopped) {
		ServiceWarmUpExecution<?> nextExecution = null;
		if (!isStopped.get() && !Thread.currentThread().isInterrupted()) {
			final int firstIndex = (int) (turn.getAndIncrement() % executions.size());
			for (int offset = 0; (offset < executions.size()) && (nextExecution == null); offset++) {
				final ServiceWarmUpExecution<?> execution = executions.get((firstIndex + offset) % executions.size());
				if (execution.isActive()) {
					nextExecution = execution;
				}
			}
		}
		return nextExecution;
	}

	/**
	 * Makes calls until none is left or the calls stop. Runs on a warm-up thread.
	 *
	 * @param executions Warm-up executions.
	 * @param turn       Turn counter.
	 * @param isStopped  Whether the calls stopped.
	 */
	private static void makeCalls(
			final List<ServiceWarmUpExecution<?>> executions,
			final AtomicLong turn,
			final AtomicBoolean isStopped) {
		ServiceWarmUpContext.enter();
		try {
			ServiceWarmUpExecution<?> execution = ServiceWarmUpServiceComponent.getNextExecution(executions, turn, isStopped);
			while (execution != null) {
				execution.callNext();
				execution = ServiceWarmUpServiceComponent.getNextExecution(executions, turn, isStopped);
			}
		}
		finally {
			ServiceWarmUpContext.exit();
		}
	}

	/**
	 * Gets whether every running warm-up made the minimum of calls.
	 *
	 * @param  executions   Warm-up executions.
	 * @param  minimumCalls Minimum of calls.
	 * @return              Whether every running warm-up made the minimum of calls.
	 */
	private static boolean haveMadeMinimumCalls(
			final List<ServiceWarmUpExecution<?>> executions,
			final Integer minimumCalls) {
		return executions.stream().allMatch(execution -> !execution.isActive() || (execution.getCallCount() >= minimumCalls));
	}

	/**
	 * Formats a duration in milliseconds for the log.
	 *
	 * @param  millis Duration in milliseconds, or null.
	 * @return        The formatted duration.
	 */
	private static String formatMillis(
			final Double millis) {
		return (millis == null ? "-" : String.format(Locale.ROOT, "%.1f", millis));
	}

	/**
	 * Creates the read-only transaction around each call, with the single (or primary) transaction manager.
	 *
	 * @return The transaction template, or null without a single transaction manager.
	 */
	private TransactionTemplate createReadOnlyTransactionTemplate() {
		final PlatformTransactionManager transactionManager = this.transactionManagerProvider.getIfUnique();
		TransactionTemplate transactionTemplate = null;
		if (transactionManager == null) {
			ServiceWarmUpServiceComponent.LOGGER.info("Service warm-up calls run without an outer read-only transaction, since there is no single "
					+ "transaction manager: the service's @Transactional methods run read-only and every other new read-write transaction is "
					+ "refused, but writes made outside any transaction are not.");
		}
		else {
			transactionTemplate = new TransactionTemplate(transactionManager);
			transactionTemplate.setReadOnly(true);
			transactionTemplate.setName("service-warm-up");
		}
		return transactionTemplate;
	}

	/**
	 * Waits until the calls end or a stop condition holds.
	 *
	 * @param  executor                   Executor of the calls, already shut down.
	 * @param  executions                 Warm-up executions.
	 * @param  warmUpProperties           Properties.
	 * @param  startNanos                 Start of the warm-up, from {@link System#nanoTime()}.
	 * @param  compilation                JIT compilation bean.
	 * @param  isCompilationTimeMonitored Whether the JIT compilation time is available.
	 * @return                            Why the running warm-ups stop, or null when every warm-up ended on its own.
	 */
	private ServiceWarmUpStopReason awaitStop(
			final ExecutorService executor,
			final List<ServiceWarmUpExecution<?>> executions,
			final ServiceWarmUpProperties warmUpProperties,
			final long startNanos,
			final CompilationMXBean compilation,
			final boolean isCompilationTimeMonitored) {
		final long deadlineNanos = startNanos + warmUpProperties.getTimeLimit().toNanos();
		final ServiceWarmUpCompilationIdleness compilationIdleness = (isCompilationTimeMonitored
				? new ServiceWarmUpCompilationIdleness(warmUpProperties.getIdleCompilationMillisPerSecond(),
						Math.max(1L, warmUpProperties.getIdleCompilationPeriod().toNanos() / ServiceWarmUpServiceComponent.CHECK_INTERVAL_NANOS),
						compilation.getTotalCompilationTime(), startNanos)
				: null);
		boolean haveCallsEnded = false;
		ServiceWarmUpStopReason stopReason = null;
		while (!haveCallsEnded && (stopReason == null)) {
			try {
				haveCallsEnded = executor.awaitTermination(
						Math.min(ServiceWarmUpServiceComponent.CHECK_INTERVAL_NANOS, Math.max(1L, deadlineNanos - System.nanoTime())), TimeUnit.NANOSECONDS);
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
				stopReason = ServiceWarmUpStopReason.INTERRUPTED;
			}
			final long checkNanos = System.nanoTime();
			if (compilationIdleness != null) {
				compilationIdleness.recordCheck(compilation.getTotalCompilationTime(), checkNanos,
						ServiceWarmUpServiceComponent.haveMadeMinimumCalls(executions, warmUpProperties.getMinimumCalls()));
			}
			if (!haveCallsEnded && (stopReason == null)) {
				// Clock readings compare by their difference, which stays right if they wrap.
				if ((checkNanos - deadlineNanos) >= 0L) {
					stopReason = ServiceWarmUpStopReason.TIME_LIMIT;
				}
				else if ((compilationIdleness != null) && compilationIdleness.isIdleLongEnough()) {
					stopReason = ServiceWarmUpStopReason.COMPILATION_IDLE;
				}
			}
		}
		// The call threads also end, with warm-ups still running, when the service stops or a JVM error ends them.
		if (haveCallsEnded && (stopReason == null) && executions.stream().anyMatch(ServiceWarmUpExecution::isActive)) {
			stopReason = (this.isDestroyed.get() ? ServiceWarmUpStopReason.INTERRUPTED : ServiceWarmUpStopReason.CALL_THREADS_FAILED);
		}
		return stopReason;
	}

	/**
	 * Logs the results and records them as metrics.
	 *
	 * @param results           Warm-up results.
	 * @param runStopReason     Why the running warm-ups stopped, or null when every warm-up ended on its own.
	 * @param durationMillis    Duration, in milliseconds.
	 * @param compilationMillis JIT compilation time during the warm-up, in milliseconds, or -1 when not available.
	 * @param parallelism       Number of threads.
	 */
	private void reportResults(
			final List<ServiceWarmUpResult> results,
			final ServiceWarmUpStopReason runStopReason,
			final long durationMillis,
			final long compilationMillis,
			final int parallelism) {
		final String runOutcome = (runStopReason == null ? "ENDED" : runStopReason.name());
		ServiceWarmUpServiceComponent.LOGGER.info("Service warm-up finished in {} ms ({}), with {} threads and {} ms of JIT compilation.", durationMillis,
				runOutcome, parallelism, compilationMillis);
		for (final ServiceWarmUpResult result : results) {
			ServiceWarmUpServiceComponent.LOGGER.info("Warm-up '{}' stopped by {} after {} calls, {} failed. Median call: {} ms in the first calls, {} ms in the last.",
					result.getName(), result.getStopReason(), result.getCallCount(), result.getFailedCallCount(),
					ServiceWarmUpServiceComponent.formatMillis(result.getFirstCallsMedianMillis()),
					ServiceWarmUpServiceComponent.formatMillis(result.getLastCallsMedianMillis()));
		}
		final MeterRegistry meterRegistry = this.meterRegistryProvider.getIfUnique();
		if (meterRegistry != null) {
			meterRegistry.timer("service.warm.up", "outcome", runOutcome).record(Duration.ofMillis(durationMillis));
			for (final ServiceWarmUpResult result : results) {
				meterRegistry.counter("service.warm.up.calls", "warm.up", result.getName(), "outcome", "success")
						.increment(result.getCallCount() - result.getFailedCallCount());
				meterRegistry.counter("service.warm.up.calls", "warm.up", result.getName(), "outcome", "error").increment(result.getFailedCallCount());
			}
		}
	}

	/**
	 * Runs warm-ups.
	 *
	 * @param  warmUps          Warm-ups.
	 * @param  warmUpProperties Properties.
	 * @return                  The results, in the order of the warm-ups.
	 */
	private List<ServiceWarmUpResult> runWarmUps(
			final List<? extends ServiceWarmUp<?>> warmUps,
			final ServiceWarmUpProperties warmUpProperties) {
		final long startNanos = System.nanoTime();
		final TransactionTemplate transactionTemplate = this.createReadOnlyTransactionTemplate();
		final List<ServiceWarmUpExecution<?>> executions = new ArrayList<>();
		for (final ServiceWarmUp<?> warmUp : warmUps) {
			executions.add(ServiceWarmUpExecution.of(warmUp, transactionTemplate, warmUpProperties.getMaximumConsecutiveErrors()));
		}
		final int parallelism = (warmUpProperties.getParallelism() > 0 ? warmUpProperties.getParallelism() : Runtime.getRuntime().availableProcessors());
		final CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
		final boolean isCompilationTimeMonitored = ((compilation != null) && compilation.isCompilationTimeMonitoringSupported());
		final long compilationAtStartMillis = (isCompilationTimeMonitored ? compilation.getTotalCompilationTime() : 0L);
		final AtomicBoolean isStopped = new AtomicBoolean();
		final AtomicLong turn = new AtomicLong();
		final ExecutorService executor = Executors.newFixedThreadPool(parallelism, Thread.ofPlatform().daemon().name("service-warm-up-", 1).factory());
		this.executors.add(executor);
		ServiceWarmUpStopReason runStopReason = null;
		try {
			for (int threadIndex = 0; threadIndex < parallelism; threadIndex++) {
				executor.execute(() -> ServiceWarmUpServiceComponent.makeCalls(executions, turn, isStopped));
			}
			executor.shutdown();
			runStopReason = this.awaitStop(executor, executions, warmUpProperties, startNanos, compilation, isCompilationTimeMonitored);
		}
		finally {
			// Calls in progress end on their own; the health check does not wait for them.
			isStopped.set(true);
		}
		for (final ServiceWarmUpExecution<?> execution : executions) {
			execution.stop(runStopReason);
		}
		final List<ServiceWarmUpResult> results = executions.stream().map(ServiceWarmUpExecution::toResult).toList();
		this.reportResults(results, runStopReason, (System.nanoTime() - startNanos) / ServiceWarmUpServiceComponent.NANOSECONDS_PER_MILLISECOND,
				(isCompilationTimeMonitored ? compilation.getTotalCompilationTime() - compilationAtStartMillis : -1L), parallelism);
		return results;
	}

	/**
	 * Runs warm-ups now and holds the health check (503) while they run, up to the time limit. Package-private: the
	 * warm-up beans run through it when the application is ready, and tests reach it through a test seam.
	 *
	 * @param  warmUps          Warm-ups.
	 * @param  warmUpProperties Properties.
	 * @return                  The results, in the order of the warm-ups; empty when nothing ran or the warm-up failed.
	 */
	List<ServiceWarmUpResult> warmUp(
			final List<? extends ServiceWarmUp<?>> warmUps,
			final ServiceWarmUpProperties warmUpProperties) {
		List<ServiceWarmUpResult> results = List.of();
		this.runningWarmUps.incrementAndGet();
		try {
			if (Boolean.TRUE.equals(warmUpProperties.getEnabled()) && !warmUps.isEmpty()) {
				results = this.runWarmUps(warmUps, warmUpProperties);
			}
		}
		// The warm-up fails open: the error is logged and the service takes traffic as it would without a warm-up.
		catch (final RuntimeException exception) {
			ServiceWarmUpServiceComponent.LOGGER.error("Service warm-up failed, so the service takes traffic without it.", exception);
		}
		finally {
			this.runningWarmUps.decrementAndGet();
		}
		return results;
	}

	/**
	 * Runs the warm-up beans and opens the health check when they stop.
	 *
	 * @param warmUps Warm-up beans.
	 */
	private void runStartupWarmUps(
			final List<ServiceWarmUp<?>> warmUps) {
		this.runningWarmUps.incrementAndGet();
		try {
			this.isStartupWarmUpPending.set(false);
			this.warmUp(warmUps, this.properties);
		}
		finally {
			this.runningWarmUps.decrementAndGet();
		}
	}

	/**
	 * Gets whether the health check may pass, that is, whether no warm-up is pending or running.
	 *
	 * @return Whether the health check may pass.
	 */
	public boolean isTrafficAllowed() {
		return !this.isStartupWarmUpPending.get() && (this.runningWarmUps.get() == 0);
	}

	/**
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	@Override
	public int getOrder() {
		// Starts the warm-up before other listeners, such as the data installer, which run on the same thread.
		return Ordered.HIGHEST_PRECEDENCE;
	}

	/**
	 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
	 */
	@Override
	public void onApplicationEvent(
			final ApplicationReadyEvent event) {
		if (this.isStartupWarmUpPending.get() && this.hasStartupWarmUpStarted.compareAndSet(false, true)) {
			try {
				final List<ServiceWarmUp<?>> warmUps = new ArrayList<>();
				for (final ServiceWarmUp<?> warmUp : this.beanFactory.getBeansOfType(ServiceWarmUp.class).values()) {
					warmUps.add(warmUp);
				}
				Thread.ofPlatform().daemon().name("service-warm-up").start(() -> this.runStartupWarmUps(warmUps));
			}
			// The warm-up fails open: the error is logged and the service takes traffic as it would without a warm-up.
			catch (final RuntimeException exception) {
				ServiceWarmUpServiceComponent.LOGGER.error("Service warm-up could not start, so the service takes traffic without it.", exception);
				this.isStartupWarmUpPending.set(false);
			}
		}
	}

	/**
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	@Override
	public void destroy() {
		this.isDestroyed.set(true);
		this.executors.forEach(ExecutorService::shutdownNow);
	}

}
