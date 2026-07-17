package org.coldis.library.test.service.jms;

import java.time.Duration;
import java.util.Random;

import javax.sql.DataSource;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.jms.StaleMessageFilterProperties;
import org.coldis.library.service.jms.StaleMessageFilterService;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Stale message filter service test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
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
	 * Creates a data source provider for manually constructed service instances.
	 */
	private ObjectProvider<DataSource> createDataSourceProvider() {
		return new ObjectProvider<>() {

			@Override
			public DataSource getObject() {
				return StaleMessageFilterServiceTest.this.dataSource;
			}

			@Override
			public DataSource getIfAvailable() {
				return StaleMessageFilterServiceTest.this.dataSource;
			}
		};
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

}
