package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds the concurrent handler executions of one JMS listener bulkhead group.
 * Listeners that received a message wait for a permit in arrival order (FIFO):
 * a release hands the permit straight to the oldest admissible waiter, so a new
 * arrival never overtakes a queued one.
 * <p>
 * Reserved permits ({@code reservation → permits}) may only be used by the
 * listeners of that reservation, which may also use the shared permits (the
 * limit minus every reservation). Reserved permits are strict: an idle reserved
 * permit is not lent. The limit is read from a supplier, so it can change at
 * runtime; it is never below the reserved permits plus one shared permit.
 * <p>
 * A waiter notices a container stop, its maximum wait and a changed limit
 * within {@link #STATE_CHECK_INTERVAL}, without losing its queue position.
 * Only {@link ReentrantLock} and {@link Condition} are used (no
 * {@code synchronized}), so waiting does not pin a virtual thread. No lock is
 * held while the handler runs.
 * <p>
 * An interrupted waiter ends with {@code INTERRUPTED} and the interrupt flag
 * cleared, not restored: the interrupt is the container shutdown's, and the
 * session rollback that follows must not run on an interrupted thread. A caller
 * outside a listener container that relies on the flag must restore it.
 * <p>
 * No Spring, JMS or metrics dependency: see {@link JmsListenerBulkheadRegistry}
 * and {@link JmsListenerBulkheadBinding} for the wiring.
 */
public class JmsListenerBulkhead {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JmsListenerBulkhead.class);

	/**
	 * Longest time a waiter sleeps before checking the container state, its
	 * maximum wait and the limit.
	 */
	public static final Duration STATE_CHECK_INTERVAL = Duration.ofMillis(250);

	/**
	 * A listener waiting for a permit. Guarded by the bulkhead lock.
	 */
	private static final class Waiter {

		/**
		 * Reservation (null for unreserved).
		 */
		private final String reservation;

		/**
		 * Condition the waiter awaits (one per waiter, so a grant wakes exactly the
		 * granted waiter).
		 */
		private final Condition condition;

		/**
		 * If the permit was granted (and counted).
		 */
		private boolean isGranted;

		/**
		 * Constructor.
		 *
		 * @param reservation Reservation.
		 * @param condition   Condition.
		 */
		private Waiter(final String reservation, final Condition condition) {
			this.reservation = reservation;
			this.condition = condition;
		}

	}

	/**
	 * Group name.
	 */
	private final String group;

	/**
	 * Supplier of the configured limit.
	 */
	private final IntSupplier maxInFlightSupplier;

	/**
	 * Reserved permits, by reservation.
	 */
	private final Map<String, Integer> reservedPermits;

	/**
	 * Sum of the reserved permits.
	 */
	private final int totalReservedPermits;

	/**
	 * In-flight handlers of each reservation (reserved and shared permits).
	 * Guarded by the lock.
	 */
	private final Map<String, Integer> reservedInFlight;

	/**
	 * Lock.
	 */
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Waiters, in arrival order. Guarded by the lock.
	 */
	private final Set<Waiter> waiters = new LinkedHashSet<>();

	/**
	 * Queued waiters that have a reservation. Guarded by the lock.
	 */
	private int queuedReservedWaiters;

	/**
	 * Shared permits in use (unreserved handlers plus reserved handlers beyond
	 * their reservation). Guarded by the lock.
	 */
	private int sharedInUse;

	/**
	 * Permits in use. Guarded by the lock.
	 */
	private int inFlight;

	/**
	 * Current limit (never below the reserved permits plus one). Guarded by the
	 * lock.
	 */
	private int maxInFlight;

	/**
	 * Last configured limit seen (to warn once per change). Guarded by the lock.
	 */
	private int lastConfiguredMaxInFlight;

	/**
	 * Constructor.
	 *
	 * @param group               Group name.
	 * @param maxInFlightSupplier Supplier of the limit, read on every acquire and
	 *                                release and by waiters (so it can change at
	 *                                runtime).
	 * @param reservedPermits     Reserved permits by reservation (each at least
	 *                                1; may be null).
	 */
	public JmsListenerBulkhead(final String group, final IntSupplier maxInFlightSupplier, final Map<String, Integer> reservedPermits) {
		this.group = Objects.requireNonNull(group);
		this.maxInFlightSupplier = Objects.requireNonNull(maxInFlightSupplier);
		final Map<String, Integer> actualReservedPermits = new LinkedHashMap<>(reservedPermits == null ? Map.of() : reservedPermits);
		final Map<String, Integer> actualReservedInFlight = new HashMap<>();
		int actualTotalReservedPermits = 0;
		for (final Map.Entry<String, Integer> reservation : actualReservedPermits.entrySet()) {
			if ((reservation.getValue() == null) || (reservation.getValue() < 1)) {
				throw new IllegalArgumentException("Reservation '" + reservation.getKey() + "' of JMS listener bulkhead group '" + group
						+ "' must have at least 1 permit.");
			}
			actualTotalReservedPermits += reservation.getValue();
			actualReservedInFlight.put(reservation.getKey(), 0);
		}
		this.reservedPermits = Collections.unmodifiableMap(actualReservedPermits);
		this.reservedInFlight = actualReservedInFlight;
		this.totalReservedPermits = actualTotalReservedPermits;
		this.lastConfiguredMaxInFlight = Integer.MIN_VALUE;
		this.refreshMaxInFlight();
	}

	/**
	 * Reads the configured limit and applies it, clamped to the reserved permits
	 * plus one (with one warning per configured value below that). Under the
	 * lock (or in the constructor).
	 *
	 * @return If the limit changed.
	 */
	private boolean refreshMaxInFlight() {
		final int configuredMaxInFlight = this.maxInFlightSupplier.getAsInt();
		final int minimumMaxInFlight = this.totalReservedPermits + 1;
		final int actualMaxInFlight = Math.max(configuredMaxInFlight, minimumMaxInFlight);
		if ((configuredMaxInFlight != this.lastConfiguredMaxInFlight) && (configuredMaxInFlight < minimumMaxInFlight)) {
			JmsListenerBulkhead.LOGGER.warn("JMS listener bulkhead group '{}' limit {} is below its reserved permits plus one shared permit; using {}.",
					this.group, configuredMaxInFlight, minimumMaxInFlight);
		}
		this.lastConfiguredMaxInFlight = configuredMaxInFlight;
		final boolean hasChanged = (actualMaxInFlight != this.maxInFlight);
		this.maxInFlight = actualMaxInFlight;
		return hasChanged;
	}

	/**
	 * Gets the shared capacity (the limit minus the reserved permits). Under the
	 * lock.
	 *
	 * @return The shared capacity.
	 */
	private int getSharedCapacity() {
		return this.maxInFlight - this.totalReservedPermits;
	}

	/**
	 * If a reserved permit of the reservation is free. Under the lock.
	 *
	 * @param  reservation Reservation (null for unreserved).
	 * @return             If a reserved permit of the reservation is free.
	 */
	private boolean hasFreeReservedSlot(
			final String reservation) {
		return (reservation != null) && (this.reservedInFlight.get(reservation) < this.reservedPermits.get(reservation));
	}

	/**
	 * If a request of the reservation may get a permit now: a free reserved
	 * permit of its reservation, or a free shared permit. Under the lock.
	 *
	 * @param  reservation Reservation (null for unreserved).
	 * @return             If the request is admissible.
	 */
	private boolean isAdmissible(
			final String reservation) {
		return this.hasFreeReservedSlot(reservation) || (this.sharedInUse < this.getSharedCapacity());
	}

	/**
	 * Counts a permit in flight: a reserved permit when one is free, otherwise a
	 * shared one. Under the lock.
	 *
	 * @param reservation Reservation (null for unreserved).
	 */
	private void countInFlight(
			final String reservation) {
		if (reservation == null) {
			this.sharedInUse++;
		}
		else {
			final int reservationInFlight = this.reservedInFlight.get(reservation);
			if (reservationInFlight >= this.reservedPermits.get(reservation)) {
				this.sharedInUse++;
			}
			this.reservedInFlight.put(reservation, reservationInFlight + 1);
		}
		this.inFlight++;
	}

	/**
	 * Uncounts a permit in flight: a shared permit while the reservation still
	 * uses shared permits, otherwise a reserved one. Under the lock.
	 *
	 * @param reservation Reservation (null for unreserved).
	 */
	private void uncountInFlight(
			final String reservation) {
		if (reservation == null) {
			this.sharedInUse--;
		}
		else {
			final int reservationInFlight = this.reservedInFlight.get(reservation) - 1;
			if (reservationInFlight >= this.reservedPermits.get(reservation)) {
				this.sharedInUse--;
			}
			this.reservedInFlight.put(reservation, reservationInFlight);
		}
		this.inFlight--;
	}

	/**
	 * Removes a waiter from the queue. Under the lock.
	 *
	 * @param waiter Waiter.
	 */
	private void dequeue(
			final Waiter waiter) {
		if (this.waiters.remove(waiter) && (waiter.reservation != null)) {
			this.queuedReservedWaiters--;
		}
	}

	/**
	 * Grants permits to the queued waiters, oldest first, while there is
	 * capacity: a waiter gets a free reserved permit of its reservation or a free
	 * shared one. The permit is counted at grant time, so capacity is never
	 * booked twice between the signal and the wake-up. Stops once the shared
	 * permits are exhausted and no queued waiter has a reservation. Under the
	 * lock.
	 */
	private void grantWaiters() {
		final Iterator<Waiter> waitersIterator = this.waiters.iterator();
		while (waitersIterator.hasNext() && ((this.sharedInUse < this.getSharedCapacity()) || (this.queuedReservedWaiters > 0))) {
			final Waiter waiter = waitersIterator.next();
			if (this.isAdmissible(waiter.reservation)) {
				waitersIterator.remove();
				if (waiter.reservation != null) {
					this.queuedReservedWaiters--;
				}
				this.countInFlight(waiter.reservation);
				waiter.isGranted = true;
				waiter.condition.signal();
			}
		}
	}

	/**
	 * After a waiter woke up without a permit: resolves whether its wait must end
	 * (the container stopped or the maximum wait elapsed), otherwise applies the
	 * current limit and grants the queued waiters it admits. Under the lock.
	 *
	 * @param  waiter       Waiter.
	 * @param  startNanos   When the wait started.
	 * @param  maxWaitNanos Maximum wait (0 for none).
	 * @param  isContainerStopped If the listener container stopped.
	 * @return              Why the wait must end, or null to keep waiting.
	 */
	private JmsListenerBulkheadException.Reason resolveAbortReasonOrGrant(
			final Waiter waiter,
			final long startNanos,
			final long maxWaitNanos,
			final BooleanSupplier isContainerStopped) {
		JmsListenerBulkheadException.Reason abortReason = null;
		if (!waiter.isGranted) {
			if (isContainerStopped.getAsBoolean()) {
				abortReason = JmsListenerBulkheadException.Reason.CONTAINER_STOPPED;
			}
			else if ((maxWaitNanos > 0) && ((System.nanoTime() - startNanos) >= maxWaitNanos)) {
				abortReason = JmsListenerBulkheadException.Reason.WAIT_TIMEOUT;
			}
			// Grants on every check, not only when this check saw the limit change:
			// another reader (such as the limit gauge) may have seen it first.
			else {
				this.refreshMaxInFlight();
				this.grantWaiters();
			}
		}
		return abortReason;
	}

	/**
	 * Ends a wait without a permit. A grant that raced with the abort is handed
	 * on to the next waiters. Under the lock.
	 *
	 * @param  waiter      Waiter.
	 * @param  reason      Why the wait ended.
	 * @param  destination Listener destination (for the exception).
	 * @param  startNanos  When the wait started.
	 * @return             The exception to throw.
	 */
	private JmsListenerBulkheadException abort(
			final Waiter waiter,
			final JmsListenerBulkheadException.Reason reason,
			final String destination,
			final long startNanos) {
		if (waiter.isGranted) {
			this.uncountInFlight(waiter.reservation);
			this.grantWaiters();
		}
		else {
			this.dequeue(waiter);
		}
		return new JmsListenerBulkheadException(reason, this.group, destination, System.nanoTime() - startNanos);
	}

	/**
	 * Queues a waiter and waits until it is granted a permit, the container
	 * stops, the maximum wait elapses or the thread is interrupted. Under the
	 * lock (released while awaiting).
	 *
	 * @param  waiter                       Waiter.
	 * @param  destination                  Listener destination (for the
	 *                                          exception).
	 * @param  startNanos                   When the wait started.
	 * @param  maxWaitNanos                 Maximum wait (0 for none).
	 * @param  isContainerStopped           If the listener container stopped.
	 * @return                              The permit.
	 * @throws JmsListenerBulkheadException If the wait ended without a permit.
	 */
	private JmsListenerBulkheadPermit awaitPermit(
			final Waiter waiter,
			final String destination,
			final long startNanos,
			final long maxWaitNanos,
			final BooleanSupplier isContainerStopped) throws JmsListenerBulkheadException {
		this.waiters.add(waiter);
		if (waiter.reservation != null) {
			this.queuedReservedWaiters++;
		}
		// Covers a limit raised since the last release (may grant this waiter).
		this.grantWaiters();
		final long stateCheckIntervalNanos = JmsListenerBulkhead.STATE_CHECK_INTERVAL.toNanos();
		JmsListenerBulkheadException.Reason abortReason = null;
		while (!waiter.isGranted && (abortReason == null)) {
			final long sliceNanos = (maxWaitNanos > 0 ? Math.min(stateCheckIntervalNanos, maxWaitNanos - (System.nanoTime() - startNanos))
					: stateCheckIntervalNanos);
			try {
				if (sliceNanos > 0) {
					waiter.condition.awaitNanos(sliceNanos);
				}
				abortReason = this.resolveAbortReasonOrGrant(waiter, startNanos, maxWaitNanos, isContainerStopped);
			}
			// The interrupt flag is deliberately not re-asserted. The interrupt comes
			// from the container shutdown, which then rolls the JMS session back: that
			// rollback is a blocking broker call that fails on an interrupted thread
			// (the session would be discarded and the message redelivered only through
			// connection recovery, with an error log). The invoker exits on the
			// container's own state flags, not on the interrupt flag.
			catch (final InterruptedException exception) {
				abortReason = JmsListenerBulkheadException.Reason.INTERRUPTED;
			}
		}
		if (abortReason != null) {
			throw this.abort(waiter, abortReason, destination, startNanos);
		}
		return new JmsListenerBulkheadPermit(this, waiter.reservation, Math.max(1L, System.nanoTime() - startNanos));
	}

	/**
	 * Acquires a permit, waiting in arrival order when none is free. A request
	 * with a free reserved permit of its reservation is granted at once, whatever
	 * the queue holds.
	 *
	 * @param  reservation                  Reservation (null, or one this
	 *                                          bulkhead does not have, for
	 *                                          unreserved).
	 * @param  destination                  Listener destination (only used to
	 *                                          describe an aborted wait).
	 * @param  maxWait                      Maximum wait (null or zero for none).
	 * @param  isContainerStopped           If the listener container stopped
	 *                                          (checked while waiting; never
	 *                                          null).
	 * @return                              The permit, to be closed when the
	 *                                      handler ends.
	 * @throws JmsListenerBulkheadException If the wait ended without a permit
	 *                                          (timeout, container stop or
	 *                                          interruption).
	 */
	public JmsListenerBulkheadPermit acquire(
			final String reservation,
			final String destination,
			final Duration maxWait,
			final BooleanSupplier isContainerStopped) throws JmsListenerBulkheadException {
		final long startNanos = System.nanoTime();
		final String actualReservation = ((reservation != null) && this.reservedPermits.containsKey(reservation) ? reservation : null);
		final long maxWaitNanos = ((maxWait == null) || maxWait.isNegative() ? 0L : maxWait.toNanos());
		JmsListenerBulkheadPermit permit = null;
		this.lock.lock();
		try {
			if (this.refreshMaxInFlight()) {
				this.grantWaiters();
			}
			if ((this.waiters.isEmpty() && this.isAdmissible(actualReservation)) || this.hasFreeReservedSlot(actualReservation)) {
				this.countInFlight(actualReservation);
				permit = new JmsListenerBulkheadPermit(this, actualReservation, 0L);
			}
			else {
				permit = this.awaitPermit(new Waiter(actualReservation, this.lock.newCondition()), destination, startNanos, maxWaitNanos,
						Objects.requireNonNull(isContainerStopped));
			}
		}
		finally {
			this.lock.unlock();
		}
		return permit;
	}

	/**
	 * Releases a permit and grants the freed capacity to the oldest admissible
	 * waiters. Called once per permit, by {@link JmsListenerBulkheadPermit#close()}.
	 *
	 * @param permit Permit.
	 */
	void release(
			final JmsListenerBulkheadPermit permit) {
		this.lock.lock();
		try {
			this.refreshMaxInFlight();
			this.uncountInFlight(permit.getReservation());
			this.grantWaiters();
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Gets the group.
	 *
	 * @return The group.
	 */
	public String getGroup() {
		return this.group;
	}

	/**
	 * Gets the current limit.
	 *
	 * @return The current limit.
	 */
	public int getMaxInFlight() {
		this.lock.lock();
		try {
			this.refreshMaxInFlight();
			return this.maxInFlight;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Gets the permits in use.
	 *
	 * @return The permits in use.
	 */
	public int getInFlight() {
		this.lock.lock();
		try {
			return this.inFlight;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Gets the listeners waiting for a permit.
	 *
	 * @return The listeners waiting for a permit.
	 */
	public int getWaiting() {
		this.lock.lock();
		try {
			return this.waiters.size();
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Gets the reservations.
	 *
	 * @return The reservations (names).
	 */
	public Set<String> getReservations() {
		return this.reservedPermits.keySet();
	}

	/**
	 * Gets the reserved permits of a reservation.
	 *
	 * @param  reservation Reservation.
	 * @return             The reserved permits (0 for an unknown reservation).
	 */
	public int getReservedPermits(
			final String reservation) {
		return this.reservedPermits.getOrDefault(reservation, 0);
	}

	/**
	 * Gets the in-flight handlers of a reservation (reserved and shared permits).
	 *
	 * @param  reservation Reservation.
	 * @return             The in-flight handlers (0 for an unknown reservation).
	 */
	public int getInFlight(
			final String reservation) {
		this.lock.lock();
		try {
			return this.reservedInFlight.getOrDefault(reservation, 0);
		}
		finally {
			this.lock.unlock();
		}
	}

}
