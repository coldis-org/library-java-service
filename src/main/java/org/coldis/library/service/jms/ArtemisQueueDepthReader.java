package org.coldis.library.service.jms;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an Artemis queue's depth (its message count, delivering and scheduled messages included)
 * with a core {@code queueQuery} on a dedicated client session, cached per queue for a short time
 * to bound broker round-trips. Never throws: a depth that cannot be read is empty (logged at
 * {@code WARN}) and the broken session is closed, to be recreated on the next read. A queue the
 * broker does not know has depth {@code 0}.
 */
public class ArtemisQueueDepthReader {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ArtemisQueueDepthReader.class);

	/** Connection factory whose server locator opens the query session (may be null: nothing is readable). */
	private final ActiveMQConnectionFactory connectionFactory;

	/** How long a read depth is reused. */
	private final long cacheTtlMillis;

	/** Queue name to {depth, timestampMillis}. */
	private final ConcurrentHashMap<String, long[]> depthCache = new ConcurrentHashMap<>();

	/** Query session lock. */
	private final Object querySessionLock = new Object();

	/** Query session. */
	private volatile ClientSession querySession;

	/** Query session factory. */
	private volatile ClientSessionFactory querySessionFactory;

	/**
	 * Constructor.
	 *
	 * @param connectionFactory Connection factory (null: every read is empty).
	 * @param cacheTtlMillis    How long a read depth is reused.
	 */
	public ArtemisQueueDepthReader(final ActiveMQConnectionFactory connectionFactory, final long cacheTtlMillis) {
		this.connectionFactory = connectionFactory;
		this.cacheTtlMillis = cacheTtlMillis;
	}

	/**
	 * Closes and forgets the query session and its session factory, so they are recreated on the
	 * next read (a broker restart would otherwise leave a broken session cached, and dropping it
	 * without closing leaks the factory's connection). Close failures are logged: the resources
	 * are being discarded.
	 *
	 * @param failedSession The session that failed (null when its creation failed).
	 */
	private void closeQuerySession(
			final ClientSession failedSession) {
		synchronized (this.querySessionLock) {
			// Only the session that failed: another thread may already have replaced it with a
			// healthy one (null when the session could not be created).
			if (this.querySession == failedSession) {
				if (this.querySession != null) {
					try {
						this.querySession.close();
					}
					catch (final Exception exception) {
						ArtemisQueueDepthReader.LOGGER.debug("Queue depth — could not close query session: {}", exception.getMessage());
					}
				}
				if (this.querySessionFactory != null) {
					this.querySessionFactory.close();
				}
				this.querySession = null;
				this.querySessionFactory = null;
			}
		}
	}

	/**
	 * Lazily creates (and caches) the client session used to query queue depth.
	 *
	 * @return The session, or null when it cannot be created.
	 */
	private ClientSession getQuerySession() {
		if (this.querySession == null) {
			synchronized (this.querySessionLock) {
				if (this.querySession == null) {
					try {
						this.querySessionFactory = this.connectionFactory.getServerLocator().createSessionFactory();
						this.querySession = this.querySessionFactory.createSession(this.connectionFactory.getUser(), this.connectionFactory.getPassword(), false, true, true,
								false, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE);
					}
					catch (final Exception exception) {
						ArtemisQueueDepthReader.LOGGER.warn("Queue depth — could not create query session: {}", exception.getMessage());
						this.closeQuerySession(null);
					}
				}
			}
		}
		return this.querySession;
	}

	/**
	 * The queue's depth: its message count, cached for the TTL; {@code 0} for a queue the broker
	 * does not know.
	 *
	 * @param  queueName Queue name.
	 * @return           The depth, or empty when it cannot be read (no session, or the query
	 *                   failed).
	 */
	public Optional<Long> findDepth(
			final String queueName) {
		final long[] cachedDepth = this.depthCache.get(queueName);
		final long currentTimeMillis = System.currentTimeMillis();
		Optional<Long> depth = Optional.empty();
		if ((cachedDepth != null) && ((currentTimeMillis - cachedDepth[1]) < this.cacheTtlMillis)) {
			depth = Optional.of(cachedDepth[0]);
		}
		else {
			// Never throws (null when the session cannot be created).
			final ClientSession session = this.getQuerySession();
			try {
				if (session != null) {
					final ClientSession.QueueQuery queueQuery;
					synchronized (session) {
						queueQuery = session.queueQuery(SimpleString.of(queueName));
					}
					final long messageCount = queueQuery.getMessageCount();
					this.depthCache.put(queueName, new long[] { messageCount, currentTimeMillis });
					depth = Optional.of(messageCount);
					// A persistently-zero depth (queue not found / name mismatch) is silent
					// otherwise, so make the actual reading observable for diagnosis.
					ArtemisQueueDepthReader.LOGGER.debug("Queue depth — queue='{}' exists={} messageCount={}", queueName, queueQuery.isExists(), messageCount);
				}
			}
			catch (final Exception exception) {
				ArtemisQueueDepthReader.LOGGER.warn("Queue depth — could not query depth for queue '{}': {}", queueName, exception.getMessage());
				this.closeQuerySession(session);
			}
		}
		return depth;
	}

	/**
	 * The last depth read of a queue, cached or not.
	 *
	 * @param  queueName Queue name.
	 * @return           The depth, {@code 0} when never read.
	 */
	public long getLastDepth(
			final String queueName) {
		return this.depthCache.getOrDefault(queueName, new long[] { 0L, 0L })[0];
	}

}
