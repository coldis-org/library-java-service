package org.coldis.library.test.service.jms;

import java.util.Random;

import javax.sql.DataSource;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.jms.StaleMessageFilterProperties;
import org.coldis.library.service.jms.StaleMessageFilterService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.core.JmsTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stale message filter integration test (through the JMS listener adapter).
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Stale message filter — through the listener adapter")
public class StaleMessageFilterIntegrationTest extends ContainerTestHelper {

	/**
	 * Random.
	 */
	private static final Random RANDOM = new Random();

	/**
	 * JMS template.
	 */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * JMS template helper.
	 */
	@Autowired
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Stale message filter properties.
	 */
	@Autowired
	private StaleMessageFilterProperties staleMessageFilterProperties;

	/**
	 * Meter registry.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Stale message filter service.
	 */
	@Autowired
	private StaleMessageFilterService staleMessageFilterService;

	/**
	 * Data source provider.
	 */
	@Autowired
	private ObjectProvider<DataSource> dataSourceProvider;

	/**
	 * Gets a stale filter count for the test queue (summed over the other tags).
	 *
	 * @param  metricName Metric name.
	 * @param  tags       Extra tags to match (key, value pairs).
	 * @return            The count.
	 */
	private Double getCount(
			final String metricName,
			final String... tags) {
		return this.meterRegistry.find(metricName).tag("destination", StaleMessageFilterTestListener.TEST_QUEUE).tags(tags).counters().stream()
				.mapToDouble(Counter::count).sum();
	}

