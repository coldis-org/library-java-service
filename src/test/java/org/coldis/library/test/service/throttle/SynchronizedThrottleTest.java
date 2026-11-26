package org.coldis.library.test.service.throttle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.coldis.library.service.throttle.SynchronizedThrottle;
import org.coldis.library.service.throttle.SynchronizedThrottleProperties;
import org.coldis.library.service.throttle.SynchronizedThrottleReservation;
import org.coldis.library.service.throttle.repository.SynchronizedThrottleRepository;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Synchronized throttle test.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Synchronized throttle")
public class SynchronizedThrottleTest extends ContainerTestHelper {

	/**
	 * Random.
	 */
	private static final Random RANDOM = new Random();

	/**
	 * Test table name.
	 */
	private static final String TABLE_NAME = "synchronized_throttle_test";

	/**
	 * Throttle name used by the tests.
	 */
	private static final String THROTTLE_NAME = "testThrottle";

	/**
	 * Long interval (never passes during a test).
	 */
	private static final Duration LONG_INTERVAL = Duration.ofMinutes(10);

	/**
	 * Data source.
	 */
	@Autowired
	private DataSource dataSource;

	/**
	 * The application context's throttle (created by default).
	 */
	@Autowired
	private SynchronizedThrottle contextThrottle;

	/**
	 * The application context's repository (created with a data source).
	 */
	@Autowired
	private ObjectProvider<SynchronizedThrottleRepository> contextRepositoryProvider;

	/**
	 * Creates a provider of a fixed object for manually constructed throttles.
	 *
	 * @param  <Type> Object type.
	 * @param  object Object.
	 * @return        The provider.
	 */
	private static <Type> ObjectProvider<Type> createProvider(
			final Type object) {
		return new ObjectProvider<>() {

			@Override
			public Type getObject() {
				return object;
			}

			@Override
			public Type getIfAvailable() {
				return object;
			}
		};
	}

	/**
	 * Creates a random key.
	 *
	 * @param  prefix Key prefix.
	 * @return        The key.
	 */
	private static String createKey(
			final String prefix) {
		return prefix + "-" + SynchronizedThrottleTest.RANDOM.nextInt(Integer.MAX_VALUE);
	}

	/**
	 * Gets the number of recorded synchronization phases with the given outcome.
	 *
	 * @param  meterRegistry Meter registry.
	 * @param  phase         Phase.
	 * @param  outcome       Outcome.
	 * @return               The number of phases.
	 */
	private static long getSyncCount(
			final MeterRegistry meterRegistry,
			final String phase,
			final String outcome) {
		final Timer timer = meterRegistry.find(SynchronizedThrottle.SYNC_METRIC).tag("phase", phase).tag("outcome", outcome).timer();
		return (timer == null ? 0 : timer.count());
	}

	/**
	 * Creates throttle properties for tests.
	 *
	 * @param  persistenceEnabled If runs are shared through the database.
	 * @return                    The properties.
	 */
	private SynchronizedThrottleProperties createProperties(
			final boolean persistenceEnabled) {
		final SynchronizedThrottleProperties properties = new SynchronizedThrottleProperties();
		properties.setEnabled(true);
		properties.setPersistenceEnabled(persistenceEnabled);
		properties.setTableName(SynchronizedThrottleTest.TABLE_NAME);
		return properties;
	}

	/**
	 * Creates a repository on the test database.
	 *
	 * @param  properties Properties (table name and query timeout).
	 * @return            The repository.
	 */
	private SynchronizedThrottleRepository createRepository(
			final SynchronizedThrottleProperties properties) {
		return new SynchronizedThrottleRepository(this.dataSource, properties.getTableName(), properties.getQueryTimeout());
	}

	/**
	 * Creates a throttle sharing runs through the test database (each throttle
	 * stands for an instance).
	 *
	 * @param  properties Properties.
	 * @return            The throttle.
	 */
	private SynchronizedThrottle createThrottle(
			final SynchronizedThrottleProperties properties) {
		return new SynchronizedThrottle(properties, SynchronizedThrottleTest.createProvider(this.createRepository(properties)));
	}

	/**
	 * Tests that the throttle is a bean of the application context by default.
	 */
	@Test
	@DisplayName("The throttle is created by default")
	public void testBean() {
		Assertions.assertNotNull(this.contextThrottle);
		Assertions.assertTrue(this.contextThrottle.isEnabled());
		Assertions.assertNotNull(this.contextRepositoryProvider.getIfAvailable());
	}

