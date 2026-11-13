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
	 * The JIT stopped compiling after every running warm-up made the minimum of calls.
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
	INTERRUPTED

}
