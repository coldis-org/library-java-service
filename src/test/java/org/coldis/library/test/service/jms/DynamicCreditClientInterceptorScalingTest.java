package org.coldis.library.test.service.jms;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionCreateConsumerMessage;
import org.coldis.library.service.jms.DynamicCreditClientInterceptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Broker-free tests proving a consumer's granted credits grow with queue depth
 * (up to {@code maxCredits}) instead of jumping straight to the cap, and that
 * protocol control packets (reset and disable-flow-control) are respected.
 *
 * <p>Queue depth is stubbed by overriding {@link DynamicCreditClientInterceptor#getPendingDepth(String)},
 * so these tests exercise the real {@code intercept}/{@code handleFlowCredit} grant
 * policy without a broker. Each measurement uses a fresh consumer (its own session
 * channel) so its outstanding window starts at zero.
 */
@DisplayName("DynamicCreditClientInterceptor — depth-proportional grant")
class DynamicCreditClientInterceptorScalingTest {

	private static final long DEPTH_THRESHOLD = 100L;
	private static final int MAX_CREDITS = 1_000_000;
	private static final int REQUESTED_CREDITS = 10;
	private static final long VERY_DEEP_QUEUE_DEPTH = 100_000_000L;

	/** Interceptor whose reported queue depth is fixed per call, avoiding a broker. */
	private static final class StubbedDepthInterceptor extends DynamicCreditClientInterceptor {

		private volatile long stubbedDepth;

		private StubbedDepthInterceptor(final double multiplier) {
			super(null, DEPTH_THRESHOLD, multiplier, MAX_CREDITS, 0L);
		}

		@Override
		protected long getPendingDepth(final String queueName) {
			return this.stubbedDepth;
		}
	}

	/** Registers a consumer (id 0) on the given channel and queue via the real create path. */
	private void createConsumer(
			final StubbedDepthInterceptor interceptor,
			final long channelId,
			final String queueName) throws Exception {
		final SessionCreateConsumerMessage createConsumerMessage =
				new SessionCreateConsumerMessage(0L, SimpleString.of(queueName), null, 0, false, true);
		createConsumerMessage.setChannelID(channelId);
		interceptor.intercept(createConsumerMessage, null);
	}

	/**
	 * Registers a consumer (id 0) on the given channel via the real create path,
	 * on a channel-specific queue so single-consumer tests keep a consumer count
	 * of one per queue.
	 */
	private void createConsumer(
			final StubbedDepthInterceptor interceptor,
			final long channelId) throws Exception {
		this.createConsumer(interceptor, channelId, "orders-" + channelId);
	}

	/** Sends one flow-credit packet for consumer 0 on the given channel and returns the (possibly rewritten) credits. */
	private int sendCreditPacket(
			final StubbedDepthInterceptor interceptor,
			final long channelId,
			final int requestedCredits) throws Exception {
		final SessionConsumerFlowCreditMessage flowCreditMessage =
				new SessionConsumerFlowCreditMessage(0L, requestedCredits);
		flowCreditMessage.setChannelID(channelId);
		interceptor.intercept(flowCreditMessage, null);
		return flowCreditMessage.getCredits();
	}

	/** Registers a fresh consumer on its own channel and returns the credits granted for one request. */
	private int grantedCreditsAtDepth(
			final StubbedDepthInterceptor interceptor,
			final long channelId,
			final long pendingDepth) throws Exception {
		interceptor.stubbedDepth = pendingDepth;
		this.createConsumer(interceptor, channelId);
		return this.sendCreditPacket(interceptor, channelId, REQUESTED_CREDITS);
	}

	@Test
	@DisplayName("granted credits increase with depth while inside the ramp")
	void testGrantGrowsWithDepth() throws Exception {
		// multiplier=0.5 → the window ramps to maxCredits at 3× threshold, so the two
		// depths below stay inside the ramp and must produce different grants.
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(0.5);
		final int shallowerGrant = this.grantedCreditsAtDepth(interceptor, 1L, DEPTH_THRESHOLD + (DEPTH_THRESHOLD / 2));
		final int deeperGrant = this.grantedCreditsAtDepth(interceptor, 2L, (DEPTH_THRESHOLD * 2) + (DEPTH_THRESHOLD / 2));
		Assertions.assertTrue(deeperGrant > shallowerGrant,
				"A deeper queue must grant more credits than a shallower one; got shallower=" + shallowerGrant
						+ " deeper=" + deeperGrant);
	}

	@Test
	@DisplayName("a very deep queue caps the grant at maxCredits")
	void testVeryDeepQueueCapsAtMaxCredits() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		final int grantedCredits = this.grantedCreditsAtDepth(interceptor, 1L, VERY_DEEP_QUEUE_DEPTH);
		Assertions.assertEquals(MAX_CREDITS, grantedCredits,
				"Grant to a fresh consumer on a very deep queue must equal maxCredits");
	}

	@Test
	@DisplayName("below the threshold the request passes through unchanged")
	void testBelowThresholdPassesThrough() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		final int grantedCredits = this.grantedCreditsAtDepth(interceptor, 1L, DEPTH_THRESHOLD / 2);
		Assertions.assertEquals(REQUESTED_CREDITS, grantedCredits,
				"Below the depth threshold credits must pass through unchanged");
	}

	@Test
	@DisplayName("slow-consumer reset (credit 0) passes through and the next refill re-inflates the window")
	void testResetZerosWindowAndNextRefillReinflates() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		interceptor.stubbedDepth = VERY_DEEP_QUEUE_DEPTH;
		this.createConsumer(interceptor, 1L);
		Assertions.assertEquals(MAX_CREDITS, this.sendCreditPacket(interceptor, 1L, REQUESTED_CREDITS),
				"A fresh consumer on a deep queue fills the window");
		Assertions.assertEquals(0, this.sendCreditPacket(interceptor, 1L, 0),
				"The reset packet must pass through unmodified");
		Assertions.assertEquals(MAX_CREDITS, this.sendCreditPacket(interceptor, 1L, REQUESTED_CREDITS),
				"After the broker window was reset, the next refill must re-inflate it");
	}

	@Test
	@DisplayName("disable-flow-control packet (credit -1) passes through unmodified")
	void testDisableFlowControlPassesThrough() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		interceptor.stubbedDepth = VERY_DEEP_QUEUE_DEPTH;
		this.createConsumer(interceptor, 1L);
		Assertions.assertEquals(-1, this.sendCreditPacket(interceptor, 1L, -1),
				"The disable-flow-control packet must never be rewritten");
	}

	@Test
	@DisplayName("failover recreate (create on an existing consumer key) restarts the window from zero")
	void testRecreateResetsWindow() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		interceptor.stubbedDepth = VERY_DEEP_QUEUE_DEPTH;
		this.createConsumer(interceptor, 1L);
		Assertions.assertEquals(MAX_CREDITS, this.sendCreditPacket(interceptor, 1L, REQUESTED_CREDITS),
				"A fresh consumer on a deep queue fills the window");
		// Failover recreates the consumer with the same session-scoped key; the
		// server-side consumer is brand new with zero credits.
		this.createConsumer(interceptor, 1L);
		Assertions.assertEquals(MAX_CREDITS, this.sendCreditPacket(interceptor, 1L, REQUESTED_CREDITS),
				"A recreated consumer starts with a fresh broker window; the tracked window must restart too");
	}

	@Test
	@DisplayName("slow-consumer sentinel (credit 1) passes through untouched and does not inflate the window")
	void testSentinelPassesThroughWithoutInflation() throws Exception {
		// The 1-credit packet sent before every poll is the dispatch trigger, not a
		// byte refill: inflating it would hand a full window to idle pollers and
		// drift the tracked window on every poll.
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		interceptor.stubbedDepth = VERY_DEEP_QUEUE_DEPTH;
		this.createConsumer(interceptor, 1L);
		Assertions.assertEquals(1, this.sendCreditPacket(interceptor, 1L, 1),
				"The sentinel credit must pass through untouched even on a deep queue");
	}

	@Test
	@DisplayName("window decays back to pass-through once depth falls below the justified target")
	void testWindowDecaysWhenDepthFallsBelowTarget() throws Exception {
		final int refillCredits = 400_000;
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		interceptor.stubbedDepth = VERY_DEEP_QUEUE_DEPTH;
		this.createConsumer(interceptor, 1L);
		Assertions.assertEquals(MAX_CREDITS, this.sendCreditPacket(interceptor, 1L, refillCredits),
				"A fresh consumer on a deep queue fills the window");
		// The queue drains below the threshold: the inflated window must shrink
		// (grant only a single credit per refill) instead of staying at maxCredits
		// forever and hoarding prefetch on a shallow queue.
		interceptor.stubbedDepth = 0L;
		Assertions.assertEquals(1, this.sendCreditPacket(interceptor, 1L, refillCredits),
				"An over-inflated window must decay: grant a single credit, not the freed bytes");
		Assertions.assertEquals(1, this.sendCreditPacket(interceptor, 1L, refillCredits),
				"Decay must continue while the window is still above the justified target");
		Assertions.assertEquals(refillCredits, this.sendCreditPacket(interceptor, 1L, refillCredits),
				"Once the window has decayed to the justified target, refills pass through again");
	}

	@Test
	@DisplayName("the target window is computed from each consumer's share of the depth, not the raw depth")
	void testTargetWindowSplitsAcrossConsumersOnSameQueue() throws Exception {
		// threshold=100, multiplier=1.0, depth=150.
		// A single consumer owns the whole depth: 150 > 100 → window inflates.
		final StubbedDepthInterceptor aloneInterceptor = new StubbedDepthInterceptor(1.0);
		aloneInterceptor.stubbedDepth = 150L;
		this.createConsumer(aloneInterceptor, 1L, "orders-shared");
		Assertions.assertEquals(MAX_CREDITS / 2, this.sendCreditPacket(aloneInterceptor, 1L, REQUESTED_CREDITS),
				"A single consumer's target is computed from the full depth");
		// Two consumers share the same queue: each consumer's share is 75 ≤ 100,
		// so neither window may inflate — the backlog is not deep enough for both.
		final StubbedDepthInterceptor sharedInterceptor = new StubbedDepthInterceptor(1.0);
		sharedInterceptor.stubbedDepth = 150L;
		this.createConsumer(sharedInterceptor, 1L, "orders-shared");
		this.createConsumer(sharedInterceptor, 2L, "orders-shared");
		Assertions.assertEquals(REQUESTED_CREDITS, this.sendCreditPacket(sharedInterceptor, 1L, REQUESTED_CREDITS),
				"With two consumers the per-consumer share (75) is below the threshold — no inflation");
	}
}
