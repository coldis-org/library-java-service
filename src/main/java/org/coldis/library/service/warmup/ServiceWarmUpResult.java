package org.coldis.library.service.warmup;

/**
 * Result of one warm-up.
 */
public class ServiceWarmUpResult {

	/**
	 * Warm-up name (its class name).
	 */
	private final String name;

	/**
	 * Number of calls.
	 */
	private final Long callCount;

	/**
	 * Number of failed calls.
	 */
	private final Long failedCallCount;

	/**
	 * Median duration of the first calls, in milliseconds, or null without calls.
	 */
	private final Double firstCallsMedianMillis;

	/**
	 * Median duration of the last calls, in milliseconds, or null without calls.
	 */
	private final Double lastCallsMedianMillis;

	/**
	 * Why the warm-up stopped.
	 */
	private final ServiceWarmUpStopReason stopReason;

	/**
	 * First error, or null without errors.
	 */
	private final Throwable firstError;

	/**
	 * Constructor.
	 *
	 * @param name                   Warm-up name.
	 * @param callCount              Number of calls.
	 * @param failedCallCount        Number of failed calls.
	 * @param firstCallsMedianMillis Median duration of the first calls, in milliseconds.
	 * @param lastCallsMedianMillis  Median duration of the last calls, in milliseconds.
	 * @param stopReason             Why the warm-up stopped.
	 * @param firstError             First error.
	 */
	public ServiceWarmUpResult(
			final String name,
			final Long callCount,
			final Long failedCallCount,
			final Double firstCallsMedianMillis,
			final Double lastCallsMedianMillis,
			final ServiceWarmUpStopReason stopReason,
			final Throwable firstError) {
		super();
		this.name = name;
		this.callCount = callCount;
		this.failedCallCount = failedCallCount;
		this.firstCallsMedianMillis = firstCallsMedianMillis;
		this.lastCallsMedianMillis = lastCallsMedianMillis;
		this.stopReason = stopReason;
		this.firstError = firstError;
	}

	/**
	 * Gets the warm-up name.
	 *
	 * @return The warm-up name.
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Gets the number of calls.
	 *
	 * @return The number of calls.
	 */
	public Long getCallCount() {
		return this.callCount;
	}

	/**
	 * Gets the number of failed calls.
	 *
	 * @return The number of failed calls.
	 */
	public Long getFailedCallCount() {
		return this.failedCallCount;
	}

	/**
	 * Gets the median duration of the first calls, in milliseconds.
	 *
	 * @return The median duration of the first calls, or null without calls.
	 */
	public Double getFirstCallsMedianMillis() {
		return this.firstCallsMedianMillis;
	}

	/**
	 * Gets the median duration of the last calls, in milliseconds.
	 *
	 * @return The median duration of the last calls, or null without calls.
	 */
	public Double getLastCallsMedianMillis() {
		return this.lastCallsMedianMillis;
	}

	/**
	 * Gets why the warm-up stopped.
	 *
	 * @return Why the warm-up stopped.
	 */
	public ServiceWarmUpStopReason getStopReason() {
		return this.stopReason;
	}

	/**
	 * Gets the first error.
	 *
	 * @return The first error, or null without errors.
	 */
	public Throwable getFirstError() {
		return this.firstError;
	}

}
