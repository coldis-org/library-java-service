package org.coldis.library.test.service.jms;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Listener of {@link StaleMessageFilterIntegrationTest}. A bean (rather than a
 * listener method on the test class) so it is registered once in the shared
 * context, not once per test instance.
 */
@Component
public class StaleMessageFilterTestListener {

	/**
	 * Test queue.
	 */
	public static final String TEST_QUEUE = "stale/filter/test";

	/**
	 * Processed messages.
	 */
	private static final List<String> PROCESSED_MESSAGES = new CopyOnWriteArrayList<>();

	/**
	 * Clears the processed messages.
	 */
	public static void reset() {
		StaleMessageFilterTestListener.PROCESSED_MESSAGES.clear();
	}

	/**
	 * Gets the processed messages.
	 *
	 * @return The processed messages.
	 */
	public static List<String> getProcessedMessages() {
		return StaleMessageFilterTestListener.PROCESSED_MESSAGES;
	}

	/**
	 * Consumes a test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(destination = StaleMessageFilterTestListener.TEST_QUEUE)
	public void consumeMessage(
			final String message) {
		StaleMessageFilterTestListener.PROCESSED_MESSAGES.add(message);
	}

}
