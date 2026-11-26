package org.coldis.library.test.service.jms;

import java.util.Random;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.batch.BatchServiceComponent;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.StaleMessageFilterProperties;
import org.coldis.library.service.jms.StaleMessageFilterService;
import org.coldis.library.service.statistics.StatisticsEventServiceComponent;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stale message filter on the library's own queues (batch resume and delete, statistics
 * delete-expired), through the real producers, broker and listeners.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Stale message filter — library queues")
public class LibraryQueueStaleFilterIntegrationTest extends ContainerTestHelper {

	/**
	 * Batch resume queue.
	 */
	private static final String RESUME_QUEUE = "batch/resume";

	/**
	 * Batch delete queue.
	 */
	private static final String DELETE_QUEUE = "batch/delete";

	/**
	 * Statistics delete-expired queue.
	 */
	private static final String DELETE_EXPIRED_QUEUE = "statistics-event/delete/expired";

	/**
	 * Random.
	 */
	private static final Random RANDOM = new Random();

	/**
	 * Batch service.
	 */
	@Autowired
	private BatchServiceComponent batchServiceComponent;

	/**
	 * Statistics event service.
	 */
	@Autowired
	private StatisticsEventServiceComponent statisticsEventServiceComponent;

	/**
	 * Stale message filter properties.
	 */
	@Autowired
	private StaleMessageFilterProperties staleMessageFilterProperties;

	/**
	 * Stale message filter service.
	 */
	@Autowired
	private StaleMessageFilterService staleMessageFilterService;

	/**
	 * Meter registry.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Gets the count of keyed messages checked by the filter on a destination.
	 *
	 * @param  destination Destination.
	 * @return             The count.
	 */
	private Double getCheckedCount(
			final String destination) {
		return this.meterRegistry.find(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC).tag("destination", destination).counters()
				.stream().mapToDouble(Counter::count).sum();
	}

	/**
	 * Waits until a processing of the key has been recorded on the destination
	 * after the given wall-clock time.
	 *
	 * @param  destination Destination.
	 * @param  messageKey  Stale filter key.
	 * @param  after       Wall-clock epoch millis.
	 * @return             If the processing was recorded in time.
	 * @throws Exception   If the wait fails.
	 */
	private boolean waitForProcessing(
			final String destination,
			final String messageKey,
			final long after) throws Exception {
		final long postedBeforeAnyProcessing = after - this.staleMessageFilterProperties.getClockSkewMargin().toMillis() - 1;
		return TestHelper.waitUntilValid(() -> this.staleMessageFilterService.hasNewerProcessing(destination, messageKey, postedBeforeAnyProcessing),
				Boolean.TRUE::equals, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT);
	}

	/**
	 * Enables the filter (memory-only) before each test.
	 */
	@BeforeEach
	public void beforeEach() {
		this.staleMessageFilterProperties.setEnabled(true);
		this.staleMessageFilterProperties.setPersistenceEnabled(false);
	}

	/**
	 * Restores the filter configuration after each test.
	 */
	@AfterEach
	public void afterEach() {
		TestHelper.cleanClock();
		this.staleMessageFilterProperties.setEnabled(false);
		this.staleMessageFilterProperties.setPersistenceEnabled(true);
	}

	/**
	 * Tests that a batch resume reaches the listener keyed by the batch key
	 * suffix, and that a resume scheduled for a time already past when it is
	 * sent (a rescue of an overdue batch, or a follow-up scheduled before a slow
	 * commit) is not dropped because of a processing that started before it was
	 * sent: the JMS template helper sends a due message unscheduled, so it is
	 * posted when it is sent.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A batch resume scheduled in the past is not dropped by a processing that started before it was sent")
	public void testOverdueResumeIsNotDroppedByEarlierProcessing() throws Exception {
		final String keySuffix = "staleFilterResume-" + LibraryQueueStaleFilterIntegrationTest.RANDOM.nextInt();

		// A first resume (for a batch that does not exist, so it does no work) records a processing for the key.
		final long firstSentAt = StaleMessageFilterService.getCurrentTimestamp();
		this.batchServiceComponent.queueResumeAsync(keySuffix, null);
		Assertions.assertTrue(this.waitForProcessing(LibraryQueueStaleFilterIntegrationTest.RESUME_QUEUE, keySuffix, firstSentAt));

		// A second resume is scheduled a minute in the past, before that processing started. It is sent
		// after it, so it must be processed (and record a newer processing), not dropped.
		final Double checkedCountBefore = this.getCheckedCount(LibraryQueueStaleFilterIntegrationTest.RESUME_QUEUE);
		final long secondSentAt = StaleMessageFilterService.getCurrentTimestamp();
		this.batchServiceComponent.queueResumeAsync(keySuffix, DateTimeHelper.getCurrentLocalDateTime().minusMinutes(1));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.getCheckedCount(LibraryQueueStaleFilterIntegrationTest.RESUME_QUEUE),
				checkedCount -> checkedCount > checkedCountBefore, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertTrue(this.waitForProcessing(LibraryQueueStaleFilterIntegrationTest.RESUME_QUEUE, keySuffix, secondSentAt));
	}

	/**
	 * Tests that conditional and forced batch deletes reach the listener under
	 * different stale filter keys.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Conditional and forced batch deletes are filtered under different keys")
	public void testBatchDeleteKeys() throws Exception {
		final String key = "staleFilterDelete-" + LibraryQueueStaleFilterIntegrationTest.RANDOM.nextInt();
		final long sentAt = StaleMessageFilterService.getCurrentTimestamp();
		this.batchServiceComponent.queueDeleteAsync(key, true);
		Assertions.assertTrue(this.waitForProcessing(LibraryQueueStaleFilterIntegrationTest.DELETE_QUEUE, key, sentAt));
		this.batchServiceComponent.queueDeleteAsync(key, false);
		Assertions.assertTrue(this.waitForProcessing(LibraryQueueStaleFilterIntegrationTest.DELETE_QUEUE,
				key + BatchServiceComponent.FORCED_DELETE_STALE_FILTER_KEY_SUFFIX, sentAt));
	}

	/**
	 * Tests that the delete-expired trigger reaches the listener with its
	 * constant stale filter key (the queue name).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The statistics delete-expired trigger is checked by the filter")
	public void testDeleteExpiredTriggerIsKeyed() throws Exception {
		final long sentAt = StaleMessageFilterService.getCurrentTimestamp();
		this.statisticsEventServiceComponent.scheduleDeleteExpired();
		Assertions.assertTrue(this.waitForProcessing(LibraryQueueStaleFilterIntegrationTest.DELETE_EXPIRED_QUEUE,
				LibraryQueueStaleFilterIntegrationTest.DELETE_EXPIRED_QUEUE, sentAt));
	}

}
