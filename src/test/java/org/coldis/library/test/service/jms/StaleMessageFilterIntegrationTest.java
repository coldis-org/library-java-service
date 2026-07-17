package org.coldis.library.test.service.jms;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import org.coldis.library.helper.DateTimeHelper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;

/**
 * Stale message filter integration test (through the JMS listener adapter).
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class StaleMessageFilterIntegrationTest extends ContainerTestHelper {

	/**
	 * Test queue.
	 */
	private static final String TEST_QUEUE = "stale/filter/test";

	/**
	 * Random.
	 */
	private static final Random RANDOM = new Random();

	/**
	 * Processed messages.
	 */
	private static final List<String> PROCESSED_MESSAGES = new CopyOnWriteArrayList<>();

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
	 * Enables the filter (memory-only) before each test.
	 */
	@BeforeEach
	public void prepare() {
		StaleMessageFilterIntegrationTest.PROCESSED_MESSAGES.clear();
		this.staleMessageFilterProperties.setEnabled(true);
		this.staleMessageFilterProperties.setPersistenceEnabled(false);
	}

	/**
	 * Restores the filter configuration after each test.
	 */
	@AfterEach
	public void restore() {
		this.staleMessageFilterProperties.setEnabled(false);
		this.staleMessageFilterProperties.setPersistenceEnabled(true);
	}

	/**
	 * Consumes a test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(destination = StaleMessageFilterIntegrationTest.TEST_QUEUE)
	public void consumeMessage(
			final String message) {
		StaleMessageFilterIntegrationTest.PROCESSED_MESSAGES.add(message);
	}

	/**
	 * Tests that messages posted before a same-key processing are dropped while
	 * fresh messages keep being processed.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testStaleMessagesAreDropped() throws Exception {
		final Integer random = StaleMessageFilterIntegrationTest.RANDOM.nextInt();
		final String messageKey = "staleFilterKey-" + random;

		// Message 1 is processed normally (and records the processing for the key).
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<String>().withDestination(StaleMessageFilterIntegrationTest.TEST_QUEUE)
				.withMessage("message1-" + random).withStaleFilterKey(messageKey));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterIntegrationTest.PROCESSED_MESSAGES,
				processedMessages -> processedMessages.contains("message1-" + random), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

		// Message 2 carries the same key but was posted (per the override property)
		// before message 1 was processed: it should be dropped.
		this.jmsTemplate.convertAndSend(StaleMessageFilterIntegrationTest.TEST_QUEUE, "message2-" + random, jmsMessage -> {
			jmsMessage.setStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY, messageKey);
			jmsMessage.setLongProperty(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY,
					DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) - 60_000);
			return jmsMessage;
		});

		// Message 3 carries the same key and was posted after message 1 was
		// processed: it should be processed.
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<String>().withDestination(StaleMessageFilterIntegrationTest.TEST_QUEUE)
				.withMessage("message3-" + random).withStaleFilterKey(messageKey));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterIntegrationTest.PROCESSED_MESSAGES,
				processedMessages -> processedMessages.contains("message3-" + random), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

		// Message 2 should have been dropped (never processed).
		Thread.sleep(1000);
		Assertions.assertFalse(StaleMessageFilterIntegrationTest.PROCESSED_MESSAGES.contains("message2-" + random));
	}

	/**
	 * Tests that messages without a stale filter key are never dropped.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testMessagesWithoutKeyAreNotFiltered() throws Exception {
		final Integer random = StaleMessageFilterIntegrationTest.RANDOM.nextInt();

		// Both messages should be processed (no key, no filtering).
		this.jmsTemplateHelper.send(this.jmsTemplate,
				new JmsMessage<String>().withDestination(StaleMessageFilterIntegrationTest.TEST_QUEUE).withMessage("plainMessage1-" + random));
		this.jmsTemplateHelper.send(this.jmsTemplate,
				new JmsMessage<String>().withDestination(StaleMessageFilterIntegrationTest.TEST_QUEUE).withMessage("plainMessage2-" + random));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> StaleMessageFilterIntegrationTest.PROCESSED_MESSAGES,
				processedMessages -> processedMessages.contains("plainMessage1-" + random) && processedMessages.contains("plainMessage2-" + random),
				TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
	}

}
