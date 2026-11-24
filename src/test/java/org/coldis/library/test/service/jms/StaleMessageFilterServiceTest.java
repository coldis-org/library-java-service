package org.coldis.library.test.service.jms;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.jms.StaleMessageFilterJdbcStore;
import org.coldis.library.service.jms.StaleMessageFilterProperties;
import org.coldis.library.service.jms.StaleMessageFilterService;
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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Stale message filter service test.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Stale message filter service")
public class StaleMessageFilterServiceTest extends ContainerTestHelper {

	/**
	 * Random.
	 */
	private static final Random RANDOM = new Random();

	/**
	 * Data source.
	 */
	@Autowired
	private DataSource dataSource;

	/**
	 * Creates a provider of a fixed object for manually constructed service
	 * instances.
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
	 * Creates a data source provider for manually constructed service instances.
	 */
	private ObjectProvider<DataSource> createDataSourceProvider() {
		return StaleMessageFilterServiceTest.createProvider(this.dataSource);
	}

	/**
	 * Creates an empty store table for a test.
	 */
	private StaleMessageFilterJdbcStore createEmptyStore(
			final String tableName) {
		final StaleMessageFilterJdbcStore store = new StaleMessageFilterJdbcStore(this.dataSource, tableName);
		store.ensureSchema();
		new JdbcTemplate(this.dataSource).update("DELETE FROM " + tableName);
		return store;
	}

	/**
	 * Gets the number of recorded synchronization phases with the given outcome.
	 */
	private static long getSyncCount(
			final MeterRegistry meterRegistry,
			final String phase,
			final String outcome) {
		final Timer timer = meterRegistry.find(StaleMessageFilterService.SYNC_METRIC).tag("phase", phase).tag("outcome", outcome).timer();
		return (timer == null ? 0 : timer.count());
	}

