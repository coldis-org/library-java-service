package org.coldis.library.service.warmup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the idle JIT decision with fixed readings of the JIT's total compilation time, at the shipped threshold of 100
 * ms of compilation per second.
 */
public class ServiceWarmUpCompilationIdlenessTest {

	/**
	 * One second, in nanoseconds.
	 */
	private static final long SECOND_NANOS = 1_000_000_000L;

	/**
	 * Compilation time per second, in milliseconds, under which the JIT counts as idle.
	 */
	private static final long IDLE_COMPILATION_MILLIS_PER_SECOND = 100L;

	/**
	 * Creates the decision, started at time zero with no compilation.
	 *
	 * @param  requiredIdleChecks Consecutive idle checks that stop the warm-ups.
	 * @return                    The decision.
	 */
	private static ServiceWarmUpCompilationIdleness createIdleness(
			final long requiredIdleChecks) {
		return new ServiceWarmUpCompilationIdleness(ServiceWarmUpCompilationIdlenessTest.IDLE_COMPILATION_MILLIS_PER_SECOND, requiredIdleChecks, 0L, 0L);
	}

	/**
	 * Tests that three idle seconds in a row stop the warm-ups, and two do not.
	 */
	@Test
	@DisplayName("Three idle seconds in a row stop the warm-ups; two do not")
	public void testIdleForRequiredChecks() {
		final ServiceWarmUpCompilationIdleness idleness = ServiceWarmUpCompilationIdlenessTest.createIdleness(3L);
		idleness.recordCheck(50L, ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		idleness.recordCheck(100L, 2L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertFalse(idleness.isIdleLongEnough());
		idleness.recordCheck(150L, 3L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertTrue(idleness.isIdleLongEnough());
	}

	/**
	 * Tests that a busy second between idle ones restarts the count.
	 */
	@Test
	@DisplayName("A busy second between idle ones restarts the count")
	public void testBusyCheckRestartsCount() {
		final ServiceWarmUpCompilationIdleness idleness = ServiceWarmUpCompilationIdlenessTest.createIdleness(3L);
		idleness.recordCheck(50L, ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		idleness.recordCheck(100L, 2L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		// 150 ms of compilation in one second: busy.
		idleness.recordCheck(250L, 3L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		idleness.recordCheck(300L, 4L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		idleness.recordCheck(350L, 5L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertFalse(idleness.isIdleLongEnough());
		idleness.recordCheck(400L, 6L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertTrue(idleness.isIdleLongEnough());
	}

	/**
	 * Tests that the rate divides the compilation time by the time elapsed since the previous check.
	 */
	@Test
	@DisplayName("The rate is the compilation time over the time elapsed since the previous check")
	public void testRateOverElapsedTime() {
		final ServiceWarmUpCompilationIdleness idleness = ServiceWarmUpCompilationIdlenessTest.createIdleness(1L);
		// 150 ms of compilation in two seconds: 75 ms per second, idle.
		idleness.recordCheck(150L, 2L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertTrue(idleness.isIdleLongEnough());
		// 150 ms more in one second: busy.
		idleness.recordCheck(300L, 3L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertFalse(idleness.isIdleLongEnough());
	}

	/**
	 * Tests that idle seconds count only once every running warm-up made the minimum of calls.
	 */
	@Test
	@DisplayName("Idle seconds count only once every running warm-up made the minimum of calls")
	public void testCountsOnlyAfterMinimumCalls() {
		final ServiceWarmUpCompilationIdleness idleness = ServiceWarmUpCompilationIdlenessTest.createIdleness(3L);
		idleness.recordCheck(10L, ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, false);
		idleness.recordCheck(20L, 2L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, false);
		idleness.recordCheck(30L, 3L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, false);
		Assertions.assertFalse(idleness.isIdleLongEnough());
		idleness.recordCheck(40L, 4L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		idleness.recordCheck(50L, 5L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertFalse(idleness.isIdleLongEnough());
		idleness.recordCheck(60L, 6L * ServiceWarmUpCompilationIdlenessTest.SECOND_NANOS, true);
		Assertions.assertTrue(idleness.isIdleLongEnough());
	}

}
