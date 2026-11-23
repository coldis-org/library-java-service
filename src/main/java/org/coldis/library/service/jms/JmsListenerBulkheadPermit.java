package org.coldis.library.service.jms;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A permit of a {@link JmsListenerBulkhead}: one handler execution. Closing it
 * releases it (closing again does nothing).
 */
public final class JmsListenerBulkheadPermit implements AutoCloseable {

	/**
	 * Bulkhead the permit belongs to.
	 */
	private final JmsListenerBulkhead bulkhead;

	/**
	 * Reservation the permit was counted in (null for unreserved).
	 */
	private final String reservation;

	/**
	 * How long the listener waited for the permit.
	 */
	private final long waitNanos;

	/**
	 * If the permit was released.
	 */
	private final AtomicBoolean isReleased = new AtomicBoolean();

	/**
	 * Constructor.
	 *
	 * @param bulkhead    Bulkhead.
	 * @param reservation Reservation (null for unreserved).
	 * @param waitNanos   How long the listener waited.
	 */
	JmsListenerBulkheadPermit(final JmsListenerBulkhead bulkhead, final String reservation, final long waitNanos) {
		this.bulkhead = bulkhead;
		this.reservation = reservation;
		this.waitNanos = waitNanos;
	}

	/**
	 * Gets the reservation the permit was counted in.
	 *
	 * @return The reservation (null for unreserved).
	 */
	String getReservation() {
		return this.reservation;
	}

	/**
	 * Gets how long the listener waited for the permit.
	 *
	 * @return The wait (nanoseconds, 0 when the permit was immediate).
	 */
	public long getWaitNanos() {
		return this.waitNanos;
	}

	/**
	 * If the listener waited for the permit.
	 *
	 * @return If the listener waited for the permit.
	 */
	public boolean hasWaited() {
		return this.waitNanos > 0;
	}

	/**
	 * Releases the permit (once).
	 *
	 * @see java.lang.AutoCloseable#close()
	 */
	@Override
	public void close() {
		if (this.isReleased.compareAndSet(false, true)) {
			this.bulkhead.release(this);
		}
	}

}
