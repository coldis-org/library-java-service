package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

/**
 * What one listener needs from the JMS listener bulkhead: its bulkhead,
 * reservation, destination and meters. Resolved once per listener by
 * {@link JmsListenerBulkheadRegistry#bind}, then reused for every message.
 */
public class JmsListenerBulkheadBinding {

	/**
	 * Message property with the delivery count (1 on the first delivery).
	 */
	public static final String DELIVERY_COUNT_PROPERTY = "JMSXDeliveryCount";

	/**
	 * Bulkhead.
	 */
	private final JmsListenerBulkhead bulkhead;

	/**
	 * Reservation (null for unreserved).
	 */
	private final String reservation;

	/**
	 * Listener destination.
	 */
	private final String destination;

	/**
	 * Group properties (read on every message, so they apply live).
	 */
	private final JmsListenerBulkheadGroupProperties groupProperties;

	/**
	 * Meter registry (null when unavailable).
	 */
	private final MeterRegistry meterRegistry;

	/**
	 * Wait timer (null without a meter registry).
	 */
	private final Timer waitTimer;

	/**
	 * Aborted wait counters, by reason (created when a reason first occurs).
	 */
	private final Map<JmsListenerBulkheadException.Reason, Counter> abortedCounters = new ConcurrentHashMap<>();

