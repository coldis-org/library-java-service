package org.coldis.library.test.service.jms;

import org.coldis.library.service.jms.DynamicCreditClientInterceptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DynamicCreditClientInterceptor#computeTargetWindow}, the
 * pure depth → target-window function. No Spring context or broker required.
 *
 * <p>{@code maxCredits} is 1000 here so the expected byte targets are easy to read.
 */
@DisplayName("DynamicCreditClientInterceptor — computeTargetWindow logic")
class DynamicCreditClientInterceptorUnitTest {

	private static final long THRESHOLD = 100L;
	private static final int MAX_CREDITS = 1000;
	private static final long CACHE_TTL_MILLIS = 5000L;

	private DynamicCreditClientInterceptor interceptorWithMultiplier(final double multiplier) {
		return new DynamicCreditClientInterceptor(null, THRESHOLD, multiplier, MAX_CREDITS, CACHE_TTL_MILLIS);
	}

	@Test
	@DisplayName("depth = 0 → target window is 0 (pass-through)")
	void testZeroDepthWindowIsZero() {
		Assertions.assertEquals(0, this.interceptorWithMultiplier(1.0).computeTargetWindow(0L));
	}

	@Test
	@DisplayName("depth < threshold → target window is 0")
	void testBelowThresholdWindowIsZero() {
		Assertions.assertEquals(0, this.interceptorWithMultiplier(1.0).computeTargetWindow(THRESHOLD - 1));
	}

	@Test
	@DisplayName("depth == threshold → target window is 0 (boundary)")
	void testAtThresholdWindowIsZero() {
		Assertions.assertEquals(0, this.interceptorWithMultiplier(1.0).computeTargetWindow(THRESHOLD));
	}

	@Test
	@DisplayName("multiplier=1.0, depth = 1.5× threshold → half the window")
	void testInsideRampHalfWindow() {
		// (150/100 - 1) × 1.0 = 0.5 → 1000 × 0.5 = 500
		Assertions.assertEquals(500, this.interceptorWithMultiplier(1.0).computeTargetWindow(150L));
	}

	@Test
	@DisplayName("multiplier=1.0, depth = 2× threshold → full window (reaches cap)")
	void testReachesCapAtTwiceThreshold() {
		// (200/100 - 1) × 1.0 = 1.0 → capped at maxCredits
		Assertions.assertEquals(MAX_CREDITS, this.interceptorWithMultiplier(1.0).computeTargetWindow(200L));
	}

	@Test
	@DisplayName("very deep queue → target window capped at maxCredits")
	void testVeryDeepQueueCappedAtMaxCredits() {
		Assertions.assertEquals(MAX_CREDITS, this.interceptorWithMultiplier(2.0).computeTargetWindow(2_000_000L));
	}

	@Test
	@DisplayName("larger multiplier reaches the full window at a shallower depth")
	void testLargerMultiplierRampsFaster() {
		// multiplier=2.0, depth=1.25× threshold: (0.25) × 2.0 = 0.5 → 500
		Assertions.assertEquals(500, this.interceptorWithMultiplier(2.0).computeTargetWindow(125L));
		// multiplier=0.5, same depth: (0.25) × 0.5 = 0.125 → 125
		Assertions.assertEquals(125, this.interceptorWithMultiplier(0.5).computeTargetWindow(125L));
	}

	@Test
	@DisplayName("target window is monotonically non-decreasing in depth and never exceeds maxCredits")
	void testMonotonicAndBounded() {
		final DynamicCreditClientInterceptor interceptor = this.interceptorWithMultiplier(1.0);
		int previousWindow = 0;
		for (final long depth : new long[] { 0, 50, 100, 110, 150, 199, 200, 500, 10_000 }) {
			final int window = interceptor.computeTargetWindow(depth);
			Assertions.assertTrue(window >= previousWindow, "window must not shrink as depth grows, at depth " + depth);
			Assertions.assertTrue(window <= MAX_CREDITS, "window must never exceed maxCredits, at depth " + depth);
			previousWindow = window;
		}
	}
}