	/**
	 * Gets the stale filter drop count for the test queue.
	 *
	 * @return The drop count.
	 */
	private Double getDroppedCount() {
		return this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC);
	}

	/**
	 * Enables the filter (memory-only) before each test.
	 */
	@BeforeEach
	public void beforeEach() {
		StaleMessageFilterTestListener.reset();
		this.staleMessageFilterProperties.setEnabled(true);
		this.staleMessageFilterProperties.setPersistenceEnabled(false);
	}

	/**
	 * Restores the filter configuration after each test.
	 */
	@AfterEach
	public void afterEach() {
		this.staleMessageFilterProperties.setEnabled(false);
		this.staleMessageFilterProperties.setPersistenceEnabled(true);
	}

	/**
	 * Tests that messages posted before a same-key processing are dropped while
	 * fresh messages keep being processed.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A message posted before a same-key processing is dropped and counted; a later one is processed")
	public void testStaleMessagesAreDropped() throws Exception {
		final Double droppedCountBefore = this.getDroppedCount();
		final Double localDroppedCountBefore = this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC, "source", "local");
		final Double checkedDroppedCountBefore = this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC, "outcome",
				"dropped");
		final Double checkedProcessedCountBefore = this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC, "outcome",
				"processed");
		final Integer random = StaleMessageFilterIntegrationTest.RANDOM.nextInt();
		final String messageKey = "staleFilterKey-" + random;

		// Message 1 is processed normally (and records the processing for the key).
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<String>().withDestination(StaleMessageFilterTestListener.TEST_QUEUE)
				.withMessage("message1-" + random).withStaleFilterKey(messageKey));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterTestListener.getProcessedMessages(),
				processedMessages -> processedMessages.contains("message1-" + random), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

		// Message 2 carries the same key but was posted (per the override property)
		// before message 1 was processed: it should be dropped.
		this.jmsTemplate.convertAndSend(StaleMessageFilterTestListener.TEST_QUEUE, "message2-" + random, jmsMessage -> {
			jmsMessage.setStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY, messageKey);
			jmsMessage.setLongProperty(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY,
					DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) - 60_000);
			return jmsMessage;
		});

		// Message 3 carries the same key and was posted after message 1 was
		// processed: it should be processed.
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<String>().withDestination(StaleMessageFilterTestListener.TEST_QUEUE)
				.withMessage("message3-" + random).withStaleFilterKey(messageKey));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterTestListener.getProcessedMessages(),
				processedMessages -> processedMessages.contains("message3-" + random), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

		// Message 2 should have been dropped (never processed), and counted.
		Assertions.assertTrue(TestHelper.waitUntilValid(this::getDroppedCount, droppedCount -> droppedCount > droppedCountBefore, TestHelper.LONG_WAIT,
				TestHelper.SHORT_WAIT));
		Assertions.assertEquals(droppedCountBefore + 1, this.getDroppedCount());
		Assertions.assertFalse(StaleMessageFilterTestListener.getProcessedMessages().contains("message2-" + random));

		// The drop came from this instance's own record, and every keyed message was
		// checked: two processed, one dropped.
		Assertions.assertEquals(localDroppedCountBefore + 1,
				this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC, "source", "local"));
		Assertions.assertEquals(checkedDroppedCountBefore + 1,
				this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC, "outcome", "dropped"));
		Assertions.assertEquals(checkedProcessedCountBefore + 2,
				this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC, "outcome", "processed"));
	}

	/**
	 * Tests that a drop caused by a record another instance shared is counted
	 * with the pulled source.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A drop caused by another instance's record is counted as pulled")
	public void testPulledDropSource() throws Exception {
		this.staleMessageFilterProperties.setPersistenceEnabled(true);
		final Double pulledDroppedCountBefore = this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC, "source", "pulled");
		final Integer random = StaleMessageFilterIntegrationTest.RANDOM.nextInt();
		final String messageKey = "pulledStaleFilterKey-" + random;

		// Message 1 is processed here (this instance learns the queue is keyed).
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<String>().withDestination(StaleMessageFilterTestListener.TEST_QUEUE)
				.withMessage("pulledMessage1-" + random).withStaleFilterKey(messageKey));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterTestListener.getProcessedMessages(),
				processedMessages -> processedMessages.contains("pulledMessage1-" + random), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

		// Another instance processes the same key later and shares it; this
		// instance pulls it.
		final StaleMessageFilterService otherInstance = new StaleMessageFilterService(this.staleMessageFilterProperties, this.dataSourceProvider);
		otherInstance.recordProcessing(StaleMessageFilterTestListener.TEST_QUEUE, messageKey,
				DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) + 1_000);
		otherInstance.synchronize();
		this.staleMessageFilterService.synchronize();

		// Message 2, posted before that processing, is dropped because of the pulled
		// record.
		this.jmsTemplate.convertAndSend(StaleMessageFilterTestListener.TEST_QUEUE, "pulledMessage2-" + random, jmsMessage -> {
			jmsMessage.setStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY, messageKey);
			jmsMessage.setLongProperty(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY,
					DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) - 60_000);
			return jmsMessage;
		});
		Assertions.assertTrue(TestHelper.waitUntilValid(
				() -> this.getCount(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC, "source", "pulled"),
				droppedCount -> droppedCount > pulledDroppedCountBefore, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertFalse(StaleMessageFilterTestListener.getProcessedMessages().contains("pulledMessage2-" + random));
	}

	/**
	 * Tests that messages without a stale filter key are never dropped.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Messages without a stale filter key are never dropped")
	public void testMessagesWithoutKeyAreNotFiltered() throws Exception {
		final Integer random = StaleMessageFilterIntegrationTest.RANDOM.nextInt();

		// Both messages should be processed (no key, no filtering).
		this.jmsTemplateHelper.send(this.jmsTemplate,
				new JmsMessage<String>().withDestination(StaleMessageFilterTestListener.TEST_QUEUE).withMessage("plainMessage1-" + random));
		this.jmsTemplateHelper.send(this.jmsTemplate,
				new JmsMessage<String>().withDestination(StaleMessageFilterTestListener.TEST_QUEUE).withMessage("plainMessage2-" + random));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterTestListener.getProcessedMessages(),
				processedMessages -> processedMessages.contains("plainMessage1-" + random) && processedMessages.contains("plainMessage2-" + random),
				TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
	}

}
