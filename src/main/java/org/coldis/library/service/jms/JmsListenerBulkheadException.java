package org.coldis.library.service.jms;

import java.util.concurrent.TimeUnit;

import org.springframework.jms.JmsException;

/**
 * A wait for a JMS listener bulkhead permit ended without a permit. Thrown from
 * the listener, so the container rolls the JMS session back and the broker
 * redelivers the message (which counts as a delivery attempt). It is not a
 * {@link org.springframework.jms.listener.adapter.ListenerExecutionFailedException}
 * (the message is never dropped as a non-retriable failure) nor a
 * {@link jakarta.jms.JMSException} (the container exception listener is not
 * invoked).
 */
public class JmsListenerBulkheadException extends JmsException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 2861440921093245781L;

	/**
	 * Why the wait ended.
	 */
	public enum Reason {

		/** The maximum wait elapsed. */
		WAIT_TIMEOUT,

		/** The listener container stopped. */
		CONTAINER_STOPPED,

		/** The waiting thread was interrupted (container shutdown). */
		INTERRUPTED

	}

	/**
	 * Reason.
	 */
	private final Reason reason;

	/**
	 * Bulkhead group.
	 */
	private final String group;

	/**
	 * Listener destination.
	 */
	private final String destination;

	/**
	 * Constructor.
	 *
	 * @param reason      Reason.
	 * @param group       Bulkhead group.
	 * @param destination Listener destination (may be null).
	 * @param waitedNanos How long the listener waited.
	 */
	public JmsListenerBulkheadException(final Reason reason, final String group, final String destination, final long waitedNanos) {
		super("JMS listener bulkhead wait of group '" + group + "' for destination '" + destination + "' ended without a permit (" + reason + ") after "
				+ TimeUnit.NANOSECONDS.toMillis(waitedNanos) + " ms: the message is rolled back to the broker.");
		this.reason = reason;
		this.group = group;
		this.destination = destination;
	}

	/**
	 * Gets the reason.
	 *
	 * @return The reason.
	 */
	public Reason getReason() {
		return this.reason;
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
	 * Gets the destination.
	 *
	 * @return The destination.
	 */
	public String getDestination() {
		return this.destination;
	}

}
