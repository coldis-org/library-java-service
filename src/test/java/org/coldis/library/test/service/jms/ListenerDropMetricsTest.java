package org.coldis.library.test.service.jms;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
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
 * Metrics of messages dropped by the listener adapter. Listener in
 * {@link DroppedMessageTestListener}.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Listener adapter — dropped message metrics")
public class ListenerDropMetricsTest extends ContainerTestHelper {

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** Meter registry. */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Gets the dropped messages count of the test queue.
	 *
	 * @return The dropped messages count.
	 */
	private Double getDroppedCount() {
		final Counter droppedCounter = this.meterRegistry.find(ExtendedMessagingMessageListenerAdapter.DROPPED_MESSAGES_METRIC)
				.tag("destination", DroppedMessageTestListener.TEST_QUEUE).tag("exception", BusinessException.class.getName()).counter();
		return (droppedCounter == null ? 0D : droppedCounter.count());
	}

	/**
	 * Tests that a message failing with a business error without a retry-in value
	 * is dropped once (not retried) and counted.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A business error without retry-in drops the message once and counts it by destination and exception")
	public void testDroppedMessageIsCounted() throws Exception {
		final Double droppedCountBefore = this.getDroppedCount();
		final Long attemptsBefore = DroppedMessageTestListener.getAttempts();
		this.jmsTemplate.convertAndSend(DroppedMessageTestListener.TEST_QUEUE, "dropped");
		Assertions.assertTrue(TestHelper.waitUntilValid(this::getDroppedCount, droppedCount -> droppedCount > droppedCountBefore, TestHelper.LONG_WAIT,
				TestHelper.SHORT_WAIT));
		Assertions.assertFalse(TestHelper.waitUntilValid(DroppedMessageTestListener::getAttempts, attempts -> attempts > (attemptsBefore + 1),
				TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(droppedCountBefore + 1, this.getDroppedCount());
		Assertions.assertEquals(attemptsBefore + 1, DroppedMessageTestListener.getAttempts());
	}

}
