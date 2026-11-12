package org.coldis.library.service.jms;

/**
 * Test-only access to {@link QueueDepthComponent}'s package-private reader seam.
 */
public final class QueueDepthComponentTestSeam {

	/**
	 * No instances.
	 */
	private QueueDepthComponentTestSeam() {
		super();
	}

	/**
	 * Makes every depth unreadable (a reader with no connection factory), closing the current reader.
	 *
	 * @param component Component.
	 */
	public static void makeUnreadable(
			final QueueDepthComponent component) {
		component.replaceReader(new ArtemisQueueDepthReader(null, 0L));
	}

	/**
	 * Goes back to the reader on the application's connection factory, closing the current one.
	 *
	 * @param component Component.
	 */
	public static void restore(
			final QueueDepthComponent component) {
		component.replaceReader(null);
	}

}
