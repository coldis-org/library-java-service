package org.coldis.library.service.jms;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.Interceptor;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.core.protocol.core.Packet;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionConsumerCloseMessage;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionCreateConsumerMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side outgoing interceptor that dynamically scales consumer credit
 * requests based on current queue depth.
 *
 * <p>Works with any {@code consumerWindowSize}. With the typical positive
 * window (e.g. 64KB), {@code requested} represents accumulated message bytes
 * since the last credit replenishment, and scaling multiplies that batch.
 * With {@code consumerWindowSize = 0}, {@code requested} carries the actual
 * size of each individual message, giving strict 1-at-a-time fairness below
 * the threshold and per-message scaling above it.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>depth &le; {@code depthThreshold}: credits passed through unchanged
 *       (one message at a time, perfect fairness).</li>
 *   <li>depth &gt; {@code depthThreshold}: credits are multiplied proportionally
 *       — {@code grant = min(maxCredits, requested &times; (depth/threshold) &times; multiplier)}.
 *       This allows each consumer to prefetch more messages as the queue grows,
 *       improving throughput under batch load.</li>
 * </ul>
 *
 * <p>Queue depth is cached for {@code cacheTtlMillis} milliseconds to avoid
 * a broker round-trip on every credit packet.
 */
public class DynamicCreditClientInterceptor implements Interceptor {

	private static final Logger LOGGER = LoggerFactory.getLogger(DynamicCreditClientInterceptor.class);

	private static final byte CREATE_CONSUMER_TYPE = 40;
	private static final byte CONSUMER_CREDITS_TYPE = 70;
	private static final byte CLOSE_CONSUMER_TYPE = 74;

	private static final Field CREDITS_FIELD;

