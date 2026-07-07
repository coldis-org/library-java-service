package org.coldis.library.test.service.jms;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionCreateConsumerMessage;
import org.coldis.library.service.jms.DynamicCreditClientInterceptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving the interceptor must track outstanding credits <em>per
 * consumer per session</em>, not by bare consumer id.
 *
 * <p>Artemis assigns consumer ids from a per-session generator
 * ({@code SessionContext.idGenerator = new SimpleIDGenerator(0)}), so the first
 * consumer of <em>every</em> session is id {@code 0}, the second is id {@code 1},
 * and so on. Spring's {@code DefaultMessageListenerContainer} at concurrency
 * {@code 1-N} opens up to N sessions — each with its own consumer id {@code 0} —
 * and connection pooling multiplies this further. The single interceptor
 * registered on the shared {@code ServerLocator} therefore sees many distinct
 * consumers that all report id {@code 0}.
 *
 * <p>Because the interceptor keys {@code consumerQueues}/{@code consumerOutstanding}
 * by the bare consumer id, those consumers collapse onto one shared
 * outstanding-credit window: the whole listener group shares a single
 * {@code maxCredits} budget instead of one budget each, and each newly created
 * consumer {@code 0} resets that shared counter. That is why adding consumers
 * (concurrency) fails to grow messages-in-flight, and why raising
 * {@code maxCredits} barely helps.
 *
 * <p>These tests are broker-free: the interceptor is built with a {@code null}
 * factory so {@code getPendingDepth()} yields {@code 0} and every credit packet
 * takes the pass-through branch, keeping the accounting deterministic and
 * isolating the consumer-identity behaviour.
 *
 * <p>The session is identified by the packet's {@code channelID} — Artemis sends
 * a session's create-consumer and flow-credit packets on that session's channel.
 */
@DisplayName("DynamicCreditClientInterceptor — session-scoped consumer tracking")
class DynamicCreditClientInterceptorSessionScopingTest {

	private static final long DEPTH_THRESHOLD = 100L;
	private static final double MULTIPLIER = 2.0;
	private static final int MAX_CREDITS = 10 * 1024 * 1024;
	private static final long CACHE_TTL_MILLIS = 5000L;

	private DynamicCreditClientInterceptor interceptor;

	@BeforeEach
	void setUp() {
		this.interceptor = new DynamicCreditClientInterceptor(null, DEPTH_THRESHOLD, MULTIPLIER, MAX_CREDITS, CACHE_TTL_MILLIS);
	}

	/** Simulates a session creating a consumer: create-consumer packet on the session's channel. */
	private void createConsumer(final long consumerId, final long channelId, final String queueName) throws Exception {
		final SessionCreateConsumerMessage createConsumerMessage =
				new SessionCreateConsumerMessage(consumerId, SimpleString.of(queueName), null, 0, false, true);
		createConsumerMessage.setChannelID(channelId);
		this.interceptor.intercept(createConsumerMessage, null);
	}

	/** Simulates a flow-credit request from a consumer on its session's channel. */
	private void sendCreditPacket(final long consumerId, final long channelId, final int requestedCredits) throws Exception {
		final SessionConsumerFlowCreditMessage flowCreditMessage = new SessionConsumerFlowCreditMessage(consumerId, requestedCredits);
		flowCreditMessage.setChannelID(channelId);
		this.interceptor.intercept(flowCreditMessage, null);
	}

	@SuppressWarnings("unchecked")
	private ConcurrentHashMap<Object, AtomicInteger> trackedOutstandingCredits() throws Exception {
		final Field consumerOutstandingField = DynamicCreditClientInterceptor.class.getDeclaredField("consumerOutstanding");
		consumerOutstandingField.setAccessible(true);
		return (ConcurrentHashMap<Object, AtomicInteger>) consumerOutstandingField.get(this.interceptor);
	}

	@Test
	@DisplayName("two sessions each with consumer id 0 are tracked as two independent windows")
	void testDistinctSessionsSameConsumerIdNotCollapsed() throws Exception {
		this.createConsumer(0L, 100L, "orders");
		this.createConsumer(0L, 200L, "orders");
		Assertions.assertEquals(2, this.trackedOutstandingCredits().size(),
				"Each session's consumer must own its outstanding-credit window; keying by bare "
						+ "consumer id collapses both sessions' consumer 0 into a single shared window.");
	}

	@Test
	@DisplayName("a new session's consumer 0 must not reset an existing consumer 0's window")
	void testNewConsumerDoesNotResetSiblingOutstanding() throws Exception {
		this.createConsumer(0L, 100L, "orders");
		this.sendCreditPacket(0L, 100L, 1000);
		this.createConsumer(0L, 200L, "orders");
		final boolean firstWindowSurvived = this.trackedOutstandingCredits().values().stream()
				.anyMatch(outstandingCounter -> outstandingCounter.get() == 1000);
		Assertions.assertTrue(firstWindowSurvived,
				"Creating a second session's consumer 0 overwrote the shared outstanding counter, "
						+ "resetting the first consumer's window back to 0.");
	}
}
