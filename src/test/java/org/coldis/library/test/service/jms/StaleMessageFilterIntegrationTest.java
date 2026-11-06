package org.coldis.library.test.service.jms;

import java.util.Random;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.jms.StaleMessageFilterProperties;
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
import org.springframework.jms.core.JmsTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Stale message filter integration test (through the JMS listener adapter).
 */
@TestWithContainer(reuse = true)
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
	 * Gets the stale filter drop count for the test queue.
	 *
	 * @return The drop count.
	 */
	private Double getDroppedCount() {
		final Counter droppedCounter = this.meterRegistry.find(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC)
				.tag("destination", StaleMessageFilterTestListener.TEST_QUEUE).counter();
		return (droppedCounter == null ? 0D : droppedCounter.count());
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
