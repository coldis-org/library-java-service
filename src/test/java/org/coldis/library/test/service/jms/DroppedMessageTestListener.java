package org.coldis.library.test.service.jms;

import java.util.concurrent.atomic.AtomicLong;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Listener of {@link ListenerDropMetricsTest}: always fails with a business
 * error without a retry-in value, so the message is dropped.
 */
@Component
public class DroppedMessageTestListener {

	/** Test queue. */
	public static final String TEST_QUEUE = "listener/dropped/test";

	/** Delivery attempts. */
	private static final AtomicLong ATTEMPTS = new AtomicLong();

	/**
	 * Gets the delivery attempts.
	 *
	 * @return The delivery attempts.
	 */
	public static Long getAttempts() {
		return DroppedMessageTestListener.ATTEMPTS.get();
	}

	/**
	 * Consumes a test message, failing with a non-retriable business error.
	 *
	 * @param  message           Message.
	 * @throws BusinessException Always.
	 */
	@JmsListener(destination = DroppedMessageTestListener.TEST_QUEUE)
	public void consumeMessage(
			final String message) throws BusinessException {
		DroppedMessageTestListener.ATTEMPTS.incrementAndGet();
		throw new BusinessException(new SimpleMessage("listener.dropped.test"));
	}

}
