package org.coldis.library.test.service.jms;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Listeners of {@link AsyncHopsExceededRetryTest}. A bean (rather than listener
 * methods on the test class) so the listeners are registered once in the shared
 * context, not once per test instance.
 */
@Component
public class AsyncHopsExceededRetryTestListener {

	/** Original queue. */
	public static final String ORIGINAL_QUEUE = "async-hops/retry/queue";

	/** Original topic. */
	public static final String ORIGINAL_TOPIC = "async-hops/retry/topic";

	/** Original topic consumed by a non-durable, non-shared subscription. */
	public static final String NON_DURABLE_TOPIC = "async-hops/retry/topic/non-durable";

	/** Queue listener name. */
	public static final String QUEUE_LISTENER = "queue";

	/** First subscription listener name. */
	public static final String FIRST_SUBSCRIPTION_LISTENER = "first";

	/** Second subscription listener name. */
	public static final String SECOND_SUBSCRIPTION_LISTENER = "second";

	/** Identifier of the message the listeners should count. */
	private static final AtomicLong EXPECTED_IDENTIFIER = new AtomicLong();

	/** How many times each listener processed the expected message. */
	private static final Map<String, AtomicLong> PROCESSED_COUNTS = new ConcurrentHashMap<>();

	/**
	 * Starts counting a new expected message.
	 *
	 * @param expectedIdentifier Identifier of the message to count.
	 */
	public static void reset(
			final Long expectedIdentifier) {
		AsyncHopsExceededRetryTestListener.PROCESSED_COUNTS.clear();
		AsyncHopsExceededRetryTestListener.EXPECTED_IDENTIFIER.set(expectedIdentifier);
	}

	/**
	 * Gets how many times a listener processed the expected message.
	 *
	 * @param  listenerName Listener name.
	 * @return              Processed count.
	 */
	public static Long getProcessedCount(
			final String listenerName) {
		return AsyncHopsExceededRetryTestListener.PROCESSED_COUNTS.getOrDefault(listenerName, new AtomicLong()).get();
	}

	/**
	 * Gets how many times all listeners processed the expected message.
	 *
	 * @return Processed count.
	 */
	public static Long getTotalProcessedCount() {
		return AsyncHopsExceededRetryTestListener.PROCESSED_COUNTS.values().stream().mapToLong(AtomicLong::get).sum();
	}

	/**
	 * Counts a processed message.
	 *
	 * @param listenerName Listener name.
	 * @param message      Message.
	 */
	private void countProcessed(
			final String listenerName,
			final DtoTestObject message) {
		if (AsyncHopsExceededRetryTestListener.EXPECTED_IDENTIFIER.get() == message.getId()) {
			AsyncHopsExceededRetryTestListener.PROCESSED_COUNTS.computeIfAbsent(listenerName, name -> new AtomicLong()).incrementAndGet();
		}
	}

	/**
	 * Consumes the queue test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(destination = AsyncHopsExceededRetryTestListener.ORIGINAL_QUEUE)
	public void consumeQueue(
			final DtoTestObject message) {
		this.countProcessed(AsyncHopsExceededRetryTestListener.QUEUE_LISTENER, message);
	}

	/**
	 * Consumes the topic test message (first subscription).
	 *
	 * @param message Message.
	 */
	@JmsListener(
			containerFactory = "jmsListenerTopicContainerFactory",
			destination = AsyncHopsExceededRetryTestListener.ORIGINAL_TOPIC,
			subscription = "async-hops/retry/topic/first"
	)
	public void consumeTopicFirstSubscription(
			final DtoTestObject message) {
		this.countProcessed(AsyncHopsExceededRetryTestListener.FIRST_SUBSCRIPTION_LISTENER, message);
	}

	/**
	 * Consumes the topic test message (second subscription).
	 *
	 * @param message Message.
	 */
	@JmsListener(
			containerFactory = "jmsListenerTopicContainerFactory",
			destination = AsyncHopsExceededRetryTestListener.ORIGINAL_TOPIC,
			subscription = "async-hops/retry/topic/second"
	)
	public void consumeTopicSecondSubscription(
			final DtoTestObject message) {
		this.countProcessed(AsyncHopsExceededRetryTestListener.SECOND_SUBSCRIPTION_LISTENER, message);
	}

	/**
	 * Consumes the non-durable topic test message (only exceeded messages are sent
	 * to it, so nothing is processed).
	 *
	 * @param message Message.
	 */
	@JmsListener(
			containerFactory = "jmsListenerNonDurableTopicContainerFactory",
			destination = AsyncHopsExceededRetryTestListener.NON_DURABLE_TOPIC
	)
	public void consumeNonDurableTopic(
			final DtoTestObject message) {
		this.countProcessed("non-durable", message);
	}

}
