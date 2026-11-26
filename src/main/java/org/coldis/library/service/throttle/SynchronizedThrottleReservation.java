package org.coldis.library.service.throttle;

/**
 * A run acquired from a {@link SynchronizedThrottle}, which
 * {@link SynchronizedThrottle#release(SynchronizedThrottleReservation)} gives
 * back when the run did not happen. Only the throttle creates reservations, so
 * a caller cannot build one that resets a key to an arbitrary earlier state.
 */
public final class SynchronizedThrottleReservation {

	/**
	 * Throttle name.
	 */
	private final String throttleName;

	/**
	 * Key (within the throttle).
	 */
	private final String key;

	/**
	 * Throttle key (throttle name and key, as stored).
	 */
	private final String throttleKey;

	/**
	 * When the run was acquired (wall-clock epoch millis).
	 */
	private final long acquiredAt;

	/**
	 * When the key may run again (wall-clock epoch millis).
	 */
	private final long nextRunAfter;

	/**
	 * The key's local run before this one (null when there was none), restored
	 * by a release.
	 */
	private final SynchronizedThrottle.LocalRun previousRun;

	/**
	 * Creates a reservation (only the throttle does).
	 *
	 * @param throttleName Throttle name.
	 * @param key          Key (within the throttle).
	 * @param throttleKey  Throttle key (throttle name and key, as stored).
	 * @param acquiredAt   When the run was acquired (wall-clock epoch millis).
	 * @param nextRunAfter When the key may run again (wall-clock epoch millis).
	 * @param previousRun  The key's local run before this one (null when there
	 *                         was none).
	 */
	SynchronizedThrottleReservation(
			final String throttleName,
			final String key,
			final String throttleKey,
			final long acquiredAt,
			final long nextRunAfter,
			final SynchronizedThrottle.LocalRun previousRun) {
		this.throttleName = throttleName;
		this.key = key;
		this.throttleKey = throttleKey;
		this.acquiredAt = acquiredAt;
		this.nextRunAfter = nextRunAfter;
		this.previousRun = previousRun;
	}

	/**
	 * Gets the throttle name.
	 *
	 * @return The throttle name.
	 */
	public String getThrottleName() {
		return this.throttleName;
	}

	/**
	 * Gets the key (within the throttle).
	 *
	 * @return The key.
	 */
	public String getKey() {
		return this.key;
	}

	/**
	 * Gets when the run was acquired.
	 *
	 * @return When the run was acquired (wall-clock epoch millis).
	 */
	public long getAcquiredAt() {
		return this.acquiredAt;
	}

	/**
	 * Gets when the key may run again.
	 *
	 * @return When the key may run again (wall-clock epoch millis).
	 */
	public long getNextRunAfter() {
		return this.nextRunAfter;
	}

	/**
	 * Gets the throttle key (throttle name and key, as stored).
	 *
	 * @return The throttle key.
	 */
	String getThrottleKey() {
		return this.throttleKey;
	}

	/**
	 * Gets the key's local run before this one.
	 *
	 * @return The key's local run before this one (null when there was none).
	 */
	SynchronizedThrottle.LocalRun getPreviousRun() {
		return this.previousRun;
	}

}