	/**
	 * Tests that a key runs once per interval, independently of other keys and
	 * throttles.
	 */
	@Test
	@DisplayName("A key runs once per interval; keys and throttles are independent")
	public void testOncePerInterval() {
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null);
		final String key = SynchronizedThrottleTest.createKey("oncePerInterval");

		final SynchronizedThrottleReservation reservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key,
				SynchronizedThrottleTest.LONG_INTERVAL);
		Assertions.assertNotNull(reservation);
		Assertions.assertEquals(SynchronizedThrottleTest.THROTTLE_NAME, reservation.getThrottleName());
		Assertions.assertEquals(key, reservation.getKey());
		Assertions.assertEquals(SynchronizedThrottleTest.LONG_INTERVAL.toMillis(), reservation.getNextRunAfter() - reservation.getAcquiredAt());
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));

		// Other keys and other throttles with the same key are independent.
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key + "-other", SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME + "Other", key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME + "Other", key, SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that invalid arguments are rejected.
	 */
	@Test
	@DisplayName("Invalid throttle names, keys and intervals are rejected")
	public void testInvalidArguments() {
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null);
		Assertions.assertThrows(IllegalArgumentException.class, () -> throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, "key", null));
		Assertions.assertThrows(IllegalArgumentException.class, () -> throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, "key", Duration.ZERO));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, "key", Duration.ofSeconds(-1)));
		Assertions.assertThrows(IllegalArgumentException.class, () -> throttle.tryAcquire(null, "key", SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertThrows(IllegalArgumentException.class, () -> throttle.tryAcquire("", "key", SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> throttle.tryAcquire("name" + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + "part", "key", SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, null, SynchronizedThrottleTest.LONG_INTERVAL));

		// Keys may contain the separator.
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, "part" + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + "key",
				SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that a key runs again once its interval passed, and that a release
	 * of the earlier run then keeps the later one.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A key runs again after the interval; releasing the earlier run keeps the later one")
	public void testReacquireAfterInterval() throws Exception {
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null);
		final String key = SynchronizedThrottleTest.createKey("reacquire");
		final Duration interval = Duration.ofMillis(300);

		final SynchronizedThrottleReservation firstReservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, interval);
		Assertions.assertNotNull(firstReservation);
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, interval));

		// Once the interval passes, the key runs again.
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, interval), Objects::nonNull,
				TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

		// Releasing the earlier run does not give the key back: the later run stays.
		Assertions.assertFalse(throttle.release(firstReservation));
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, interval));
	}

	/**
	 * Tests that a release gives the key back, once.
	 */
	@Test
	@DisplayName("A release gives the key back, once")
	public void testRelease() {
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null);
		final String key = SynchronizedThrottleTest.createKey("release");

		final SynchronizedThrottleReservation firstReservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key,
				SynchronizedThrottleTest.LONG_INTERVAL);
		Assertions.assertTrue(throttle.release(firstReservation));
		Assertions.assertFalse(throttle.release(firstReservation));
		Assertions.assertFalse(throttle.release(null));

		// The key is free again; the used reservation cannot release the new run.
		final SynchronizedThrottleReservation secondReservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key,
				SynchronizedThrottleTest.LONG_INTERVAL);
		Assertions.assertNotNull(secondReservation);
		Assertions.assertFalse(throttle.release(firstReservation));
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertTrue(throttle.release(secondReservation));
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that concurrent callers of the same key never both acquire it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Concurrent callers of the same key acquire it exactly once")
	public void testConcurrentAcquire() throws Exception {
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null);
		final int threadCount = 16;
		final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		try {
			for (int round = 0; round < 20; round++) {
				final String key = SynchronizedThrottleTest.createKey("concurrent");
				final CountDownLatch startLatch = new CountDownLatch(1);
				final List<Future<SynchronizedThrottleReservation>> attempts = new ArrayList<>();
				for (int thread = 0; thread < threadCount; thread++) {
					attempts.add(executor.submit(() -> {
						startLatch.await();
						return throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL);
					}));
				}
				startLatch.countDown();
				int acquiredCount = 0;
				for (final Future<SynchronizedThrottleReservation> attempt : attempts) {
					acquiredCount += (attempt.get(TestHelper.LONG_WAIT, TimeUnit.MILLISECONDS) == null ? 0 : 1);
				}
				Assertions.assertEquals(1, acquiredCount);
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Tests that instances share runs through the database, and that an
	 * instance's own run pulled back stays local (releasable).
	 */
	@Test
	@DisplayName("Instances share runs through the database")
	public void testSynchronization() {
		final SynchronizedThrottleProperties properties = this.createProperties(true);
		final SynchronizedThrottle instanceA = this.createThrottle(properties);
		final SynchronizedThrottle instanceB = this.createThrottle(properties);
		final String key = SynchronizedThrottleTest.createKey("synchronization");

		// A runs the key; once A pushes and B pulls, B refuses it.
		final SynchronizedThrottleReservation reservation = instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key,
				SynchronizedThrottleTest.LONG_INTERVAL);
		Assertions.assertNotNull(reservation);
		instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, SynchronizedThrottleTest.createKey("other"), SynchronizedThrottleTest.LONG_INTERVAL);
		instanceA.synchronize();
		instanceB.synchronize();
		Assertions.assertNull(instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertEquals(1, new JdbcTemplate(this.dataSource).queryForObject(
				"SELECT COUNT(*) FROM " + SynchronizedThrottleTest.TABLE_NAME + " WHERE throttle_key = ?", Integer.class,
				SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + key));

		// A pulled its own run back without replacing it: it can still release it.
		instanceA.synchronize();
		Assertions.assertTrue(instanceA.release(reservation));

		// The release after the push is local only: A runs the key again (its
		// released run is not pulled back), B keeps refusing it.
		instanceA.synchronize();
		instanceB.synchronize();
		Assertions.assertNull(instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNotNull(instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that a run released before its push is never shared.
	 */
	@Test
	@DisplayName("A run released before its push is not shared")
	public void testReleaseBeforePush() {
		final SynchronizedThrottleProperties properties = this.createProperties(true);
		final SynchronizedThrottle instanceA = this.createThrottle(properties);
		final SynchronizedThrottle instanceB = this.createThrottle(properties);
		final String key = SynchronizedThrottleTest.createKey("releaseBeforePush");

		Assertions.assertTrue(instanceA
				.release(instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL)));
		instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, SynchronizedThrottleTest.createKey("other"), SynchronizedThrottleTest.LONG_INTERVAL);
		instanceA.synchronize();
		instanceB.synchronize();
		Assertions.assertEquals(0, new JdbcTemplate(this.dataSource).queryForObject(
				"SELECT COUNT(*) FROM " + SynchronizedThrottleTest.TABLE_NAME + " WHERE throttle_key = ?", Integer.class,
				SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + key));
		Assertions.assertNotNull(instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that the synchronization deletes expired rows and does not pull them.
	 */
	@Test
	@DisplayName("Expired rows are deleted and not pulled")
	public void testExpiredRowsDeleted() {
		final SynchronizedThrottleRepository store = new SynchronizedThrottleRepository(this.dataSource, SynchronizedThrottleTest.TABLE_NAME);
		store.ensureSchema();
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		final long now = SynchronizedThrottle.getCurrentWallClockTimestamp();
		final String expiredKey = SynchronizedThrottleTest.createKey("expired");
		final String activeKey = SynchronizedThrottleTest.createKey("active");
		final String insert = "INSERT INTO " + SynchronizedThrottleTest.TABLE_NAME
				+ " (throttle_key, last_run_at, next_run_after, synchronized_at) VALUES (?, ?, ?, (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT)";
		jdbcTemplate.update(insert, SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + expiredKey, now - 120_000,
				now - 60_000);
		jdbcTemplate.update(insert, SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + activeKey, now,
				now + SynchronizedThrottleTest.LONG_INTERVAL.toMillis());

		final SynchronizedThrottle throttle = this.createThrottle(this.createProperties(true));
		throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, SynchronizedThrottleTest.createKey("other"), SynchronizedThrottleTest.LONG_INTERVAL);
		throttle.synchronize();
		final String count = "SELECT COUNT(*) FROM " + SynchronizedThrottleTest.TABLE_NAME + " WHERE throttle_key = ?";
		Assertions.assertEquals(0, jdbcTemplate.queryForObject(count, Integer.class,
				SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + expiredKey));
		Assertions.assertEquals(1, jdbcTemplate.queryForObject(count, Integer.class,
				SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + activeKey));
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, expiredKey, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, activeKey, SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that the cleanup runs from one instance at a time.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The cleanup is skipped while another instance holds its lock")
	public void testCleanupLock() throws Exception {
		final SynchronizedThrottleRepository store = new SynchronizedThrottleRepository(this.dataSource, SynchronizedThrottleTest.TABLE_NAME);
		store.ensureSchema();
		try (Connection lockHolder = this.dataSource.getConnection()) {
			try (PreparedStatement statement = lockHolder.prepareStatement("SELECT pg_advisory_lock(?)")) {
				statement.setLong(1, SynchronizedThrottleRepository.CLEANUP_LOCK_KEY);
				statement.executeQuery().close();
			}
			try {
				Assertions.assertNull(store.deleteExpired());
			}
			finally {
				try (PreparedStatement statement = lockHolder.prepareStatement("SELECT pg_advisory_unlock(?)")) {
					statement.setLong(1, SynchronizedThrottleRepository.CLEANUP_LOCK_KEY);
					statement.executeQuery().close();
				}
			}
		}
		Assertions.assertNotNull(store.deleteExpired());
	}

	/**
	 * Tests that the synchronization does not touch the database until the
	 * instance acquires a run.
	 */
	@Test
	@DisplayName("The synchronization skips the database until a run is acquired")
	public void testSkipUntilUsed() {
		final String tableName = "synchronized_throttle_skip_test";
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
		final SynchronizedThrottleProperties properties = this.createProperties(true);
		properties.setTableName(tableName);
		final MeterRegistry meterRegistry = new SimpleMeterRegistry();
		final SynchronizedThrottle throttle = new SynchronizedThrottle(properties, SynchronizedThrottleTest.createProvider(this.createRepository(properties)),
				SynchronizedThrottleTest.createProvider(meterRegistry));

		throttle.synchronize();
		for (final String phase : List.of("push", "pull", "delete")) {
			Assertions.assertEquals(1, SynchronizedThrottleTest.getSyncCount(meterRegistry, phase, "skipped"));
		}
		Assertions.assertNull(jdbcTemplate.queryForObject("SELECT to_regclass(?)::TEXT", String.class, tableName));

		throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, "key", SynchronizedThrottleTest.LONG_INTERVAL);
		throttle.synchronize();
		Assertions.assertEquals(1, SynchronizedThrottleTest.getSyncCount(meterRegistry, "push", "success"));
		Assertions.assertEquals(1, SynchronizedThrottleTest.getSyncCount(meterRegistry, "pull", "success"));
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class));
		Assertions.assertEquals(1.0, meterRegistry.find(SynchronizedThrottle.SYNC_ROWS_METRIC).tag("phase", "push").counter().count());
		Assertions.assertEquals(1.0, meterRegistry.find(SynchronizedThrottle.RECORDS_METRIC).gauge().value());
	}

	/**
	 * Asserts that a throttle whose database fails keeps working locally, and
	 * that its synchronization fails without throwing (then retries the push).
	 *
	 * @param properties Properties.
	 * @param dataSource Failing data source.
	 */
	private void assertFailsOpen(
			final SynchronizedThrottleProperties properties,
			final DataSource dataSource) {
		final MeterRegistry meterRegistry = new SimpleMeterRegistry();
		final SynchronizedThrottleRepository repository = new SynchronizedThrottleRepository(dataSource, properties.getTableName(),
				properties.getQueryTimeout());
		final SynchronizedThrottle throttle = new SynchronizedThrottle(properties, SynchronizedThrottleTest.createProvider(repository),
				SynchronizedThrottleTest.createProvider(meterRegistry));
		final String key = SynchronizedThrottleTest.createKey("failOpen");

		final SynchronizedThrottleReservation reservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key,
				SynchronizedThrottleTest.LONG_INTERVAL);
		Assertions.assertNotNull(reservation);
		Assertions.assertDoesNotThrow(throttle::synchronize);
		Assertions.assertDoesNotThrow(throttle::synchronize);
		Assertions.assertEquals(2, SynchronizedThrottleTest.getSyncCount(meterRegistry, "push", "failure"));

		// The throttle keeps working locally.
		Assertions.assertNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key + "-other", SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertTrue(throttle.release(reservation));
		Assertions.assertNotNull(throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
	}

	/**
	 * Tests that the throttle fails open when its table is missing.
	 */
	@Test
	@DisplayName("A missing table fails open")
	public void testFailOpenMissingTable() {
		final SynchronizedThrottleProperties properties = this.createProperties(true);
		properties.setTableName("synchronized_throttle_missing_test");
		properties.setCreateSchema(false);
		new JdbcTemplate(this.dataSource).execute("DROP TABLE IF EXISTS synchronized_throttle_missing_test");
		this.assertFailsOpen(properties, this.dataSource);
	}

	/**
	 * Tests that the throttle fails open when the database is unreachable.
	 */
	@Test
	@DisplayName("An unreachable database fails open")
	public void testFailOpenUnreachableDatabase() {
		final DriverManagerDataSource unreachableDataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/missing", "unreachable-test-user",
				"unreachable-test-password");
		final SynchronizedThrottleProperties properties = this.createProperties(true);
		properties.setCreateSchema(false);
		this.assertFailsOpen(properties, unreachableDataSource);
	}

	/**
	 * Tests that a throttle without persistence is local only.
	 */
	@Test
	@DisplayName("Without persistence, the throttle is local only")
	public void testPersistenceDisabled() {
		final String tableName = "synchronized_throttle_local_test";
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
		final SynchronizedThrottleProperties properties = this.createProperties(false);
		properties.setTableName(tableName);
		final SynchronizedThrottle instanceA = this.createThrottle(properties);
		final SynchronizedThrottle instanceB = this.createThrottle(properties);
		final String key = SynchronizedThrottleTest.createKey("local");

		Assertions.assertNotNull(instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, SynchronizedThrottleTest.createKey("other"), SynchronizedThrottleTest.LONG_INTERVAL);
		instanceA.synchronize();
		instanceB.synchronize();
		Assertions.assertNotNull(instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNull(instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		Assertions.assertNull(jdbcTemplate.queryForObject("SELECT to_regclass(?)::TEXT", String.class, tableName));
	}

	/**
	 * Tests that the local sweep removes expired runs.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The synchronization sweeps expired local runs")
	public void testLocalSweep() throws Exception {
		final MeterRegistry meterRegistry = new SimpleMeterRegistry();
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null,
				SynchronizedThrottleTest.createProvider(meterRegistry));
		throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, SynchronizedThrottleTest.createKey("sweep"), Duration.ofMillis(50));
		throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, SynchronizedThrottleTest.createKey("kept"), SynchronizedThrottleTest.LONG_INTERVAL);
		throttle.synchronize();
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			throttle.synchronize();
			return meterRegistry.find(SynchronizedThrottle.RECORDS_METRIC).gauge().value();
		}, records -> records == 1.0, TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
	}

	/**
	 * Gets the previous run kept by a reservation (package-private, read
	 * reflectively).
	 *
	 * @param  reservation Reservation.
	 * @return             The previous run (null when there was none).
	 * @throws Exception   If it cannot be read.
	 */
	private static Object getPreviousRun(
			final SynchronizedThrottleReservation reservation) throws Exception {
		final Method getPreviousRun = SynchronizedThrottleReservation.class.getDeclaredMethod("getPreviousRun");
		getPreviousRun.setAccessible(true);
		return getPreviousRun.invoke(reservation);
	}

	/**
	 * Gets the reservation held by a local run (package-private, read
	 * reflectively).
	 *
	 * @param  localRun  Local run.
	 * @return           Its reservation (null when none).
	 * @throws Exception If it cannot be read.
	 */
	private static Object getLocalRunReservation(
			final Object localRun) throws Exception {
		final Method reservation = localRun.getClass().getDeclaredMethod("reservation");
		reservation.setAccessible(true);
		return reservation.invoke(localRun);
	}

	/**
	 * Tests that a reservation keeps one step of history: its previous run holds
	 * no reservation, so a busy key does not chain every earlier run.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A reservation keeps one step of history")
	public void testOneStepHistory() throws Exception {
		final SynchronizedThrottle throttle = new SynchronizedThrottle(this.createProperties(false), null);
		final String key = SynchronizedThrottleTest.createKey("history");
		final Duration interval = Duration.ofMillis(1);
		SynchronizedThrottleReservation lastReservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, interval);
		Assertions.assertNull(SynchronizedThrottleTest.getPreviousRun(lastReservation));

		// Acquires the key again and again (each time its 1 ms interval passed).
		SynchronizedThrottleReservation earlierReservation = null;
		for (int acquisition = 0; acquisition < 50; acquisition++) {
			final long deadline = SynchronizedThrottle.getCurrentWallClockTimestamp() + TestHelper.LONG_WAIT;
			SynchronizedThrottleReservation reservation = null;
			while ((reservation == null) && (SynchronizedThrottle.getCurrentWallClockTimestamp() < deadline)) {
				reservation = throttle.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, interval);
			}
			Assertions.assertNotNull(reservation);
			final Object previousRun = SynchronizedThrottleTest.getPreviousRun(reservation);
			Assertions.assertNotNull(previousRun);
			Assertions.assertNull(SynchronizedThrottleTest.getLocalRunReservation(previousRun));
			earlierReservation = lastReservation;
			lastReservation = reservation;
		}

		// Releasing the last run restores the previous one without its reservation.
		Assertions.assertTrue(throttle.release(lastReservation));
		Assertions.assertFalse(throttle.release(earlierReservation));
	}

	/**
	 * Tests that a push only moves a key forward.
	 */
	@Test
	@DisplayName("A push only moves a key forward")
	public void testPushOnlyMovesForward() {
		final SynchronizedThrottleRepository repository = this.createRepository(this.createProperties(true));
		repository.ensureSchema();
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		final String throttleKey = SynchronizedThrottleTest.THROTTLE_NAME + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR
				+ SynchronizedThrottleTest.createKey("forward");
		final String select = "SELECT last_run_at, next_run_after, synchronized_at FROM " + SynchronizedThrottleTest.TABLE_NAME + " WHERE throttle_key = ?";

		repository.push(Map.of(throttleKey, new SynchronizedThrottleRepository.ThrottleRun(1_000, 2_000)));
		final Map<String, Object> firstRow = jdbcTemplate.queryForMap(select, throttleKey);

		// An earlier run does not replace the stored one.
		repository.push(Map.of(throttleKey, new SynchronizedThrottleRepository.ThrottleRun(500, 1_500)));
		Assertions.assertEquals(firstRow, jdbcTemplate.queryForMap(select, throttleKey));

		// The same run again leaves the row (and its "synchronized at") untouched.
		repository.push(Map.of(throttleKey, new SynchronizedThrottleRepository.ThrottleRun(1_000, 2_000)));
		Assertions.assertEquals(firstRow, jdbcTemplate.queryForMap(select, throttleKey));

		// A later run replaces it.
		repository.push(Map.of(throttleKey, new SynchronizedThrottleRepository.ThrottleRun(3_000, 4_000)));
		final Map<String, Object> laterRow = jdbcTemplate.queryForMap(select, throttleKey);
		Assertions.assertEquals(3_000L, ((Number) laterRow.get("last_run_at")).longValue());
		Assertions.assertEquals(4_000L, ((Number) laterRow.get("next_run_after")).longValue());
		Assertions.assertTrue(((Number) laterRow.get("synchronized_at")).longValue() >= ((Number) firstRow.get("synchronized_at")).longValue());
	}

	/**
	 * Tests that a later run from another instance replaces the local one: its
	 * reservation can no longer release the key, and the key is refused.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A later run pulled from another instance replaces the local run")
	public void testLaterPulledRunReplacesLocal() throws Exception {
		final SynchronizedThrottleProperties properties = this.createProperties(true);
		final SynchronizedThrottle instanceA = this.createThrottle(properties);
		final SynchronizedThrottle instanceB = this.createThrottle(properties);
		final String key = SynchronizedThrottleTest.createKey("laterPulled");
		final Duration shortInterval = Duration.ofMillis(200);

		final SynchronizedThrottleReservation reservationA = instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, shortInterval);
		Assertions.assertNotNull(reservationA);
		Assertions.assertTrue(TestHelper.waitUntilValid(SynchronizedThrottle::getCurrentWallClockTimestamp,
				now -> now > reservationA.getNextRunAfter(), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

		// B runs the key later (with a long interval), pushes, and A pulls.
		Assertions.assertNotNull(instanceB.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, SynchronizedThrottleTest.LONG_INTERVAL));
		instanceB.synchronize();
		instanceA.synchronize();
		Assertions.assertFalse(instanceA.release(reservationA));
		Assertions.assertNull(instanceA.tryAcquire(SynchronizedThrottleTest.THROTTLE_NAME, key, shortInterval));
	}

}
