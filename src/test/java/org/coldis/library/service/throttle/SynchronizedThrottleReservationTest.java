package org.coldis.library.service.throttle;

import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Synchronized throttle reservation test (in the throttle's package, to read
 * the history a reservation keeps).
 */
@DisplayName("Synchronized throttle reservation")
public class SynchronizedThrottleReservationTest {

	/**
	 * Throttle name used by the tests.
	 */
	private static final String THROTTLE_NAME = "reservationTestThrottle";

	/**
	 * Most time to wait for a 1 ms interval to pass (millis).
	 */
	private static final long MAXIMUM_WAIT_MILLIS = 5_000;

	/**
	 * Acquires a key, retrying until its interval passed (bounded).
	 *
	 * @param  throttle Throttle.
	 * @param  key      Key.
	 * @param  interval Interval.
	 * @return          The reservation, or null when the key could not be
	 *                  acquired in time.
	 */
	private static SynchronizedThrottleReservation acquireWhenAllowed(
			final SynchronizedThrottle throttle,
			final String key,
			final Duration interval) {
		final long deadline = SynchronizedThrottle.getCurrentWallClockTimestamp() + SynchronizedThrottleReservationTest.MAXIMUM_WAIT_MILLIS;
		SynchronizedThrottleReservation reservation = null;
		while ((reservation == null) && (SynchronizedThrottle.getCurrentWallClockTimestamp() < deadline)) {
			reservation = throttle.tryAcquire(SynchronizedThrottleReservationTest.THROTTLE_NAME, key, interval);
		}
		return reservation;
	}

	/**
	 * Tests that a reservation keeps one step of history: its previous run holds
	 * no reservation, so a busy key does not chain every earlier run.
	 */
	@Test
	@DisplayName("A reservation keeps one step of history")
	public void testOneStepHistory() {
		final SynchronizedThrottleProperties properties = new SynchronizedThrottleProperties();
		properties.setPersistenceEnabled(false);
		final SynchronizedThrottle throttle = new SynchronizedThrottle(properties, null);
		final String key = "historyKey";
		final Duration interval = Duration.ofMillis(1);
		SynchronizedThrottleReservation lastReservation = throttle.tryAcquire(SynchronizedThrottleReservationTest.THROTTLE_NAME, key, interval);
		Assertions.assertNotNull(lastReservation);
		Assertions.assertNull(lastReservation.getPreviousRun());

		// Acquires the key again and again (each time its 1 ms interval passed).
		SynchronizedThrottleReservation earlierReservation = null;
		for (int acquisition = 0; acquisition < 50; acquisition++) {
			final SynchronizedThrottleReservation reservation = SynchronizedThrottleReservationTest.acquireWhenAllowed(throttle, key, interval);
			Assertions.assertNotNull(reservation);
			Assertions.assertNotNull(reservation.getPreviousRun());
			Assertions.assertEquals(lastReservation.getAcquiredAt(), reservation.getPreviousRun().lastRunAt());
			Assertions.assertNull(reservation.getPreviousRun().reservation());
			earlierReservation = lastReservation;
			lastReservation = reservation;
		}

		// Releasing the last run restores the previous one without its reservation.
		Assertions.assertTrue(throttle.release(lastReservation));
		Assertions.assertFalse(throttle.release(earlierReservation));
	}

}
