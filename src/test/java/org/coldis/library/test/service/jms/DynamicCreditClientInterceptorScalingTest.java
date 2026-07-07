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
 * (up to {@code maxCredits}) instead of jumping straight to the cap.
 *
 * <p>Queue depth is stubbed by overriding {@link DynamicCreditClientInterceptor#getPendingDepth(String)},
 * so these tests exercise the real {@code intercept}/{@code handleFlowCredit} grant
 * policy without a broker. Each measurement uses a fresh consumer (its own session
 * channel) so its outstanding window starts at zero.
 */
@DisplayName("DynamicCreditClientInterceptor — depth-proportional grant")
class DynamicCreditClientInterceptorScalingTest {

	private static final long THRESHOLD = 100L;
	private static final int MAX_CREDITS = 1_000_000;
	private static final int REQUESTED_CREDITS = 10;

	/** Interceptor whose reported queue depth is fixed per call, avoiding a broker. */
	private static final class StubbedDepthInterceptor extends DynamicCreditClientInterceptor {

		private volatile long stubbedDepth;

		private StubbedDepthInterceptor(final double multiplier) {
			super(null, THRESHOLD, multiplier, MAX_CREDITS, 0L);
		}

		@Override
		protected long getPendingDepth(final String queueName) {
			return this.stubbedDepth;
		}
	}

	/** Registers a fresh consumer on its own channel and returns the credits granted for one request. */
	private int grantedCreditsAt(
			final StubbedDepthInterceptor interceptor,
			final long channelId,
			final long depth) throws Exception {
		interceptor.stubbedDepth = depth;
		final SessionCreateConsumerMessage create =
				new SessionCreateConsumerMessage(0L, SimpleString.of("orders"), null, 0, false, true);
		create.setChannelID(channelId);
		interceptor.intercept(create, null);
		final SessionConsumerFlowCreditMessage credit = new SessionConsumerFlowCreditMessage(0L, REQUESTED_CREDITS);
		credit.setChannelID(channelId);
		interceptor.intercept(credit, null);
		return credit.getCredits();
	}

	@Test
	@DisplayName("granted credits increase with depth while inside the ramp")
	void testGrantGrowsWithDepth() throws Exception {
		// multiplier=0.5 → the window ramps to maxCredits at 3× threshold, so the two
		// depths below stay inside the ramp and must produce different grants.
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(0.5);
		final int shallowerGrant = this.grantedCreditsAt(interceptor, 1L, THRESHOLD + THRESHOLD / 2);
		final int deeperGrant = this.grantedCreditsAt(interceptor, 2L, THRESHOLD * 2 + THRESHOLD / 2);
		Assertions.assertTrue(deeperGrant > shallowerGrant,
				"A deeper queue must grant more credits than a shallower one; got shallower=" + shallowerGrant
						+ " deeper=" + deeperGrant);
	}

	@Test
	@DisplayName("a very deep queue caps the grant at maxCredits")
	void testVeryDeepQueueCapsAtMaxCredits() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		final int grant = this.grantedCreditsAt(interceptor, 1L, 100_000_000L);
		Assertions.assertEquals(MAX_CREDITS, grant, "Grant to a fresh consumer on a very deep queue must equal maxCredits");
	}

	@Test
	@DisplayName("below the threshold the request passes through unchanged")
	void testBelowThresholdPassesThrough() throws Exception {
		final StubbedDepthInterceptor interceptor = new StubbedDepthInterceptor(2.0);
		final int grant = this.grantedCreditsAt(interceptor, 1L, THRESHOLD / 2);
		Assertions.assertEquals(REQUESTED_CREDITS, grant, "Below the depth threshold credits must pass through unchanged");
	}
}
