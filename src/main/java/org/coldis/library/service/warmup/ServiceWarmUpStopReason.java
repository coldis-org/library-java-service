package org.coldis.library.service.warmup;

/**
 * Why a warm-up stopped.
 */
public enum ServiceWarmUpStopReason {

	/**
	 * Every input was used.
	 */
	INPUTS_EXHAUSTED,

	/**
	 * The JIT stayed idle for the idle period after every running warm-up made the minimum of calls.
	 */
	COMPILATION_IDLE,

	/**
	 * The time limit was reached.
	 */
	TIME_LIMIT,

	/**
	 * Too many consecutive calls failed.
	 */
	ERRORS,

	/**
	 * The inputs could not be read.
	 */
	INPUTS_FAILED,

	/**
	 * The warm-up was interrupted, because the service is stopping.
	 */
	INTERRUPTED,

	/**
	 * Every call thread ended on an error the JVM cannot recover from, before the warm-up stopped.
	 */
	CALL_THREADS_FAILED

}
