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
	 * Acquires a permit for a received message, recording the wait (0 when the
	 * permit was immediate) or the aborted wait.
	 *
	 * @param  message                      Received message.
	 * @param  stopped                      If the listener container stopped.
	 * @return                              The permit, to be closed when the
	 *                                      handler ends.
	 * @throws JMSException                 If the message cannot be read.
	 * @throws JmsListenerBulkheadException If the wait ended without a permit.
	 */
	public JmsListenerBulkheadPermit acquire(
			final Message message,
			final BooleanSupplier stopped) throws JMSException, JmsListenerBulkheadException {
		final Duration maxWait = this.getMaxWait(message);
		JmsListenerBulkheadPermit permit = null;
		try {
			permit = this.bulkhead.acquire(this.reservation, this.destination, maxWait, stopped);
		}
		catch (final JmsListenerBulkheadException exception) {
			this.countAbortedWait(exception.getReason());
			throw exception;
		}
		if (this.waitTimer != null) {
			this.waitTimer.record(permit.getWaitNanos(), TimeUnit.NANOSECONDS);
		}
		return permit;
	}

}
