package org.coldis.library.service.jms;

import java.util.Optional;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jakarta.jms.ConnectionFactory;

/**
 * Depth of any queue on the Artemis broker (a backpressure signal for batches that feed a queue),
 * through an {@link ArtemisQueueDepthReader} on the application's connection factory: cached for
 * {@link #CACHE_TTL_MILLIS}, never throws, empty when the depth cannot be read (no Artemis
 * connection factory, no session, or a failed query). Always registered: with no Artemis
 * connection factory every read is empty (one WARN on the first read).
 */
@Component
public class QueueDepthComponent {

	/** How long a depth is reused: shorter than any sensible step delay, so each step reads the broker. */
	public static final long CACHE_TTL_MILLIS = 100L;

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(QueueDepthComponent.class);

	/** Connection factory. */
	@Autowired
	private ObjectProvider<ConnectionFactory> connectionFactory;

	/** Reader, created on the first read. */
	private volatile ArtemisQueueDepthReader reader;

	/**
	 * The reader, created on the first call from the application's connection factory (unwrapped
	 * from its pool or cache); one with no factory, whose every read is empty, when that is not
	 * an Artemis one.
	 *
	 * @return The reader.
	 */
	private ArtemisQueueDepthReader getReader() {
		if (this.reader == null) {
			synchronized (this) {
				if (this.reader == null) {
					final ConnectionFactory factory = this.connectionFactory.getIfAvailable();
					final ConnectionFactory nativeFactory = factory == null ? null : ConnectionFactoryUnwrapper.unwrap(factory);
					ActiveMQConnectionFactory artemisFactory = null;
					if (nativeFactory instanceof final ActiveMQConnectionFactory activeMqFactory) {
						artemisFactory = activeMqFactory;
					}
					else {
						QueueDepthComponent.LOGGER.warn("Queue depth — no Artemis connection factory ({}): queue depths cannot be read",
								nativeFactory == null ? null : nativeFactory.getClass().getName());
					}
					this.reader = new ArtemisQueueDepthReader(artemisFactory, QueueDepthComponent.CACHE_TTL_MILLIS);
				}
			}
		}
		return this.reader;
	}

	/**
	 * Test seam (package-private, used only by tests in this package): replaces the reader, closing
	 * the previous one; null goes back to the lazily created one on the application's connection
	 * factory.
	 *
	 * @param newReader The reader to use from now on, or null.
	 */
	void replaceReader(
			final ArtemisQueueDepthReader newReader) {
		synchronized (this) {
			if (this.reader != null) {
				this.reader.close();
			}
			this.reader = newReader;
		}
	}

	/**
	 * Releases the reader's broker session on shutdown.
	 */
	@PreDestroy
	public void close() {
		synchronized (this) {
			if (this.reader != null) {
				this.reader.close();
			}
		}
	}

	/**
	 * A queue's depth (its message count; {@code 0} for a queue the broker does not know yet).
	 *
	 * @param  queueName Queue name.
	 * @return           The depth, or empty when it cannot be read.
	 */
	public Optional<Long> findDepth(
			final String queueName) {
		return this.getReader().findDepth(queueName);
	}

}