	static {
		try {
			CREDITS_FIELD = SessionConsumerFlowCreditMessage.class.getDeclaredField("credits");
			CREDITS_FIELD.setAccessible(true);
		}
		catch (final NoSuchFieldException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private final long depthThreshold;
	private final double multiplier;
	private final int maxCredits;
	private final long cacheTtlMillis;
	private final ActiveMQConnectionFactory factory;

	/** consumerID → queue name, populated when consumers are created. */
	private final ConcurrentHashMap<Long, String> consumerQueues = new ConcurrentHashMap<>();

	/** queueName → {depth, timestampMillis}, invalidated after cacheTtlMillis. */
	private final ConcurrentHashMap<String, long[]> depthCache = new ConcurrentHashMap<>();

	private volatile ClientSession querySession;
	private final Object querySessionLock = new Object();

	/** Total consumer-create packets seen by this interceptor. */
	private final AtomicLong consumersRegistered = new AtomicLong(0);

	/** Total flow-credit packets seen by this interceptor. */
	private final AtomicLong creditPacketsIntercepted = new AtomicLong(0);

	/** Flow-credit packets where the credit value was modified (scaled). */
	private final AtomicLong creditPacketsScaled = new AtomicLong(0);

	public DynamicCreditClientInterceptor(
			final ActiveMQConnectionFactory factory,
			final long depthThreshold,
			final double multiplier,
			final int maxCredits,
			final long cacheTtlMillis) {
		this.factory = factory;
		this.depthThreshold = depthThreshold;
		this.multiplier = multiplier;
		this.maxCredits = maxCredits;
		this.cacheTtlMillis = cacheTtlMillis;
	}

	@Override
	public boolean intercept(
			final Packet packet,
			final RemotingConnection connection) throws ActiveMQException {
		final byte type = packet.getType();
		if (type == CREATE_CONSUMER_TYPE) {
			final SessionCreateConsumerMessage msg = (SessionCreateConsumerMessage) packet;
			final SimpleString queueName = msg.getQueueName();
			if (queueName != null) {
				this.consumerQueues.put(msg.getID(), queueName.toString());
				this.consumersRegistered.incrementAndGet();
			}
		}
		else if (type == CONSUMER_CREDITS_TYPE) {
			this.handleFlowCredit((SessionConsumerFlowCreditMessage) packet);
		}
		else if (type == CLOSE_CONSUMER_TYPE) {
			this.consumerQueues.remove(((SessionConsumerCloseMessage) packet).getConsumerID());
		}
		return true;
	}

	private void handleFlowCredit(final SessionConsumerFlowCreditMessage msg) {
		this.creditPacketsIntercepted.incrementAndGet();
		final String queueName = this.consumerQueues.get(msg.getConsumerID());
		if (queueName == null) {
			return;
		}
		final long depth = this.getQueueDepth(queueName);
		final int requested = msg.getCredits();
		final int granted = this.computeGranted(depth, requested);
		if (granted != requested) {
			try {
				DynamicCreditClientInterceptor.CREDITS_FIELD.setInt(msg, granted);
				this.creditPacketsScaled.incrementAndGet();
				LOGGER.debug("DynamicCredit — queue={} depth={} requested={} granted={}", queueName, depth, requested, granted);
			}
			catch (final IllegalAccessException e) {
				LOGGER.warn("DynamicCredit — could not modify credits field, skipping", e);
			}
		}
	}

	/** Total consumer-create packets seen. */
	public long getConsumersRegistered() {
		return this.consumersRegistered.get();
	}

	/** Total flow-credit packets seen (including those not modified). */
	public long getCreditPacketsIntercepted() {
		return this.creditPacketsIntercepted.get();
	}

	/** Flow-credit packets whose credit value was actually modified by the scaling logic. */
	public long getCreditPacketsScaled() {
		return this.creditPacketsScaled.get();
	}

	/**
	 * Returns the credits to grant given current queue depth and the amount the
	 * consumer requested.
	 *
	 * <p>Below {@code depthThreshold}: returns {@code requested} unchanged.
	 * <p>Above {@code depthThreshold}: returns
	 * {@code min(maxCredits, requested × (depth/threshold) × multiplier)},
	 * always at least {@code requested} so credits never decrease.
	 */
	public int computeGranted(final long depth, final int requested) {
		if (depth <= this.depthThreshold) {
			return requested;
		}
		final double scale = (double) depth / this.depthThreshold * this.multiplier;
		final long grant = (long) Math.ceil(requested * scale);
		return (int) Math.min(this.maxCredits, Math.max(requested, grant));
	}

	private long getQueueDepth(final String queueName) {
		final long[] cached = this.depthCache.get(queueName);
		final long now = System.currentTimeMillis();
		if ((cached != null) && ((now - cached[1]) < this.cacheTtlMillis)) {
			return cached[0];
		}
		try {
			final ClientSession session = this.getQuerySession();
			if (session == null) {
				return 0L;
			}
			final long depth = session.queueQuery(SimpleString.of(queueName)).getMessageCount();
			this.depthCache.put(queueName, new long[] { depth, now });
			return depth;
		}
		catch (final Exception e) {
			LOGGER.warn("DynamicCredit — could not query depth for queue '{}': {}", queueName, e.getMessage());
			return 0L;
		}
	}

	private ClientSession getQuerySession() {
		if (this.querySession == null) {
			synchronized (this.querySessionLock) {
				if (this.querySession == null) {
					try {
						final ClientSessionFactory csf = this.factory.getServerLocator().createSessionFactory();
						// Use the factory's configured credentials so the session authenticates
						// the same way as regular consumer/producer sessions.
						this.querySession = csf.createSession(
								this.factory.getUser(),
								this.factory.getPassword(),
								false,
								true,
								true,
								false,
								ActiveMQClient.DEFAULT_ACK_BATCH_SIZE);
					}
					catch (final Exception e) {
						LOGGER.warn("DynamicCredit — could not create query session: {}", e.getMessage());
						return null;
					}
				}
			}
		}
		return this.querySession;
	}
}
