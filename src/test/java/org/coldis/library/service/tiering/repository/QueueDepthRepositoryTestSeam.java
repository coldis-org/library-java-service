package org.coldis.library.service.tiering.repository;

import org.coldis.library.service.jms.ArtemisQueueDepthReader;

/**
 * Test-only access to {@link QueueDepthRepository}'s package-private reader seam.
 */
public final class QueueDepthRepositoryTestSeam {

	/**
	 * No instances.
	 */
	private QueueDepthRepositoryTestSeam() {
		super();
	}

	/**
	 * Makes every depth unreadable (a reader with no connection factory), closing the current reader.
	 *
	 * @param repository Repository.
	 */
	public static void makeUnreadable(
			final QueueDepthRepository repository) {
		repository.replaceReader(new ArtemisQueueDepthReader(null, 0L));
	}

	/**
	 * Goes back to the reader on the application's connection factory, closing the current one.
	 *
	 * @param repository Repository.
	 */
	public static void restore(
			final QueueDepthRepository repository) {
		repository.replaceReader(null);
	}

}
