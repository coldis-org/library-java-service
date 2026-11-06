package org.coldis.library.test.service.jms;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Listeners of {@link EnhancedMessageConverterTest}. A bean (rather than
 * listener methods on the test class) so they are registered once in the
 * shared context, not once per test instance.
 */
@Component
public class EnhancedMessageConverterTestListener {

	/** Loop topic. */
	public static final String LOOP_TOPIC = "message/loop";

	/** Last received test message. */
	private static final AtomicReference<Object> CURRENT_TEST_MESSAGE = new AtomicReference<>();

	/** Async hops counted for the loop message. */
	private static final AtomicLong ASYNC_HOPS = new AtomicLong();

	/** Identifier of the loop message to count. */
	private static final AtomicLong ASYNC_HOPS_MESSAGE_IDENTIFIER = new AtomicLong();

	/** JMS topic template. */
	private final JmsTemplate jmsTopicTemplate;

	/**
	 * Constructor.
	 *
	 * @param jmsTopicTemplate JMS topic template.
	 */
	public EnhancedMessageConverterTestListener(@Qualifier("jmsTopicTemplate") final JmsTemplate jmsTopicTemplate) {
		this.jmsTopicTemplate = jmsTopicTemplate;
	}

	/**
	 * Clears the last received test message.
	 */
	public static void resetCurrentTestMessage() {
		EnhancedMessageConverterTestListener.CURRENT_TEST_MESSAGE.set(null);
	}

	/**
	 * Gets the last received test message.
	 *
	 * @return The last received test message.
	 */
	public static Object getCurrentTestMessage() {
		return EnhancedMessageConverterTestListener.CURRENT_TEST_MESSAGE.get();
	}

	/**
	 * Starts counting the hops of a new loop message.
	 *
	 * @param messageIdentifier Loop message identifier.
	 */
	public static void resetAsyncHops(
			final Long messageIdentifier) {
		EnhancedMessageConverterTestListener.ASYNC_HOPS.set(0L);
		EnhancedMessageConverterTestListener.ASYNC_HOPS_MESSAGE_IDENTIFIER.set(messageIdentifier);
	}

	/**
	 * Gets the hops counted for the loop message.
	 *
	 * @return The counted hops.
	 */
	public static Long getAsyncHops() {
		return EnhancedMessageConverterTestListener.ASYNC_HOPS.get();
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(
			destination = "message/original",
			concurrency = "100"
	)
	public void consumeOriginalMessage(
			final DtoTestObject message) {
		EnhancedMessageConverterTestListener.CURRENT_TEST_MESSAGE.set(message);
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(
			destination = "message/dto",
			concurrency = "100"
	)
	public void consumeDtoMessage(
			final DtoTestObjectDto message) {
		EnhancedMessageConverterTestListener.CURRENT_TEST_MESSAGE.set(message);
	}

	/**
	 * Consumes a loop message and sends it again (one more async hop).
	 *
	 * @param message Message.
	 */
	@JmsListener(
			containerFactory = "jmsListenerTopicContainerFactory",
			destination = EnhancedMessageConverterTestListener.LOOP_TOPIC,
			subscription = "message/loop/test",
			concurrency = "100"
	)
	public void consumeMessageLoop(
			final DtoTestObject message) {
		if (Objects.equals(EnhancedMessageConverterTestListener.ASYNC_HOPS_MESSAGE_IDENTIFIER.get(), message.getId())) {
			EnhancedMessageConverterTestListener.ASYNC_HOPS.incrementAndGet();
		}
		this.jmsTopicTemplate.convertAndSend(EnhancedMessageConverterTestListener.LOOP_TOPIC, message);
	}

}
