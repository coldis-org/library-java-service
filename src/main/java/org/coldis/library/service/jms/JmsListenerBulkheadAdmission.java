package org.coldis.library.service.jms;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A consumer's pass through the gate of a {@link JmsListenerBulkhead}: it may
 * receive one message. When the permit was taken at the gate
 * ({@link #hasPermit()}), the handler of the received message runs on it
 * ({@link #takePermit()}); otherwise the handler acquires its own. Closing the
 * admission releases a permit taken at the gate that no handler took (no
 * message, or the message was not handled); closing again does nothing.
 */
public final class JmsListenerBulkheadAdmission implements AutoCloseable {

	/**
	 * Bulkhead the admission belongs to.
	 */
	private final JmsListenerBulkhead bulkhead;

	/**
	 * Reservation (null for unreserved).
	 */
	private final String reservation;

	/**
	 * If the permit was taken at the gate.
	 */
	private final boolean hasPermit;

	/**
	 * How long the consumer waited at the gate.
	 */
	private final long waitNanos;

	/**
	 * If the permit taken at the gate was handed to a handler or released.
	 */
	private final AtomicBoolean isPermitHandedOver = new AtomicBoolean();

	/**
	 * Constructor.
	 *
	 * @param bulkhead    Bulkhead.
	 * @param reservation Reservation (null for unreserved).
	 * @param hasPermit   If the permit was taken at the gate.
	 * @param waitNanos   How long the consumer waited at the gate.
	 */
	JmsListenerBulkheadAdmission(final JmsListenerBulkhead bulkhead, final String reservation, final boolean hasPermit, final long waitNanos) {
		this.bulkhead = bulkhead;
		this.reservation = reservation;
		this.hasPermit = hasPermit;
		this.waitNanos = waitNanos;
	}

	/**
	 * Gets the bulkhead.
	 *
	 * @return The bulkhead.
	 */
	public JmsListenerBulkhead getBulkhead() {
		return this.bulkhead;
	}

	/**
	 * If the permit was taken at the gate.
	 *
	 * @return If the permit was taken at the gate.
	 */
	public boolean hasPermit() {
		return this.hasPermit;
	}

	/**
	 * Gets how long the consumer waited at the gate.
	 *
	 * @return The wait (nanoseconds, 0 when the consumer passed at once).
	 */
	public long getWaitNanos() {
		return this.waitNanos;
	}

	/**
	 * Hands the permit taken at the gate to the handler of the received message
	 * (once).
	 *
	 * @return The permit, to be closed when the handler ends, or null when no
	 *         permit was taken at the gate or it was already handed over or
	 *         released.
	 */
	public JmsListenerBulkheadPermit takePermit() {
		return (this.hasPermit && this.isPermitHandedOver.compareAndSet(false, true)
				? new JmsListenerBulkheadPermit(this.bulkhead, this.reservation, 0L)
				: null);
	}

	/**
	 * Releases the permit taken at the gate unless a handler took it (once).
	 *
	 * @see java.lang.AutoCloseable#close()
	 */
	@Override
	public void close() {
		if (this.hasPermit && this.isPermitHandedOver.compareAndSet(false, true)) {
			this.bulkhead.release(this.reservation);
		}
	}

}
