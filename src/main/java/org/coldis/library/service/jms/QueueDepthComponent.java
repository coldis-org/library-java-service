package org.coldis.library.service.jms;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jakarta.jms.ConnectionFactory;

/**
 * Depth of any queue on the Artemis broker (a backpressure signal for batches that feed a queue),
 * through one {@link ArtemisQueueDepthReader} per connection factory: cached for
 * {@link #CACHE_TTL_MILLIS}, never throws, empty when the depth cannot be read (no Artemis
 * connection factory, no session, or a failed query). Always registered.
 * <p>
 * A caller that sends to the queue reads its depth on the factory it sends with
 * ({@link #findDepth(ConnectionFactory, String)}, e.g. its {@code JmsTemplate}'s): applications
 * often hold several connection factories (one per service client) with none primary.
 * {@link #findDepth(String)} uses the application's unique or primary connection factory, and is
 * empty (one WARN) when there is none.
 */
@Component
public class QueueDepthComponent {

	/** How long a depth is reused: shorter than any sensible step delay, so each step reads the broker. */
	public static final long CACHE_TTL_MILLIS = 100L;

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(QueueDepthComponent.class);

	/** Connection factories. */
	private final ObjectProvider<ConnectionFactory> connectionFactories;

	/** Readers, by the connection factory they were asked for, created on its first read. */
	private final Map<ConnectionFactory, ArtemisQueueDepthReader> readers = new ConcurrentHashMap<>();

	/** Reader with no factory: every read is empty. */
	private final ArtemisQueueDepthReader unreadableReader = new ArtemisQueueDepthReader(null, 0L);

	/** Whether the missing unique or primary connection factory was already logged. */
	private final AtomicBoolean noDefaultFactoryLogged = new AtomicBoolean(false);

	/** Reader used for every read instead (test seam), or null. */
	private volatile ArtemisQueueDepthReader overrideReader;

	/**
	 * Constructor.
	 *
	 * @param connectionFactories Connection factories.
	 */
	public QueueDepthComponent(final ObjectProvider<ConnectionFactory> connectionFactories) {
		this.connectionFactories = connectionFactories;
	}

	/**
	 * Creates the reader of a connection factory (unwrapped from its pool or cache); one with no
	 * factory, whose every read is empty, when that is not an Artemis one.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The reader.
	 */
	private ArtemisQueueDepthReader createReader(
			final ConnectionFactory connectionFactory) {
		final ConnectionFactory nativeFactory = ConnectionFactoryUnwrapper.unwrap(connectionFactory);
		ActiveMQConnectionFactory artemisFactory = null;
		if (nativeFactory instanceof final ActiveMQConnectionFactory activeMqFactory) {
			artemisFactory = activeMqFactory;
		}
		else {
			QueueDepthComponent.LOGGER.warn("Queue depth — not an Artemis connection factory ({}): queue depths cannot be read",
					nativeFactory == null ? null : nativeFactory.getClass().getName());
		}
		return new ArtemisQueueDepthReader(artemisFactory, QueueDepthComponent.CACHE_TTL_MILLIS);
	}

	/**
	 * The reader of a connection factory: the override when set, the unreadable one with no
	 * factory, otherwise the factory's own (created on its first read).
	 *
	 * @param  connectionFactory Connection factory, or null.
	 * @return                   The reader.
	 */
	private ArtemisQueueDepthReader getReader(
			final ConnectionFactory connectionFactory) {
		final ArtemisQueueDepthReader override = this.overrideReader;
		ArtemisQueueDepthReader reader = this.unreadableReader;
		if (override != null) {
			reader = override;
		}
		else if (connectionFactory != null) {
			reader = this.readers.computeIfAbsent(connectionFactory, this::createReader);
		}
		return reader;
	}

	/**
	 * The application's unique or primary connection factory; null (one WARN) when it has none,
	 * or several with none primary.
	 *
	 * @return The connection factory, or null.
	 */
	private ConnectionFactory getDefaultConnectionFactory() {
		final ConnectionFactory connectionFactory = this.connectionFactories.getIfUnique();
		if ((connectionFactory == null) && this.noDefaultFactoryLogged.compareAndSet(false, true)) {
			QueueDepthComponent.LOGGER.warn(
					"Queue depth — no unique or primary connection factory: queue depths read without one are empty (pass the factory that sends to the queue)");
		}
		return connectionFactory;
	}

	/**
	 * Test seam (package-private, used only by tests in this package): every read goes to this
	 * reader instead, closing the previous one; null goes back to the connection factories'
	 * readers.
	 *
	 * @param newReader The reader to use from now on, or null.
	 */
	void replaceReader(
			final ArtemisQueueDepthReader newReader) {
		synchronized (this) {
			if (this.overrideReader != null) {
				this.overrideReader.close();
			}
			this.overrideReader = newReader;
		}
	}

	/**
	 * Releases the readers' broker sessions on shutdown.
	 */
	@PreDestroy
	public void close() {
		synchronized (this) {
			this.readers.values().forEach(ArtemisQueueDepthReader::close);
			this.readers.clear();
			if (this.overrideReader != null) {
				this.overrideReader.close();
			}
		}
	}

	/**
	 * A queue's depth on a connection factory's broker (its message count; {@code 0} for a queue
	 * the broker does not know yet).
	 *
	 * @param  connectionFactory Connection factory (the one that sends to the queue), or null (only
	 *                               from {@link #findDepth(String)}, which logs its absence once).
	 * @param  queueName         Queue name.
	 * @return                   The depth, or empty when it cannot be read (or no factory is given).
	 */
	public Optional<Long> findDepth(
			final ConnectionFactory connectionFactory,
			final String queueName) {
		return this.getReader(connectionFactory).findDepth(queueName);
	}

	/**
	 * A queue's depth on the application's unique or primary connection factory.
	 *
	 * @param  queueName Queue name.
	 * @return           The depth, or empty when it cannot be read (or there is no such factory).
	 * @see              #findDepth(ConnectionFactory, String)
	 */
	public Optional<Long> findDepth(
			final String queueName) {
		return this.findDepth(this.getDefaultConnectionFactory(), queueName);
	}

}
