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
 * Client-side outgoing interceptor that enlarges consumer credit grants when a
 * queue is backed up, so consumers prefetch a deeper batch under load while
 * still receiving one message at a time when the queue is shallow (which keeps
 * distribution fair across consumers).
 *
 * <p>Tracking is per consumer <em>per session</em>. Artemis numbers consumers
 * from a per-session generator, so the first consumer of every session is id
 * {@code 0}; the single interceptor registered on the shared
 * {@code ServerLocator} therefore sees many consumers reporting the same id.
 * Each consumer is keyed by connection + channel (session) + consumer id and
 * gets its own {@code maxCredits} budget, so adding consumers (concurrency)
 * multiplies total prefetch instead of sharing one budget.
 *
 * <p>On each flow-credit packet {@code requestedCredits} is the number of bytes
 * the consumer just freed. The scaling signal is each consumer's <em>fair share</em>
 * of the backlog — {@code pendingDepth / consumersOnQueue} — so windows only
 * inflate when there is enough depth for every consumer. The interceptor reduces
 * the consumer's outstanding count by the freed bytes, then:
 * <ul>
 *   <li>share &le; {@code depthThreshold}: passes the request through unchanged
 *       (no inflation) — one message at a time;</li>
 *   <li>share &gt; {@code depthThreshold}: raises the outstanding budget toward a
 *       target that grows with the share (see {@link #computeTargetWindow}), so a
 *       deeper backlog prefetches a larger batch, up to {@code maxCredits};</li>
 *   <li>outstanding budget above the target (the queue drained): grants only a
 *       single credit per refill so the window decays back to pass-through instead
 *       of hoarding prefetch on a shallow queue.</li>
 * </ul>
 * {@code maxCredits} is a hard cap on outstanding bytes per consumer. The
 * slow-consumer sentinel (a single credit sent before each poll) passes through
 * untouched and untracked: it is the dispatch trigger, not a byte refill, and
 * inflating it would hand full windows to idle pollers.
 *
 * <p>Queue depth is read with a client {@code queueQuery} and cached for
 * {@code cacheTtlMillis} to bound broker round-trips. When the broker-side
 * consumer window is positive the broker already manages prefetch, so this
 * interceptor disables itself to avoid double-stacking.
 */
public class DynamicCreditClientInterceptor implements Interceptor {

	private static final Logger LOGGER = LoggerFactory.getLogger(DynamicCreditClientInterceptor.class);

	private static final byte CREATE_CONSUMER_TYPE = 40;
	private static final byte CONSUMER_CREDITS_TYPE = 70;
	private static final byte CLOSE_CONSUMER_TYPE = 74;

	/**
	 * The single credit a slow consumer sends before each poll to trigger dispatch
	 * of one message. It is a dispatch trigger, not a byte refill, and is excluded
	 * from window accounting.
	 */
	private static final int SLOW_CONSUMER_SENTINEL_CREDIT = 1;

	private static final long SCALING_LOG_INTERVAL_MILLIS = 5 * 60 * 1000L;

	private static final Field CREDITS_FIELD;

	static {
		try {
			CREDITS_FIELD = SessionConsumerFlowCreditMessage.class.getDeclaredField("credits");
			CREDITS_FIELD.setAccessible(true);
		}
		catch (final NoSuchFieldException noSuchFieldException) {
			throw new ExceptionInInitializerError(noSuchFieldException);
		}
	}

	private final long depthThreshold;
	private final double multiplier;
	private final int maxCredits;
	private final long cacheTtlMillis;
	private final ActiveMQConnectionFactory connectionFactory;

	/**
	 * True when the broker-side consumer window is positive — in that case the
	 * broker already manages prefetch via its own window mechanism and this
	 * interceptor would double-stack on top of it, so scaling is disabled.
	 */
	private final boolean disabled;

	/**
	 * Session-scoped consumer key → queue name, populated when consumers are created.
	 *
	 * <p>The key is {@code connectionId|channelId|consumerId}, NOT the bare consumer
	 * id: Artemis numbers consumers from a per-session generator, so the first
	 * consumer of every session is id {@code 0}. A single interceptor on the shared
	 * {@code ServerLocator} sees every session's consumer 0, and keying by bare id
	 * would collapse them all onto one entry.
	 */
	private final ConcurrentHashMap<String, String> consumerQueues = new ConcurrentHashMap<>();

	/**
	 * Session-scoped consumer key → outstanding credits (bytes granted but not yet
	 * freed). Decremented by {@code requestedCredits} on each credit packet (bytes
	 * consumed), incremented by the final grant. Keyed like {@link #consumerQueues}
	 * so each consumer gets its own {@code maxCredits} budget.
	 */
	private final ConcurrentHashMap<String, AtomicInteger> consumerOutstanding = new ConcurrentHashMap<>();

	/**
	 * queueName → number of tracked consumers currently attached to the queue.
	 * Maintained on create/close so the fair-share target can divide the backlog
	 * across the consumers competing for it.
	 */
	private final ConcurrentHashMap<String, AtomicInteger> queueConsumerCounts = new ConcurrentHashMap<>();

	/** queueName → {pendingDepth, timestampMillis}, invalidated after cacheTtlMillis. */
	private final ConcurrentHashMap<String, long[]> depthCache = new ConcurrentHashMap<>();

	/** queueName → last timestamp (ms) a scaling INFO log was emitted. */
	private final ConcurrentHashMap<String, Long> lastScalingLogAt = new ConcurrentHashMap<>();

	private volatile ClientSession querySession;
	private final Object querySessionLock = new Object();

	/** Total consumer-create packets seen by this interceptor. */
	private final AtomicLong consumersRegistered = new AtomicLong(0);

	/** Total flow-credit packets seen by this interceptor. */
	private final AtomicLong creditPacketsIntercepted = new AtomicLong(0);

	/** Flow-credit packets where the credit value was raised (window inflated). */
	private final AtomicLong creditPacketsScaled = new AtomicLong(0);

	/** Flow-credit packets where the credit value was lowered (window decaying). */
	private final AtomicLong creditPacketsDecayed = new AtomicLong(0);

	public DynamicCreditClientInterceptor(
			final ActiveMQConnectionFactory connectionFactory,
			final long depthThreshold,
			final double multiplier,
			final int maxCredits,
			final long cacheTtlMillis) {
		this.connectionFactory = connectionFactory;
		this.depthThreshold = depthThreshold;
		this.multiplier = multiplier;
		this.maxCredits = maxCredits;
		this.cacheTtlMillis = cacheTtlMillis;
		final int consumerWindowSize = (connectionFactory != null) ? connectionFactory.getServerLocator().getConsumerWindowSize() : 0;
		this.disabled = consumerWindowSize > 0;
		if (this.disabled) {
			LOGGER.info("DynamicCredit — disabled: consumerWindowSize={} > 0, broker window handles prefetch", consumerWindowSize);
		}
	}

	/**
	 * Session-scoped identity for a consumer: {@code connectionId|channelId|consumerId}.
	 * Consumer ids alone are not unique across sessions (Artemis numbers them per
	 * session from 0), so the channel (session) and connection must qualify them.
	 */
	private static String buildConsumerKey(
			final Object connectionId,
			final long channelId,
			final long consumerId) {
		return connectionId + "|" + channelId + "|" + consumerId;
	}

	/**
	 * Pure helper: the target outstanding-credit window (in bytes) for a consumer
	 * given the queue's pending depth.
	 *
	 * <p>At or below {@code depthThreshold} the target is {@code 0}, so the caller
	 * leaves the request untouched (one message at a time, fair across consumers).
	 * Above the threshold the window grows linearly with how far depth exceeds the
	 * threshold — reaching the full {@code maxCredits} once
	 * {@code (depth/threshold - 1) × multiplier >= 1} — and is capped at
	 * {@code maxCredits}. A larger {@code multiplier} reaches the full window at a
	 * shallower depth.
	 *
	 * <p>Scaling the window (not the volatile {@code requestedCredits}) is deliberate:
	 * in slow-consumer mode the request is often the sentinel {@code 1}, which would
	 * scale to nothing.
	 */
	public int computeTargetWindow(final long pendingDepth) {
		int targetWindow = 0;
		if (pendingDepth > this.depthThreshold) {
			final double depthRatioAboveThreshold = (double) pendingDepth / this.depthThreshold - 1.0;
			final double windowFraction = Math.min(1.0, depthRatioAboveThreshold * this.multiplier);
			targetWindow = (int) Math.min(this.maxCredits, Math.ceil(this.maxCredits * windowFraction));
		}
		return targetWindow;
	}

	/**
	 * Lazily creates (and caches) the client session used to query queue depth.
	 * Returns {@code null} when the session cannot be created; callers treat that
	 * as depth {@code 0}.
	 */
	private ClientSession getQuerySession() {
		if (this.querySession == null) {
			synchronized (this.querySessionLock) {
				if (this.querySession == null) {
					try {
						final ClientSessionFactory clientSessionFactory = this.connectionFactory.getServerLocator().createSessionFactory();
						this.querySession = clientSessionFactory.createSession(
								this.connectionFactory.getUser(),
								this.connectionFactory.getPassword(),
								false,
								true,
								true,
								false,
								ActiveMQClient.DEFAULT_ACK_BATCH_SIZE);
					}
					catch (final Exception exception) {
						LOGGER.warn("DynamicCredit — could not create query session: {}", exception.getMessage());
					}
				}
			}
		}
		return this.querySession;
	}

	/**
	 * Returns the queue's message count, used as the scaling signal. Cached for
	 * {@code cacheTtlMillis} to bound broker round-trips. Returns {@code 0} (which
	 * keeps scaling off) when the query session is unavailable or the query fails.
	 *
	 * <p>Non-final and package-visible so tests can stub the depth without a broker.
	 */
	protected long getPendingDepth(final String queueName) {
		final long[] cachedDepth = this.depthCache.get(queueName);
		final long currentTimeMillis = System.currentTimeMillis();
		long pendingDepth = 0L;
		if ((cachedDepth != null) && ((currentTimeMillis - cachedDepth[1]) < this.cacheTtlMillis)) {
			pendingDepth = cachedDepth[0];
		}
		else {
			try {
				final ClientSession session = this.getQuerySession();
				if (session != null) {
					final ClientSession.QueueQuery queueQuery;
					synchronized (session) {
						queueQuery = session.queueQuery(SimpleString.of(queueName));
					}
					pendingDepth = queueQuery.getMessageCount();
					this.depthCache.put(queueName, new long[] { pendingDepth, currentTimeMillis });
					// A persistently-zero depth (queue not found / name mismatch) silently keeps
					// scaling off, so make the actual reading observable for diagnosis.
					LOGGER.debug("DynamicCredit — depth query queue='{}' exists={} messageCount={}", queueName, queueQuery.isExists(),
							pendingDepth);
				}
			}
			catch (final Exception exception) {
				LOGGER.warn("DynamicCredit — could not query depth for queue '{}': {}", queueName, exception.getMessage());
				// Invalidate the session so it is recreated on the next call.
				// Without this, a broker restart leaves the cached session permanently broken.
				this.querySession = null;
			}
		}
		return pendingDepth;
	}

	/** Returns the number of tracked consumers attached to the queue, never below one. */
	private int getConsumersOnQueue(final String queueName) {
		final AtomicInteger consumerCount = this.queueConsumerCounts.get(queueName);
		return (consumerCount != null) ? Math.max(1, consumerCount.get()) : 1;
	}

	/**
	 * Returns the target outstanding-credit window for one consumer of the queue,
	 * computed from the consumer's <em>fair share</em> of the pending depth
	 * ({@code pendingDepth / consumersOnQueue}) so windows only inflate when the
	 * backlog is deep enough for every consumer to fill one — a single consumer
	 * cannot capture prefetch that its siblings could be draining.
	 */
	private int computeFairShareTargetWindow(final String queueName) {
		final long pendingDepth = this.getPendingDepth(queueName);
		final long fairSharePendingDepth = pendingDepth / this.getConsumersOnQueue(queueName);
		return this.computeTargetWindow(fairSharePendingDepth);
	}

	/**
	 * Emits the throttled per-queue scaling INFO log (at most once every
	 * {@link #SCALING_LOG_INTERVAL_MILLIS}), summing outstanding credits across all
	 * consumers currently attached to the queue.
	 */
	private void logScalingActivity(
			final String queueName,
			final int requestedCredits,
			final int grantedCredits) {
		final long currentTimeMillis = System.currentTimeMillis();
		final Long lastScalingLogTimestamp = this.lastScalingLogAt.get(queueName);
		if ((lastScalingLogTimestamp == null) || ((currentTimeMillis - lastScalingLogTimestamp) >= DynamicCreditClientInterceptor.SCALING_LOG_INTERVAL_MILLIS)) {
			this.lastScalingLogAt.put(queueName, currentTimeMillis);
			final long totalOutstandingForQueue = this.consumerQueues.entrySet().stream()
					.filter(consumerEntry -> queueName.equals(consumerEntry.getValue()))
					.mapToLong(consumerEntry -> {
						final AtomicInteger entryOutstanding = this.consumerOutstanding.get(consumerEntry.getKey());
						return (entryOutstanding != null) ? entryOutstanding.get() : 0L;
					}).sum();
			LOGGER.info("DynamicCredit — queue={} depth={} requested={} granted={} totalOutstanding={}", queueName,
					this.depthCache.getOrDefault(queueName, new long[] { 0L, 0L })[0], requestedCredits, grantedCredits, totalOutstandingForQueue);
		}
	}

	/**
	 * Applies the fair-share grant to one positive flow-credit request, updating the
	 * consumer's outstanding window and rewriting the packet when the grant differs
	 * from the request: refills inflate the window toward the fair-share target when
	 * the backlog warrants it, and decay it (single-credit grants) when the window
	 * exceeds what the backlog justifies.
	 */
	private void scaleFlowCredit(
			final SessionConsumerFlowCreditMessage flowCreditMessage,
			final String queueName,
			final AtomicInteger outstandingCredits,
			final int requestedCredits) {
		// The slow-consumer sentinel is the poll's dispatch trigger, not a byte refill:
		// it must reach the broker as-is, and tracking it would drift the window upward
		// on every poll (falsely triggering decay for baseline consumers) while
		// inflating it would hand full windows to idle pollers.
		if (requestedCredits != DynamicCreditClientInterceptor.SLOW_CONSUMER_SENTINEL_CREDIT) {
			final int grantedCredits;
			synchronized (outstandingCredits) {
				final int currentOutstanding = outstandingCredits.get();
				// Consumer freed `requestedCredits` bytes — reduce outstanding accordingly.
				final int outstandingAfterFreed = Math.max(0, currentOutstanding - requestedCredits);
				final int fairShareTargetWindow = this.computeFairShareTargetWindow(queueName);
				if (outstandingAfterFreed > fairShareTargetWindow) {
					// The window exceeds what the backlog justifies (the queue drained or
					// siblings joined): grant only a single credit so the freed bytes shrink
					// the broker-side window until it matches the target, then pass-through
					// resumes. Never grant zero — that is a reset on the wire.
					grantedCredits = DynamicCreditClientInterceptor.SLOW_CONSUMER_SENTINEL_CREDIT;
					outstandingCredits.set(outstandingAfterFreed + grantedCredits);
				}
				else {
					// Raise the window toward the fair-share target. Never grant less than
					// requested (that would throttle below the pass-through baseline) and
					// never let outstanding fall short of the target the backlog warrants.
					final int passthroughOutstanding = outstandingAfterFreed + requestedCredits;
					final int targetOutstanding = Math.max(passthroughOutstanding, fairShareTargetWindow);
					grantedCredits = targetOutstanding - outstandingAfterFreed;
					outstandingCredits.set(targetOutstanding);
				}
			}

			if (grantedCredits != requestedCredits) {
				try {
					DynamicCreditClientInterceptor.CREDITS_FIELD.setInt(flowCreditMessage, grantedCredits);
					if (grantedCredits > requestedCredits) {
						this.creditPacketsScaled.incrementAndGet();
						LOGGER.debug("DynamicCredit — queue={} requested={} granted={} outstanding={}", queueName, requestedCredits,
								grantedCredits, outstandingCredits.get());
						this.logScalingActivity(queueName, requestedCredits, grantedCredits);
					}
					else {
						this.creditPacketsDecayed.incrementAndGet();
						LOGGER.debug("DynamicCredit — decay queue={} requested={} granted={} outstanding={}", queueName,
								requestedCredits, grantedCredits, outstandingCredits.get());
					}
				}
				catch (final IllegalAccessException illegalAccessException) {
					// Roll back the outstanding adjustment so the accounting stays consistent.
					synchronized (outstandingCredits) {
						outstandingCredits.addAndGet(requestedCredits - grantedCredits);
					}
					LOGGER.warn("DynamicCredit — could not modify credits field, skipping", illegalAccessException);
				}
			}
		}
	}

	/**
	 * Handles one consumer's flow-credit packet: protocol control packets (credits
	 * {@code <= 0}) pass through untouched while the tracked window is zeroed to
	 * mirror the broker-side reset; positive refills go through the
	 * depth-proportional grant.
	 */
	private void handleFlowCredit(
			final SessionConsumerFlowCreditMessage flowCreditMessage,
			final String consumerKey) {
		this.creditPacketsIntercepted.incrementAndGet();
		final String queueName = this.consumerQueues.get(consumerKey);
		final AtomicInteger outstandingCredits = (queueName != null) ? this.consumerOutstanding.get(consumerKey) : null;
		if ((queueName != null) && (outstandingCredits != null)) {
			final int requestedCredits = flowCreditMessage.getCredits();
			// Non-positive credits are protocol control packets, not byte refills: 0 resets
			// the server-side window (the slow-consumer reset sent after a timed-out empty
			// poll) and -1 disables flow control entirely. They must pass through untouched —
			// rewriting a reset into a grant would strand messages in idle consumers'
			// buffers. Mirror the reset locally so the next real request re-inflates the
			// window instead of assuming it is still full (a desynced tracker permanently
			// collapses the consumer to one message in flight).
			if (requestedCredits <= 0) {
				synchronized (outstandingCredits) {
					outstandingCredits.set(0);
				}
			}
			else {
				this.scaleFlowCredit(flowCreditMessage, queueName, outstandingCredits, requestedCredits);
			}
		}
	}

	/** Decrements the queue's tracked consumer count, dropping the entry at zero. */
	private void decrementQueueConsumerCount(final String queueName) {
		this.queueConsumerCounts.computeIfPresent(queueName,
				(countedQueueName, consumerCount) -> (consumerCount.decrementAndGet() <= 0) ? null : consumerCount);
	}

	@Override
	public boolean intercept(
			final Packet packet,
			final RemotingConnection connection) throws ActiveMQException {
		if (!this.disabled) {
			final byte packetType = packet.getType();
			final Object connectionId = (connection != null) ? connection.getID() : null;
			final long channelId = packet.getChannelID();
			if (packetType == CREATE_CONSUMER_TYPE) {
				final SessionCreateConsumerMessage createConsumerMessage = (SessionCreateConsumerMessage) packet;
				final SimpleString queueName = createConsumerMessage.getQueueName();
				if (queueName != null) {
					final String consumerKey = DynamicCreditClientInterceptor.buildConsumerKey(connectionId, channelId, createConsumerMessage.getID());
					// A create on an already-tracked key is a failover recreate: the previous
					// registration is replaced (its queue count released) and the tracked
					// window restarts, since the server-side consumer is brand new.
					final String previousQueueName = this.consumerQueues.put(consumerKey, queueName.toString());
					if (previousQueueName != null) {
						this.decrementQueueConsumerCount(previousQueueName);
					}
					this.queueConsumerCounts.computeIfAbsent(queueName.toString(), countedQueueName -> new AtomicInteger(0)).incrementAndGet();
					this.consumerOutstanding.put(consumerKey, new AtomicInteger(0));
					this.consumersRegistered.incrementAndGet();
				}
			}
			else if (packetType == CONSUMER_CREDITS_TYPE) {
				final SessionConsumerFlowCreditMessage flowCreditMessage = (SessionConsumerFlowCreditMessage) packet;
				this.handleFlowCredit(flowCreditMessage,
						DynamicCreditClientInterceptor.buildConsumerKey(connectionId, channelId, flowCreditMessage.getConsumerID()));
			}
			else if (packetType == CLOSE_CONSUMER_TYPE) {
				final long consumerId = ((SessionConsumerCloseMessage) packet).getConsumerID();
				final String consumerKey = DynamicCreditClientInterceptor.buildConsumerKey(connectionId, channelId, consumerId);
				final String closedQueueName = this.consumerQueues.remove(consumerKey);
				if (closedQueueName != null) {
					this.decrementQueueConsumerCount(closedQueueName);
				}
				this.consumerOutstanding.remove(consumerKey);
			}
		}
		return true;
	}

	/** Total consumer-create packets seen. */
	public long getConsumersRegistered() {
		return this.consumersRegistered.get();
	}

	/** Total flow-credit packets seen (including those not modified). */
	public long getCreditPacketsIntercepted() {
		return this.creditPacketsIntercepted.get();
	}

	/** Flow-credit packets whose credit value was raised by the scaling logic (window inflated). */
	public long getCreditPacketsScaled() {
		return this.creditPacketsScaled.get();
	}

	/** Flow-credit packets whose credit value was lowered by the decay logic (window shrinking). */
	public long getCreditPacketsDecayed() {
		return this.creditPacketsDecayed.get();
	}
}