	/**
	 * Constructor.
	 *
	 * @param bulkhead        Bulkhead.
	 * @param reservation     Reservation (null for unreserved).
	 * @param destination     Listener destination.
	 * @param groupProperties Group properties.
	 * @param meterRegistry   Meter registry (may be null).
	 */
	JmsListenerBulkheadBinding(
			final JmsListenerBulkhead bulkhead,
			final String reservation,
			final String destination,
			final JmsListenerBulkheadGroupProperties groupProperties,
			final MeterRegistry meterRegistry) {
		this.bulkhead = bulkhead;
		this.reservation = reservation;
		this.destination = destination;
		this.groupProperties = groupProperties;
		this.meterRegistry = meterRegistry;
		this.waitTimer = (meterRegistry == null ? null
				: Timer.builder(JmsListenerBulkheadRegistry.WAIT_METRIC).tag("group", bulkhead.getGroup()).tag("destination", destination)
						.register(meterRegistry));
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
	 * Gets the reservation.
	 *
	 * @return The reservation (null for unreserved).
	 */
	public String getReservation() {
		return this.reservation;
	}

	/**
	 * Gets the destination.
	 *
	 * @return The destination.
	 */
	public String getDestination() {
		return this.destination;
	}

	/**
	 * Counts an aborted wait, when a meter registry is available.
	 *
	 * @param reason Why the wait ended.
	 */
	private void countAbortedWait(
			final JmsListenerBulkheadException.Reason reason) {
		if (this.meterRegistry != null) {
			this.abortedCounters.computeIfAbsent(reason,
					abortReason -> Counter.builder(JmsListenerBulkheadRegistry.WAIT_ABORTED_METRIC).tag("group", this.bulkhead.getGroup())
							.tag("destination", this.destination).tag("reason", JmsListenerBulkheadRegistry.getReasonTag(abortReason))
							.register(this.meterRegistry))
					.increment();
		}
	}

	/**
	 * Gets the maximum wait that applies to the message: the group maximum wait
	 * while the message delivery count is at most the group maximum wait
	 * deliveries (a missing delivery count counts as 1), none after that.
	 *
	 * @param  message       Message.
	 * @return               The maximum wait (null for none).
	 * @throws JMSException If the delivery count cannot be read.
	 */
	private Duration getMaxWait(
			final Message message) throws JMSException {
		final Duration maxWait = this.groupProperties.getMaxWait();
		Duration actualMaxWait = null;
		if ((maxWait != null) && !maxWait.isZero() && !maxWait.isNegative()) {
			final int deliveryCount = (message.propertyExists(JmsListenerBulkheadBinding.DELIVERY_COUNT_PROPERTY)
					? message.getIntProperty(JmsListenerBulkheadBinding.DELIVERY_COUNT_PROPERTY)
					: 1);
			actualMaxWait = (deliveryCount <= this.groupProperties.getMaxWaitDeliveries() ? maxWait : null);
		}
		return actualMaxWait;
	}

	/**
	 * Waits at the gate of the bulkhead until the listener's consumer may
	 * receive (see {@link JmsListenerBulkhead#admit}). An aborted gate wait is
	 * not counted in {@value JmsListenerBulkheadRegistry#WAIT_ABORTED_METRIC}: no
	 * message was received, so nothing is rolled back.
	 *
	 * @param  takesPermit                  If the permit is taken at the gate
	 *                                          (the consumer's queue just
	 *                                          delivered).
	 * @param  isContainerStopped           If the listener container stopped.
	 * @param  beforeWaiting                Run just before the consumer queues
	 *                                          at the gate (may be null).
	 * @return                              The admission.
	 * @throws JmsListenerBulkheadException If the wait ended first (container
	 *                                          stop or interruption).
	 */
	public JmsListenerBulkheadAdmission admit(
			final boolean takesPermit,
			final BooleanSupplier isContainerStopped,
			final Runnable beforeWaiting) throws JmsListenerBulkheadException {
		return this.bulkhead.admit(this.reservation, takesPermit, this.destination, isContainerStopped, beforeWaiting);
	}

	/**
	 * If a consumer of the listener arriving at the gate now would receive at
	 * once.
	 *
	 * @return If the gate is open for the listener.
	 */
	public boolean isGateOpen() {
		return this.bulkhead.isGateOpen(this.reservation);
	}

	/**
	 * Gets the permit for a received message: the one its consumer took at the
	 * gate, or else a permit acquired now (waiting, holding the message, when
	 * none is free). Records the wait (the gate wait plus the wait holding the
	 * message, 0 when both were immediate) or the aborted wait.
	 *
	 * @param  message                      Received message.
	 * @param  admission                    Admission the message was received
	 *                                          with (null when the consumer did
	 *                                          not pass the gate).
	 * @param  isContainerStopped           If the listener container stopped.
	 * @return                              The permit, to be closed when the
	 *                                      handler ends.
	 * @throws JMSException                 If the message cannot be read.
	 * @throws JmsListenerBulkheadException If the wait ended without a permit.
	 */
	public JmsListenerBulkheadPermit acquire(
			final Message message,
			final JmsListenerBulkheadAdmission admission,
			final BooleanSupplier isContainerStopped) throws JMSException, JmsListenerBulkheadException {
		final boolean isOwnAdmission = ((admission != null) && (admission.getBulkhead() == this.bulkhead));
		JmsListenerBulkheadPermit permit = (isOwnAdmission ? admission.takePermit() : null);
		if (permit == null) {
			final Duration maxWait = this.getMaxWait(message);
			try {
				permit = this.bulkhead.acquire(this.reservation, this.destination, maxWait, isContainerStopped);
			}
			catch (final JmsListenerBulkheadException exception) {
				this.countAbortedWait(exception.getReason());
				throw exception;
			}
		}
		if (this.waitTimer != null) {
			this.waitTimer.record((isOwnAdmission ? admission.getWaitNanos() : 0L) + permit.getWaitNanos(), TimeUnit.NANOSECONDS);
		}
		return permit;
	}

	/**
	 * Acquires a permit for a received message, recording the wait (0 when the
	 * permit was immediate) or the aborted wait.
	 *
	 * @param  message                      Received message.
	 * @param  isContainerStopped           If the listener container stopped.
	 * @return                              The permit, to be closed when the
	 *                                      handler ends.
	 * @throws JMSException                 If the message cannot be read.
	 * @throws JmsListenerBulkheadException If the wait ended without a permit.
	 */
	public JmsListenerBulkheadPermit acquire(
			final Message message,
			final BooleanSupplier isContainerStopped) throws JMSException, JmsListenerBulkheadException {
		return this.acquire(message, null, isContainerStopped);
	}

}
