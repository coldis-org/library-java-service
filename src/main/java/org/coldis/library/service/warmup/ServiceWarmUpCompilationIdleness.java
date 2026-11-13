package org.coldis.library.service.warmup;

/**
 * Decides when the JIT has stayed idle long enough to stop the warm-ups. Each check turns the JIT's total compilation
 * time into a rate since the previous check, and counts the consecutive checks under the threshold, but only once
 * every running warm-up made the minimum of calls; a busy check, or one before the minimum, restarts the count.
 */
final class ServiceWarmUpCompilationIdleness {

	/**
	 * Nanoseconds per millisecond.
	 */
	private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;

	/**
	 * Milliseconds per second.
	 */
	private static final long MILLISECONDS_PER_SECOND = 1_000L;

	/**
	 * Compilation time per second, in milliseconds, under which the JIT counts as idle.
	 */
	private final long idleCompilationMillisPerSecond;

	/**
	 * Consecutive idle checks that stop the warm-ups.
	 */
	private final long requiredIdleChecks;

	/**
	 * Total compilation time at the previous check, in milliseconds.
	 */
	private long previousCompilationMillis;

	/**
	 * Time of the previous check, from {@link System#nanoTime()}.
	 */
	private long previousCheckNanos;

	/**
	 * Consecutive idle checks.
	 */
	private long idleChecks;

	/**
	 * Constructor.
	 *
	 * @param idleCompilationMillisPerSecond Compilation time per second, in milliseconds, under which the JIT counts as
	 *                                           idle.
	 * @param requiredIdleChecks             Consecutive idle checks that stop the warm-ups.
	 * @param startCompilationMillis         Total compilation time at the start, in milliseconds.
	 * @param startNanos                     Start, from {@link System#nanoTime()}.
	 */
	ServiceWarmUpCompilationIdleness(
			final long idleCompilationMillisPerSecond,
			final long requiredIdleChecks,
			final long startCompilationMillis,
			final long startNanos) {
		super();
		this.idleCompilationMillisPerSecond = idleCompilationMillisPerSecond;
		this.requiredIdleChecks = requiredIdleChecks;
		this.previousCompilationMillis = startCompilationMillis;
		this.previousCheckNanos = startNanos;
	}

	/**
	 * Records a check.
	 *
	 * @param compilationMillis    Total compilation time of the JIT, in milliseconds.
	 * @param checkNanos           Time of the check, from {@link System#nanoTime()}.
	 * @param haveMadeMinimumCalls Whether every running warm-up made the minimum of calls.
	 */
	void recordCheck(
			final long compilationMillis,
			final long checkNanos,
			final boolean haveMadeMinimumCalls) {
		final long elapsedMillis = Math.max(1L, (checkNanos - this.previousCheckNanos) / ServiceWarmUpCompilationIdleness.NANOSECONDS_PER_MILLISECOND);
		final long compilationMillisPerSecond = ((compilationMillis - this.previousCompilationMillis) * ServiceWarmUpCompilationIdleness.MILLISECONDS_PER_SECOND)
				/ elapsedMillis;
		this.idleChecks = (haveMadeMinimumCalls && (compilationMillisPerSecond < this.idleCompilationMillisPerSecond) ? this.idleChecks + 1 : 0L);
		this.previousCompilationMillis = compilationMillis;
		this.previousCheckNanos = checkNanos;
	}

	/**
	 * Gets whether the JIT stayed idle for the required consecutive checks.
	 *
	 * @return Whether the JIT stayed idle for the required consecutive checks.
	 */
	boolean isIdleLongEnough() {
		return this.idleChecks >= this.requiredIdleChecks;
	}

}
