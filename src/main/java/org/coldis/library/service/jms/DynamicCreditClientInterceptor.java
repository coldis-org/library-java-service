package org.coldis.library.service.jms;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 * requests based on current queue depth, while enforcing a hard cap on total
 * outstanding credits per consumer.
 *
 * <p>Each consumer's outstanding credits are tracked across bursts. When a
 * credit request arrives, {@code requested} represents bytes freed by a
 * processed message. The interceptor:
 * <ol>
 *   <li>Subtracts {@code requested} from the consumer's outstanding count
 *       (those bytes were consumed).</li>
 *   <li>Computes how much headroom remains before {@code maxCredits}.</li>
 *   <li>Scales the new grant proportionally to the queue's pending depth
 *       ({@code messageCount - deliveringCount}), capped at the available
 *       headroom so total outstanding never exceeds {@code maxCredits}.</li>
 * </ol>
 *
 * <p>This ensures {@code maxCredits} is a true cap on total in-flight bytes
 * per consumer, not merely a per-burst limit.
 *
 * <p>Behaviour by depth:
 * <ul>
 *   <li>pending depth &le; {@code depthThreshold}: grant only what headroom
 *       allows (at most {@code requested}), preserving fairness.</li>
 *   <li>pending depth &gt; {@code depthThreshold}: grant
 *       {@code min(headroom, requested &times; (depth/threshold) &times; multiplier)}
 *       to increase throughput under load.</li>
 * </ul>
 *
 * <p>Queue depth is cached for {@code cacheTtlMillis} milliseconds to bound
 * broker round-trips.
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

	/**
	 * consumerID → outstanding credits (bytes granted but not yet freed).
	 * Decremented by {@code requested} on each credit packet (bytes consumed),
	 * incremented by the final grant.
	 */
	private final ConcurrentHashMap<Long, AtomicInteger> consumerOutstanding = new ConcurrentHashMap<>();

	/** queueName → {pendingDepth, timestampMillis}, invalidated after cacheTtlMillis. */
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
				this.consumerOutstanding.put(msg.getID(), new AtomicInteger(0));
				this.consumersRegistered.incrementAndGet();
			}
		}
		else if (type == CONSUMER_CREDITS_TYPE) {
			this.handleFlowCredit((SessionConsumerFlowCreditMessage) packet);
		}
		else if (type == CLOSE_CONSUMER_TYPE) {
			final long id = ((SessionConsumerCloseMessage) packet).getConsumerID();
			this.consumerQueues.remove(id);
			this.consumerOutstanding.remove(id);
		}
		return true;
	}

	private void handleFlowCredit(final SessionConsumerFlowCreditMessage msg) {
		this.creditPacketsIntercepted.incrementAndGet();
		final String queueName = this.consumerQueues.get(msg.getConsumerID());
		if (queueName == null) {
			return;
		}
		final AtomicInteger outstanding = this.consumerOutstanding.get(msg.getConsumerID());
		if (outstanding == null) {
			return;
		}

		final int requested = msg.getCredits();

		// Compute the final grant atomically: account for freed bytes, then fill
		// headroom up to maxCredits using depth-proportional scaling.
		final int granted;
		synchronized (outstanding) {
			final int current = outstanding.get();
			// Consumer freed `requested` bytes — reduce outstanding accordingly.
			final int afterFreed = Math.max(0, current - requested);
			// How much more can we push before hitting the cap?
			final int headroom = this.maxCredits - afterFreed;
			if (headroom <= 0) {
				// Already at or above cap even after accounting for the freed bytes.
				// Grant exactly `requested` to stay at the cap.
				outstanding.set(afterFreed + requested);
				granted = requested;
			}
			else {
				// Headroom available — scale up based on pending queue depth.
				final long pendingDepth = this.getPendingDepth(queueName);
				final int scaled = this.computeGranted(pendingDepth, requested);
				granted = Math.min(scaled, headroom);
				outstanding.set(afterFreed + granted);
			}
		}

		if (granted != requested) {
			try {
				DynamicCreditClientInterceptor.CREDITS_FIELD.setInt(msg, granted);
				this.creditPacketsScaled.incrementAndGet();
				LOGGER.debug("DynamicCredit — queue={} requested={} granted={} outstanding={}", queueName, requested, granted,
						outstanding.get());
			}
			catch (final IllegalAccessException e) {
				// Roll back the outstanding adjustment so the accounting stays consistent.
				synchronized (outstanding) {
					outstanding.addAndGet(requested - granted);
				}
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
	 * Returns the credits to grant given pending queue depth and the amount the
	 * consumer requested.
	 *
	 * <p>Below {@code depthThreshold}: returns {@code requested} unchanged.
	 * <p>Above {@code depthThreshold}: returns
	 * {@code requested × (depth/threshold) × multiplier},
	 * always at least {@code requested}.  The caller caps this at the available
	 * headroom so total outstanding never exceeds {@code maxCredits}.
	 */
	public int computeGranted(final long depth, final int requested) {
		if (depth <= this.depthThreshold) {
			return requested;
		}
		final double scale = (double) depth / this.depthThreshold * this.multiplier;
		final long grant = (long) Math.ceil(requested * scale);
		return (int) Math.max(requested, grant);
	}

	/**
	 * Returns pending (not-yet-dispatched) message count for the queue.
	 * Uses {@code messageCount - deliveringCount} so that messages already
	 * in-flight do not inflate the scaling signal.
	 */
	private long getPendingDepth(final String queueName) {
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
			final long pendingDepth;
			synchronized (session) {
				pendingDepth = session.queueQuery(SimpleString.of(queueName)).getMessageCount();
			}
			this.depthCache.put(queueName, new long[] { pendingDepth, now });
			return pendingDepth;
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