	/**
	 * If a store row is locked by another transaction, or was already written by
	 * the push (processed at 1).
	 */
	private boolean isRowLockedOrWritten(
			final String tableName,
			final String messageKey) {
		boolean lockedOrWritten = false;
		try (Connection connection = this.dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection
					.prepareStatement("SELECT processed_at FROM " + tableName + " WHERE message_key = ? FOR UPDATE NOWAIT")) {
				statement.setString(1, messageKey);
				try (java.sql.ResultSet resultSet = statement.executeQuery()) {
					lockedOrWritten = resultSet.next() && (resultSet.getLong(1) == 1L);
				}
			}
			catch (final SQLException exception) {
				// 55P03: lock not available.
				lockedOrWritten = "55P03".equals(exception.getSQLState());
				if (!lockedOrWritten) {
					throw exception;
				}
			}
			finally {
				connection.rollback();
			}
		}
		catch (final SQLException exception) {
			throw new IllegalStateException(exception);
		}
		return lockedOrWritten;
	}

	/**
	 * Creates enabled filter properties for tests.
	 */
	private StaleMessageFilterProperties createProperties(
			final boolean persistenceEnabled) {
		final StaleMessageFilterProperties properties = new StaleMessageFilterProperties();
		properties.setEnabled(true);
		properties.setPersistenceEnabled(persistenceEnabled);
		properties.setClockSkewMargin(Duration.ofMillis(100));
		properties.setWindow(Duration.ofMinutes(10));
		properties.setTableName("stale_message_filter_test");
		return properties;
	}

	/**
	 * Tests the drop decision semantics (in-memory only).
	 */
	@Test
	@DisplayName("Drops a keyed message only when a same-key processing started after it was posted")
	public void testDropDecision() {
		final StaleMessageFilterService service = new StaleMessageFilterService(this.createProperties(false), null);
		final long now = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
		final String messageKey = "dropDecisionKey-" + StaleMessageFilterServiceTest.RANDOM.nextInt();

		// Without a processing record, nothing is stale.
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey, now - 10_000));

		// Records a processing: messages posted before it (beyond the margin) become
		// stale, while messages posted within the margin or after it do not.
		service.recordProcessing("queue", messageKey, now);
		Assertions.assertTrue(service.hasNewerProcessing("queue", messageKey, now - 10_000));
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey, now - 50));
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey, now));
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey, now + 10_000));

		// Other keys and destinations are unaffected.
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey + "-other", now - 10_000));
		Assertions.assertFalse(service.hasNewerProcessing("queue-other", messageKey, now - 10_000));

		// Older processing records do not overwrite newer ones.
		service.recordProcessing("queue", messageKey, now - 60_000);
		Assertions.assertTrue(service.hasNewerProcessing("queue", messageKey, now - 10_000));
	}

	/**
	 * Tests that the filter no-ops when disabled.
	 */
	@Test
	@DisplayName("A disabled filter never drops messages")
	public void testDisabled() {
		final StaleMessageFilterProperties properties = this.createProperties(false);
		properties.setEnabled(false);
		final StaleMessageFilterService service = new StaleMessageFilterService(properties, null);
		final long now = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
		final String messageKey = "disabledKey-" + StaleMessageFilterServiceTest.RANDOM.nextInt();

		service.recordProcessing("queue", messageKey, now);
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey, now - 10_000));
	}

	/**
	 * Tests that processing records older than the window are swept from the map.
	 */
	@Test
	@DisplayName("Processing records older than the window are swept")
	public void testWindowSweep() {
		final StaleMessageFilterProperties properties = this.createProperties(false);
		properties.setWindow(Duration.ofSeconds(30));
		final StaleMessageFilterService service = new StaleMessageFilterService(properties, null);
		final long now = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
		final String messageKey = "sweepKey-" + StaleMessageFilterServiceTest.RANDOM.nextInt();

		// A record older than the window drops even older messages until swept.
		service.recordProcessing("queue", messageKey, now - 60_000);
		Assertions.assertTrue(service.hasNewerProcessing("queue", messageKey, now - 120_000));

		// The synchronization sweep removes it (fail-open afterwards).
		service.synchronize();
		Assertions.assertFalse(service.hasNewerProcessing("queue", messageKey, now - 120_000));
	}

	/**
	 * Tests that instances share processing records through the database.
	 */
	@Test
	@DisplayName("Instances share processing records through the database")
	public void testSynchronization() {
		final StaleMessageFilterProperties properties = this.createProperties(true);
		final StaleMessageFilterService instanceA = new StaleMessageFilterService(properties, this.createDataSourceProvider());
		final StaleMessageFilterService instanceB = new StaleMessageFilterService(properties, this.createDataSourceProvider());
		final long now = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
		final String messageKey = "syncKey-" + StaleMessageFilterServiceTest.RANDOM.nextInt();

		// Instance A records a processing locally: instance B does not know it yet.
		instanceA.recordProcessing("queue", messageKey, now);
		Assertions.assertFalse(instanceB.hasNewerProcessing("queue", messageKey, now - 10_000));

		// After A pushes and B pulls, B drops older same-key messages too.
		instanceA.synchronize();
		instanceB.synchronize();
		Assertions.assertTrue(instanceB.hasNewerProcessing("queue", messageKey, now - 10_000));
		Assertions.assertFalse(instanceB.hasNewerProcessing("queue", messageKey, now + 10_000));

		// Newer processing records win on push and pull.
		instanceB.recordProcessing("queue", messageKey, now + 60_000);
		instanceB.synchronize();
		instanceA.synchronize();
		Assertions.assertTrue(instanceA.hasNewerProcessing("queue", messageKey, now + 30_000));
	}

	/**
	 * Tests that pushes write rows in key order (whatever the map order), so
	 * instances lock shared rows in the same order and do not deadlock.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Pushes lock rows in key order")
	public void testPushInKeyOrder() throws Exception {
		final String tableName = "stale_message_filter_order_test";
		final StaleMessageFilterJdbcStore store = this.createEmptyStore(tableName);
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		for (final String messageKey : List.of("key-a", "key-b", "key-c")) {
			jdbcTemplate.update("INSERT INTO " + tableName + " (message_key, processed_at, synchronized_at) VALUES (?, 0, 0)", messageKey);
		}

		// Another transaction holds the middle row, so the push blocks on it.
		final CompletableFuture<Void> push;
		try (Connection lockHolder = this.dataSource.getConnection()) {
			lockHolder.setAutoCommit(false);
			try (PreparedStatement statement = lockHolder.prepareStatement("SELECT 1 FROM " + tableName + " WHERE message_key = 'key-b' FOR UPDATE")) {
				statement.executeQuery().close();
			}

			// Pushes in reverse key order: the store still writes "key-a" first, so
			// while it waits for "key-b" it holds (or wrote) "key-a" and has not
			// touched "key-c".
			final Map<String, Long> records = new LinkedHashMap<>();
			records.put("key-c", 1L);
			records.put("key-b", 1L);
			records.put("key-a", 1L);
			push = CompletableFuture.runAsync(() -> store.push(records));
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.isRowLockedOrWritten(tableName, "key-a"), Boolean::booleanValue,
					TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			Assertions.assertFalse(this.isRowLockedOrWritten(tableName, "key-c"));
			lockHolder.rollback();
		}

		// Once the middle row is released, the push completes.
		push.get(TestHelper.LONG_WAIT, TimeUnit.MILLISECONDS);
		Assertions.assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName + " WHERE processed_at = 1", Integer.class));
	}

	/**
	 * Tests that pulls only return the rows of the given destinations, matching
	 * LIKE wildcards in destination names literally.
	 */
	@Test
	@DisplayName("Pulls only the rows of the given destinations (wildcards matched literally)")
	public void testPullFilteredByDestination() {
		final StaleMessageFilterJdbcStore store = this.createEmptyStore("stale_message_filter_pull_test");
		store.push(Map.of("dest_1|key", 1L, "destX1|key", 1L, "dest%2|key", 1L, "destYY2|key", 1L, "dest\\3|key", 1L, "dest3|key", 1L, "other|key",
				1L));

		final Set<String> pulledKeys = store.pull(0, List.of("dest_1|", "dest%2|", "dest\\3|")).stream()
				.map(StaleMessageFilterJdbcStore.ProcessingRecord::messageKey).collect(Collectors.toSet());
		Assertions.assertEquals(Set.of("dest_1|key", "dest%2|key", "dest\\3|key"), pulledKeys);
	}

	/**
	 * Tests that the cleanup deletes rows before the bound cutoff, and only when
	 * no other instance holds the cleanup lock.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The cleanup deletes rows before the cutoff, from one instance at a time")
	public void testDeleteWithCutoffAndLock() throws Exception {
		final String tableName = "stale_message_filter_delete_test";
		final StaleMessageFilterJdbcStore store = this.createEmptyStore(tableName);
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		jdbcTemplate.update("INSERT INTO " + tableName + " (message_key, processed_at, synchronized_at) VALUES ('old', 0, 1000), ('recent', 0, 5000)");

		// Another instance running the cleanup: nothing is deleted.
		try (Connection lockHolder = this.dataSource.getConnection()) {
			try (PreparedStatement statement = lockHolder.prepareStatement("SELECT pg_advisory_lock(?)")) {
				statement.setLong(1, StaleMessageFilterJdbcStore.CLEANUP_LOCK_KEY);
				statement.executeQuery().close();
			}
			Assertions.assertNull(store.deleteSynchronizedBefore(3000));
			Assertions.assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class));
			try (PreparedStatement statement = lockHolder.prepareStatement("SELECT pg_advisory_unlock(?)")) {
				statement.setLong(1, StaleMessageFilterJdbcStore.CLEANUP_LOCK_KEY);
				statement.executeQuery().close();
			}
		}

		// Without it, only the rows before the cutoff are deleted.
		Assertions.assertEquals(1, store.deleteSynchronizedBefore(3000));
		Assertions.assertEquals(List.of("recent"), jdbcTemplate.queryForList("SELECT message_key FROM " + tableName, String.class));
	}

	/**
	 * Tests that the synchronization does not touch the database until the
	 * instance sees a keyed message.
	 */
	@Test
	@DisplayName("The synchronization skips the database until a keyed message is seen")
	public void testSkipWithoutKeyedDestination() {
		final String tableName = "stale_message_filter_skip_test";
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
		final StaleMessageFilterProperties properties = this.createProperties(true);
		properties.setTableName(tableName);
		final MeterRegistry meterRegistry = new SimpleMeterRegistry();
		final StaleMessageFilterService service = new StaleMessageFilterService(properties, this.createDataSourceProvider(),
				StaleMessageFilterServiceTest.createProvider(meterRegistry));

		// No keyed destination: every phase is skipped and the table is not even
		// created.
		service.synchronize();
		for (final String phase : List.of("push", "pull", "delete")) {
			Assertions.assertEquals(1, StaleMessageFilterServiceTest.getSyncCount(meterRegistry, phase, "skipped"));
		}
		Assertions.assertNull(jdbcTemplate.queryForObject("SELECT to_regclass(?)::TEXT", String.class, tableName));

		// Once a keyed message is processed, the synchronization runs.
		service.recordProcessing("queue", "skipKey", DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()));
		service.synchronize();
		Assertions.assertEquals(Set.of("queue"), service.getKeyedDestinations());
		Assertions.assertEquals(1, StaleMessageFilterServiceTest.getSyncCount(meterRegistry, "push", "success"));
		Assertions.assertEquals(1, StaleMessageFilterServiceTest.getSyncCount(meterRegistry, "pull", "success"));
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class));
	}

	/**
	 * Tests that an instance only pulls the destinations it has seen keyed
	 * messages on, and that records keep their origin (local or pulled).
	 */
	@Test
	@DisplayName("An instance pulls only its keyed destinations; records keep their origin")
	public void testPullOnlyKeyedDestinations() {
		final StaleMessageFilterProperties properties = this.createProperties(true);
		final MeterRegistry meterRegistry = new SimpleMeterRegistry();
		final StaleMessageFilterService instanceA = new StaleMessageFilterService(properties, this.createDataSourceProvider());
		final StaleMessageFilterService instanceB = new StaleMessageFilterService(properties, this.createDataSourceProvider(),
				StaleMessageFilterServiceTest.createProvider(meterRegistry));
		final long now = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
		final String random = Integer.toString(StaleMessageFilterServiceTest.RANDOM.nextInt());
		final String consumedQueue = "consumed_" + random;
		final String otherQueue = "other_" + random;

		// A processes keys on both queues; B only sees keyed messages on one.
		instanceA.recordProcessing(consumedQueue, "key", now);
		instanceA.recordProcessing(otherQueue, "key", now);
		instanceA.synchronize();
		Assertions.assertNull(instanceB.getNewerProcessingSource(consumedQueue, "key", now - 10_000));
		instanceB.synchronize();

		// B pulled the queue it consumes, and only that one.
		Assertions.assertEquals(Set.of(consumedQueue), instanceB.getKeyedDestinations());
		Assertions.assertEquals(StaleMessageFilterService.Source.PULLED, instanceB.getNewerProcessingSource(consumedQueue, "key", now - 10_000));
		Assertions.assertNull(instanceB.getNewerProcessingSource(otherQueue, "key", now - 10_000));

		// A's own records pulled back stay local; a newer local record on B wins.
		Assertions.assertEquals(StaleMessageFilterService.Source.LOCAL, instanceA.getNewerProcessingSource(consumedQueue, "key", now - 10_000));
		instanceB.recordProcessing(consumedQueue, "key", now + 60_000);
		Assertions.assertEquals(StaleMessageFilterService.Source.LOCAL, instanceB.getNewerProcessingSource(consumedQueue, "key", now));

		// The synchronization and the map size are measured.
		Assertions.assertEquals(1, StaleMessageFilterServiceTest.getSyncCount(meterRegistry, "pull", "success"));
		Assertions.assertTrue(meterRegistry.find(StaleMessageFilterService.SYNC_ROWS_METRIC).tag("phase", "pull").counter().count() >= 1);
		Assertions.assertTrue(meterRegistry.find(StaleMessageFilterService.RECORDS_METRIC).gauge().value() >= 1);
	}

	/**
	 * Tests that the synchronization runs on its own daemon thread, started and
	 * stopped with the service.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The synchronization runs on its own thread, started and stopped with the service")
	public void testSchedulerLifecycle() throws Exception {
		final StaleMessageFilterProperties properties = this.createProperties(false);
		properties.setWindow(Duration.ofSeconds(30));
		properties.setSynchronizationInterval(Duration.ofMillis(100));
		final StaleMessageFilterService service = new StaleMessageFilterService(properties, null);
		final long now = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
		final String messageKey = "lifecycleKey-" + StaleMessageFilterServiceTest.RANDOM.nextInt();
		service.recordProcessing("queue", messageKey, now - 60_000);

		// Started: a daemon thread sweeps the expired record on its own.
		service.start();
		try {
			Assertions.assertTrue(service.isRunning());
			Assertions.assertTrue(Thread.getAllStackTraces().keySet().stream()
					.anyMatch(thread -> StaleMessageFilterService.SYNC_THREAD_NAME.equals(thread.getName()) && thread.isDaemon()));
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> service.hasNewerProcessing("queue", messageKey, now - 120_000), newer -> !newer,
					TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
		}
		finally {
			service.stop();
		}

		// Stopped: the thread ends.
		Assertions.assertFalse(service.isRunning());
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> Thread.getAllStackTraces().keySet().stream()
				.noneMatch(thread -> StaleMessageFilterService.SYNC_THREAD_NAME.equals(thread.getName()) && thread.isAlive()), Boolean::booleanValue,
				TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

		// A disabled filter does not start it.
		properties.setEnabled(false);
		service.start();
		Assertions.assertFalse(service.isRunning());
	}

}
