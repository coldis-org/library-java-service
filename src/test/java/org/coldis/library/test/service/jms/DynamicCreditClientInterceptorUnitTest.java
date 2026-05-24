package org.coldis.library.test.service.jms;

import org.coldis.library.service.jms.DynamicCreditClientInterceptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DynamicCreditClientInterceptor#computeGranted}.
 * No Spring context or broker required.
 */
@DisplayName("DynamicCreditClientInterceptor — computeGranted logic")
class DynamicCreditClientInterceptorUnitTest {

	private static final long THRESHOLD = 100L;
	private static final double MULTIPLIER = 2.0;
	private static final int MAX_CREDITS = 10 * 1024 * 1024;
	private static final int REQUESTED = 500;

	private DynamicCreditClientInterceptor interceptor;

	@BeforeEach
	void setUp() {
		this.interceptor = new DynamicCreditClientInterceptor(null, THRESHOLD, MULTIPLIER, MAX_CREDITS, 5000L);
	}

	@Test
	@DisplayName("depth=0 → returns requested unchanged")
	void testAtZeroDepthReturnsRequested() {
		Assertions.assertEquals(REQUESTED, this.interceptor.computeGranted(0L, REQUESTED));
	}

	@Test
	@DisplayName("depth < threshold → returns requested unchanged")
	void testBelowThresholdReturnsRequested() {
		Assertions.assertEquals(REQUESTED, this.interceptor.computeGranted(THRESHOLD - 1, REQUESTED));
	}

	@Test
	@DisplayName("depth == threshold → returns requested unchanged (boundary)")
	void testAtThresholdReturnsRequested() {
		Assertions.assertEquals(REQUESTED, this.interceptor.computeGranted(THRESHOLD, REQUESTED));
	}

	@Test
	@DisplayName("depth = 2× threshold → returns requested × scale × multiplier")
	void testAtTwiceThresholdReturnsScaled() {
		// scale = (200/100) × 2 = 4  →  ceil(500 × 4) = 2000
		Assertions.assertEquals(2000, this.interceptor.computeGranted(THRESHOLD * 2, REQUESTED));
	}

	@Test
	@DisplayName("depth = 10× threshold → scales proportionally")
	void testAtTenTimesThresholdReturnsScaled() {
		// scale = (1000/100) × 2 = 20  →  ceil(500 × 20) = 10 000
		Assertions.assertEquals(10_000, this.interceptor.computeGranted(THRESHOLD * 10, REQUESTED));
	}

	@Test
	@DisplayName("very deep queue → computeGranted returns uncapped scaled value; cap is enforced by handleFlowCredit headroom")
	void testVeryDeepQueueReturnsScaledUncapped() {
		// computeGranted no longer applies the maxCredits cap — that is the caller's
		// responsibility via the outstanding-credit headroom.
		// ceil(500 × (2_000_000/100) × 2.0) = 20_000_000
		Assertions.assertEquals(20_000_000, this.interceptor.computeGranted(2_000_000L, REQUESTED));
	}

	@Test
	@DisplayName("grant is never less than requested at any depth")
	void testGrantIsNeverLessThanRequested() {
		for (final long depth : new long[] { 0, 1, 50, 100, 101, 500, 10_000 }) {
			Assertions.assertTrue(
					this.interceptor.computeGranted(depth, 1) >= 1,
					"grant must be >= requested at depth " + depth);
		}
	}

	@Test
	@DisplayName("multiplier=1.0 → linear growth with depth")
	void testMultiplierOneLinearGrowth() {
		final DynamicCreditClientInterceptor linear =
				new DynamicCreditClientInterceptor(null, THRESHOLD, 1.0, MAX_CREDITS, 5000L);
		// depth=200 → scale=2 → grant=1000
		Assertions.assertEquals(1000, linear.computeGranted(200L, REQUESTED));
		// depth=500 → scale=5 → grant=2500
		Assertions.assertEquals(2500, linear.computeGranted(500L, REQUESTED));
	}

	@Test
	@DisplayName("multiplier < 1 and low depth above threshold → grant clamped to requested")
	void testMultiplierLessThanOneClampedToRequested() {
		// multiplier=0.5, depth=150: scale = 1.5 × 0.5 = 0.75 → raw = 375 < requested → clamp to 500
		final DynamicCreditClientInterceptor slow =
				new DynamicCreditClientInterceptor(null, THRESHOLD, 0.5, MAX_CREDITS, 5000L);
		Assertions.assertEquals(REQUESTED, slow.computeGranted(150L, REQUESTED));
	}

	@Test
	@DisplayName("multiplier < 1 and high depth → grant exceeds requested once scale passes 1")
	void testMultiplierLessThanOneHighDepthExceedsRequested() {
		// multiplier=0.5, depth=400: scale = 4 × 0.5 = 2 → grant = 1000 > requested(500)
		final DynamicCreditClientInterceptor slow =
				new DynamicCreditClientInterceptor(null, THRESHOLD, 0.5, MAX_CREDITS, 5000L);
		Assertions.assertEquals(1000, slow.computeGranted(400L, REQUESTED));
	}
}
