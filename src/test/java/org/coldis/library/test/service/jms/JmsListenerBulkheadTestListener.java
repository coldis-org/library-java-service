package org.coldis.library.test.service.jms;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Listeners of {@link JmsListenerBulkheadIntegrationTest}: two queues on the
 * container factory of the {@code bulkhead-test} group (limit 2). The handlers
 * are transactional and block until the test releases them.
 */
@Component
public class JmsListenerBulkheadTestListener {

	/** First queue. */
	public static final String FIRST_QUEUE = "bulkhead/integration/first";

	/** Second queue. */
	public static final String SECOND_QUEUE = "bulkhead/integration/second";

	/** First listener id. */
	public static final String FIRST_LISTENER = "bulkheadIntegrationFirst";

	/** Second listener id. */
	public static final String SECOND_LISTENER = "bulkheadIntegrationSecond";

	/** Container factory. */
	public static final String CONTAINER_FACTORY = "jmsListenerBulkheadTestContainerFactory";

	/** Handlers running and the most seen at once. */
	private static volatile JmsListenerBulkheadTestSupport.InFlightProbe probe = new JmsListenerBulkheadTestSupport.InFlightProbe();

	/** Latch the handlers wait for. */
	private static volatile CountDownLatch release = new CountDownLatch(0);

	/** Processed messages. */
	private static final AtomicInteger PROCESSED = new AtomicInteger();

	/** If a transaction was active in each handler execution. */
	private static final List<Boolean> TRANSACTION_ACTIVE = new CopyOnWriteArrayList<>();

	/**
	 * Resets the state and makes the handlers wait until {@link #release()}.
	 */
	public static void reset() {
		JmsListenerBulkheadTestListener.release.countDown();
		JmsListenerBulkheadTestListener.release = new CountDownLatch(1);
		JmsListenerBulkheadTestListener.probe = new JmsListenerBulkheadTestSupport.InFlightProbe();
		JmsListenerBulkheadTestListener.PROCESSED.set(0);
		JmsListenerBulkheadTestListener.TRANSACTION_ACTIVE.clear();
	}

	/**
	 * Lets the handlers finish.
	 */
	public static void release() {
		JmsListenerBulkheadTestListener.release.countDown();
	}

	/**
	 * Gets the in-flight probe.
	 *
	 * @return The in-flight probe.
	 */
	public static JmsListenerBulkheadTestSupport.InFlightProbe getProbe() {
		return JmsListenerBulkheadTestListener.probe;
	}

	/**
	 * Gets the processed messages.
	 *
	 * @return The processed messages.
	 */
	public static int getProcessed() {
		return JmsListenerBulkheadTestListener.PROCESSED.get();
	}

	/**
	 * Gets if a transaction was active in each handler execution.
	 *
	 * @return If a transaction was active in each handler execution.
	 */
	public static List<Boolean> getTransactionActive() {
		return List.copyOf(JmsListenerBulkheadTestListener.TRANSACTION_ACTIVE);
	}

	/**
	 * Handles a message: records the transaction state and waits for the test.
	 */
	private void handle() {
		final JmsListenerBulkheadTestSupport.InFlightProbe actualProbe = JmsListenerBulkheadTestListener.getProbe();
		actualProbe.enter();
		try {
			JmsListenerBulkheadTestListener.TRANSACTION_ACTIVE.add(TransactionSynchronizationManager.isActualTransactionActive());
			JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(JmsListenerBulkheadTestListener.release);
			JmsListenerBulkheadTestListener.PROCESSED.incrementAndGet();
		}
		finally {
			actualProbe.exit();
		}
	}

	/**
	 * Consumes a message of the first queue.
	 *
	 * @param message Message.
	 */
	@Transactional
	@JmsListener(
			id = JmsListenerBulkheadTestListener.FIRST_LISTENER,
			destination = JmsListenerBulkheadTestListener.FIRST_QUEUE,
			containerFactory = JmsListenerBulkheadTestListener.CONTAINER_FACTORY,
			concurrency = "1-5"
	)
	public void consumeFirst(
			final String message) {
		this.handle();
	}

	/**
	 * Consumes a message of the second queue.
	 *
	 * @param message Message.
	 */
	@Transactional
	@JmsListener(
			id = JmsListenerBulkheadTestListener.SECOND_LISTENER,
			destination = JmsListenerBulkheadTestListener.SECOND_QUEUE,
			containerFactory = JmsListenerBulkheadTestListener.CONTAINER_FACTORY,
			concurrency = "1-5"
	)
	public void consumeSecond(
			final String message) {
		this.handle();
	}

}
