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
 * <p>
 * Consumers wait at a gate before they receive ({@link #admit}), so a message
 * waits on the broker queue, not in a consumer, while the group has no
 * capacity. A consumer whose queue just delivered takes its permit at the gate;
 * one whose queue was just empty only waits until a permit is free and takes
 * nothing, so an idle queue never holds a permit while it polls. A consumer
 * that received a message without a permit (it raced another one for the free
 * permit, or its container has no gate) waits for one with {@link #acquire},
 * holding that message.
 * <p>
 * Waiters are served in arrival order (FIFO), consumers holding a message
 * before gated ones: a release hands the permit straight to the oldest
 * admissible waiter, so a new arrival never overtakes a queued one.
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
	 * What a waiter waits for.
	 */
	private enum WaiterKind {

		/** A permit for a message the consumer already received. */
		MESSAGE(true),

		/** To receive, taking the permit at the gate (the queue just delivered). */
		GATE_WITH_PERMIT(true),

		/** To receive, without a permit (the queue was just empty). */
		GATE_WITHOUT_PERMIT(false);

		/**
		 * If a grant counts a permit.
		 */
		private final boolean takesPermit;

		/**
		 * Constructor.
		 *
		 * @param takesPermit If a grant counts a permit.
		 */
		WaiterKind(final boolean takesPermit) {
			this.takesPermit = takesPermit;
		}

	}

	/**
	 * A listener waiting for a permit or at the gate. Guarded by the bulkhead
	 * lock.
	 */
	private static final class Waiter {

		/**
		 * Reservation (null for unreserved).
		 */
		private final String reservation;

		/**
		 * What the waiter waits for.
		 */
		private final WaiterKind kind;

		/**
		 * Condition the waiter awaits (one per waiter, so a grant wakes exactly the
		 * granted waiter).
		 */
		private final Condition condition;

		/**
		 * If the wait was granted (and the permit counted, for a kind that takes
		 * one).
		 */
		private boolean isGranted;

		/**
		 * Constructor.
		 *
		 * @param reservation Reservation.
		 * @param kind        What the waiter waits for.
		 * @param condition   Condition.
		 */
		private Waiter(final String reservation, final WaiterKind kind, final Condition condition) {
			this.reservation = reservation;
			this.kind = kind;
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
	 * Consumers holding a received message and waiting for a permit, in arrival
	 * order. Served before the gate waiters. Guarded by the lock.
	 */
	private final Set<Waiter> messageWaiters = new LinkedHashSet<>();

	/**
	 * Consumers waiting at the gate before they receive, in arrival order.
	 * Guarded by the lock.
	 */
	private final Set<Waiter> gateWaiters = new LinkedHashSet<>();

	/**
	 * Queued waiters that have a reservation. Guarded by the lock.
	 */
	private int queuedReservedWaiters;

	/**
	 * Shared permits in use (unreserved handlers plus reserved handlers beyond
	 * their reservation, including permits taken at the gate). Guarded by the
	 * lock.
	 */
	private int sharedInUse;

	/**
	 * Permits in use (including permits taken at the gate). Guarded by the lock.
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
	 * Gets the queue of a waiter. Under the lock.
	 *
	 * @param  waiter Waiter.
	 * @return        The queue.
	 */
	private Set<Waiter> getQueue(
			final Waiter waiter) {
		return (waiter.kind == WaiterKind.MESSAGE ? this.messageWaiters : this.gateWaiters);
	}

	/**
	 * Adds a waiter to its queue. Under the lock.
	 *
	 * @param waiter Waiter.
	 */
	private void enqueue(
			final Waiter waiter) {
		this.getQueue(waiter).add(waiter);
		if (waiter.reservation != null) {
			this.queuedReservedWaiters++;
		}
	}

	/**
	 * Removes a waiter from its queue. Under the lock.
	 *
	 * @param waiter Waiter.
	 */
	private void dequeue(
			final Waiter waiter) {
		if (this.getQueue(waiter).remove(waiter) && (waiter.reservation != null)) {
			this.queuedReservedWaiters--;
		}
	}

	/**
	 * Grants the waiters of a queue, oldest first, while there is capacity: a
	 * waiter is admissible with a free reserved permit of its reservation or a
	 * free shared one. A permit is counted at grant time (for a kind that takes
	 * one), so capacity is never booked twice between the signal and the
	 * wake-up; a gate waiter without permit is woken without counting anything.
	 * Stops once the shared permits are exhausted and no queued waiter has a
	 * reservation. Under the lock.
	 *
	 * @param queue Queue.
	 */
	private void grantWaiters(
			final Set<Waiter> queue) {
		final Iterator<Waiter> waitersIterator = queue.iterator();
		while (waitersIterator.hasNext() && ((this.sharedInUse < this.getSharedCapacity()) || (this.queuedReservedWaiters > 0))) {
			final Waiter waiter = waitersIterator.next();
			if (this.isAdmissible(waiter.reservation)) {
				waitersIterator.remove();
				if (waiter.reservation != null) {
					this.queuedReservedWaiters--;
				}
				if (waiter.kind.takesPermit) {
					this.countInFlight(waiter.reservation);
				}
				waiter.isGranted = true;
				waiter.condition.signal();
			}
		}
	}

	/**
	 * Grants the queued waiters while there is capacity: first the consumers
	 * holding a message, then the gate waiters, each queue in arrival order.
	 * Under the lock.
	 */
	private void grantWaiters() {
		this.grantWaiters(this.messageWaiters);
		this.grantWaiters(this.gateWaiters);
	}

	/**
	 * If a consumer arriving at the gate may receive at once: nobody waits and
	 * it is admissible, or a reserved permit of its reservation is free. Under
	 * the lock, after the limit was refreshed.
	 *
	 * @param  reservation Reservation (null for unreserved).
	 * @return             If the consumer may receive at once.
	 */
	private boolean isGateOpenUnderLock(
			final String reservation) {
		return (this.messageWaiters.isEmpty() && this.gateWaiters.isEmpty() && this.isAdmissible(reservation)) || this.hasFreeReservedSlot(reservation);
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
	 * Ends a wait without a permit. A permit granted in a race with the abort is
	 * handed on to the next waiters. Under the lock.
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
			if (waiter.kind.takesPermit) {
				this.uncountInFlight(waiter.reservation);
				this.grantWaiters();
			}
		}
		else {
			this.dequeue(waiter);
		}
		return new JmsListenerBulkheadException(reason, this.group, destination, System.nanoTime() - startNanos);
	}

	/**
	 * Queues a waiter and waits until it is granted, the container stops, the
	 * maximum wait elapses or the thread is interrupted. Under the lock
	 * (released while awaiting).
	 *
	 * @param  waiter                       Waiter.
	 * @param  destination                  Listener destination (for the
	 *                                          exception).
	 * @param  startNanos                   When the wait started.
	 * @param  maxWaitNanos                 Maximum wait (0 for none).
	 * @param  isContainerStopped           If the listener container stopped.
	 * @return                              How long the wait took (at least 1
	 *                                      nanosecond).
	 * @throws JmsListenerBulkheadException If the wait ended without a grant.
	 */
	private long awaitGrant(
			final Waiter waiter,
			final String destination,
			final long startNanos,
			final long maxWaitNanos,
			final BooleanSupplier isContainerStopped) throws JmsListenerBulkheadException {
		this.enqueue(waiter);
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
		return Math.max(1L, System.nanoTime() - startNanos);
	}

	/**
	 * Gets the reservation a request uses.
	 *
	 * @param  reservation Requested reservation.
	 * @return             The reservation, or null when it is null or this
	 *                     bulkhead does not have it.
	 */
	private String getActualReservation(
			final String reservation) {
		return ((reservation != null) && this.reservedPermits.containsKey(reservation) ? reservation : null);
	}

	/**
	 * Waits at the gate as {@link #admit(String, boolean, String, BooleanSupplier)}
	 * does, running a callback just before the consumer queues (only when it has
	 * to wait).
	 *
	 * @param  reservation                  Reservation.
	 * @param  takesPermit                  If the permit is taken at the gate.
	 * @param  destination                  Listener destination.
	 * @param  isContainerStopped           If the listener container stopped.
	 * @param  beforeWaiting                Run under the bulkhead lock just before
	 *                                          the consumer queues (may be null;
	 *                                          must be short and must not block).
	 * @return                              The admission.
	 * @throws JmsListenerBulkheadException If the wait ended first.
	 */
	public JmsListenerBulkheadAdmission admit(
			final String reservation,
			final boolean takesPermit,
			final String destination,
			final BooleanSupplier isContainerStopped,
			final Runnable beforeWaiting) throws JmsListenerBulkheadException {
		final long startNanos = System.nanoTime();
		final String actualReservation = this.getActualReservation(reservation);
		JmsListenerBulkheadAdmission admission = null;
		this.lock.lock();
		try {
			if (this.refreshMaxInFlight()) {
				this.grantWaiters();
			}
			if (this.isGateOpenUnderLock(actualReservation)) {
				if (takesPermit) {
					this.countInFlight(actualReservation);
				}
				admission = new JmsListenerBulkheadAdmission(this, actualReservation, takesPermit, 0L);
			}
			else {
				if (beforeWaiting != null) {
					beforeWaiting.run();
				}
				final long waitNanos = this.awaitGrant(new Waiter(actualReservation,
						(takesPermit ? WaiterKind.GATE_WITH_PERMIT : WaiterKind.GATE_WITHOUT_PERMIT), this.lock.newCondition()), destination, startNanos, 0L,
						Objects.requireNonNull(isContainerStopped));
				admission = new JmsListenerBulkheadAdmission(this, actualReservation, takesPermit, waitNanos);
			}
		}
		finally {
			this.lock.unlock();
		}
		return admission;
	}

	/**
	 * Waits at the gate until the consumer may receive: in arrival order, until
	 * its listener is admissible (a free reserved permit of its reservation or a
	 * free shared one). A consumer that arrives while nobody waits and it is
	 * admissible, or while a reserved permit of its reservation is free, passes
	 * at once.
	 * <p>
	 * With {@code takesPermit} (its queue just delivered, so the receive is
	 * expected to return at once) the permit is taken at the gate and the
	 * admission holds it until it becomes the handler's permit or is closed.
	 * Without (its queue was just empty), nothing is taken: an idle queue's
	 * consumer never holds a permit while it polls, and on a message it acquires
	 * the permit with {@link #acquire}, normally at once.
	 * <p>
	 * There is no maximum wait at the gate (no message waits); the wait ends on
	 * a container stop and on an interruption, as {@link #acquire} does.
	 *
	 * @param  reservation                  Reservation (null, or one this
	 *                                          bulkhead does not have, for
	 *                                          unreserved).
	 * @param  takesPermit                  If the permit is taken at the gate.
	 * @param  destination                  Listener destination (only used to
	 *                                          describe an aborted wait).
	 * @param  isContainerStopped           If the listener container stopped
	 *                                          (checked while waiting; never
	 *                                          null).
	 * @return                              The admission, to be closed after the
	 *                                      receive and its handler.
	 * @throws JmsListenerBulkheadException If the wait ended first (container
	 *                                          stop or interruption).
	 */
	public JmsListenerBulkheadAdmission admit(
			final String reservation,
			final boolean takesPermit,
			final String destination,
			final BooleanSupplier isContainerStopped) throws JmsListenerBulkheadException {
		return this.admit(reservation, takesPermit, destination, isContainerStopped, null);
	}

	/**
	 * If a consumer of a listener of the reservation arriving at the gate now
	 * would receive at once. Used to decide whether a listener container may
	 * start another consumer.
	 *
	 * @param  reservation Reservation (null, or one this bulkhead does not have,
	 *                         for unreserved).
	 * @return             If the gate is open for the reservation.
	 */
	public boolean isGateOpen(
			final String reservation) {
		this.lock.lock();
		try {
			if (this.refreshMaxInFlight()) {
				this.grantWaiters();
			}
			return this.isGateOpenUnderLock(this.getActualReservation(reservation));
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Acquires a permit for a received message, waiting in arrival order when
	 * none is free (holding the message). A request with a free reserved permit
	 * of its reservation is granted at once, whatever the queue holds; consumers
	 * waiting at the gate never delay it.
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
		final String actualReservation = this.getActualReservation(reservation);
		final long maxWaitNanos = ((maxWait == null) || maxWait.isNegative() ? 0L : maxWait.toNanos());
		JmsListenerBulkheadPermit permit = null;
		this.lock.lock();
		try {
			if (this.refreshMaxInFlight()) {
				this.grantWaiters();
			}
			// Gate waiters do not hold a message: a consumer that holds one does not
			// queue behind them.
			if ((this.messageWaiters.isEmpty() && this.isAdmissible(actualReservation)) || this.hasFreeReservedSlot(actualReservation)) {
				this.countInFlight(actualReservation);
				permit = new JmsListenerBulkheadPermit(this, actualReservation, 0L);
			}
			else {
				final long waitNanos = this.awaitGrant(new Waiter(actualReservation, WaiterKind.MESSAGE, this.lock.newCondition()), destination, startNanos,
						maxWaitNanos, Objects.requireNonNull(isContainerStopped));
				permit = new JmsListenerBulkheadPermit(this, actualReservation, waitNanos);
			}
		}
		finally {
			this.lock.unlock();
		}
		return permit;
	}

	/**
	 * Releases a permit (of a handler or taken at the gate) and grants the freed
	 * capacity to the oldest admissible waiters. Called once per permit, by
	 * {@link JmsListenerBulkheadPermit#close()} and
	 * {@link JmsListenerBulkheadAdmission#close()}.
	 *
	 * @param reservation Reservation the permit was counted in (null for
	 *                        unreserved).
	 */
	void release(
			final String reservation) {
		this.lock.lock();
		try {
			this.refreshMaxInFlight();
			this.uncountInFlight(reservation);
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
	 * Gets the consumers waiting: at the gate before they receive, or holding a
	 * received message for a permit.
	 *
	 * @return The consumers waiting.
	 */
	public int getWaiting() {
		this.lock.lock();
		try {
			return this.gateWaiters.size() + this.messageWaiters.size();
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Gets the consumers holding a received message while they wait for a
	 * permit (part of {@link #getWaiting()}).
	 *
	 * @return The consumers holding a received message while they wait.
	 */
	public int getWaitingWithMessage() {
		this.lock.lock();
		try {
			return this.messageWaiters.size();
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
