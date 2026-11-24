package org.coldis.library.service.jms;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.springframework.jms.listener.DefaultMessageListenerContainer;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;

/**
 * Listener container whose consumers wait at the gate of their listener's JMS
 * listener bulkhead before they receive (see
 * {@link JmsListenerBulkhead#admit}): while the bulkhead has no capacity for
 * the listener, its messages stay on the broker queue instead of in consumers
 * waiting for a permit. Created by {@link ExtendedJmsListenerContainerFactory}.
 * <p>
 * A consumer whose last receive returned a message, or that has not received
 * yet, takes its permit at the gate and receives with a short timeout
 * ({@value #PERMIT_RECEIVE_TIMEOUT_MILLIS} ms at most): its queue is expected to
 * deliver at once, and if it does not the permit goes back right away. A
 * consumer whose last receive returned nothing only waits until a permit is
 * free, takes none, and receives with the container's receive timeout: an idle
 * queue never holds a permit while it polls.
 * <p>
 * The gate is passed before {@code receiveAndExecute} starts the container's
 * transaction (when a transaction manager is set), so a gated consumer holds
 * no transaction. A consumer that must wait at the gate counts as idle for the
 * container, and the container starts no new consumer while the gate is closed
 * for its listener. A container stop or shutdown ends the gate wait within
 * {@link JmsListenerBulkhead#STATE_CHECK_INTERVAL}: the consumer returns
 * without receiving, so nothing is rolled back.
 * <p>
 * A container whose listener is not limited (no bulkhead, bulkhead disabled,
 * group not limited, or not an {@link ExtendedMessagingMessageListenerAdapter})
 * behaves as a {@link DefaultMessageListenerContainer}.
 */
public class ExtendedMessageListenerContainer extends DefaultMessageListenerContainer {

	/**
	 * Longest receive timeout of a consumer that took its permit at the gate.
	 */
	public static final long PERMIT_RECEIVE_TIMEOUT_MILLIS = 100L;

	/**
	 * Admission of the consumer running on the current thread, from the gate to
	 * the end of its receive and handler.
	 */
	private final ThreadLocal<JmsListenerBulkheadAdmission> currentAdmission = new ThreadLocal<>();

	/**
	 * Consumers (invokers) whose last receive returned no message. Weak, so a
	 * retired consumer is not kept.
	 */
	private final Set<Object> idleConsumers = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	/**
	 * Gets the bulkhead binding of the listener.
	 *
	 * @return The binding, or null when the listener is not limited.
	 */
	private JmsListenerBulkheadBinding getBulkheadBinding() {
		return (this.getMessageListener() instanceof final ExtendedMessagingMessageListenerAdapter listenerAdapter ? listenerAdapter.getBulkheadBinding()
				: null);
	}

	/**
	 * Gets the admission of the consumer running on the current thread, used by
	 * the listener to run its handler on the permit taken at the gate.
	 *
	 * @return The admission, or null when the consumer did not pass the gate.
	 */
	JmsListenerBulkheadAdmission getCurrentAdmission() {
		return this.currentAdmission.get();
	}

	/**
	 * Gets the receive timeout of a consumer that took its permit at the gate:
	 * the container's receive timeout, at most
	 * {@value #PERMIT_RECEIVE_TIMEOUT_MILLIS} ms (a negative timeout, no wait, is
	 * kept).
	 *
	 * @return The receive timeout.
	 */
	private long getPermitReceiveTimeout() {
		final long receiveTimeout = this.getReceiveTimeout();
		return (receiveTimeout == 0 ? ExtendedMessageListenerContainer.PERMIT_RECEIVE_TIMEOUT_MILLIS
				: Math.min(receiveTimeout, ExtendedMessageListenerContainer.PERMIT_RECEIVE_TIMEOUT_MILLIS));
	}

	/**
	 * Waits at the bulkhead gate until the consumer may receive. The permit is
	 * taken at the gate unless the consumer's last receive returned nothing. A
	 * consumer about to wait counts as idle: the container then starts no new
	 * consumer for it, and may retire it like any idle surplus consumer.
	 *
	 * @param  bulkheadBinding Bulkhead binding of the listener.
	 * @param  invoker         Consumer (invoker).
	 * @param  session         Session (may be null).
	 * @return                 The admission, or null when the container stopped
	 *                         or the consumer was interrupted first (nothing is
	 *                         received then).
	 */
	private JmsListenerBulkheadAdmission admit(
			final JmsListenerBulkheadBinding bulkheadBinding,
			final Object invoker,
			final Session session) {
		JmsListenerBulkheadAdmission admission = null;
		try {
			admission = bulkheadBinding.admit(!this.idleConsumers.contains(invoker), () -> !this.isRunning(),
					() -> this.noMessageReceived(invoker, session));
		}
		catch (final JmsListenerBulkheadException exception) {
			this.logger.debug(exception.getMessage());
		}
		return admission;
	}

	/**
	 * Waits at the bulkhead gate (when the listener is limited), then receives
	 * and executes as the parent does, before the parent starts any
	 * transaction.
	 *
	 * @see org.springframework.jms.listener.AbstractPollingMessageListenerContainer#receiveAndExecute(java.lang.Object,
	 *      jakarta.jms.Session, jakarta.jms.MessageConsumer)
	 */
	@Override
	protected boolean receiveAndExecute(
			final Object invoker,
			final Session session,
			final MessageConsumer consumer) throws JMSException {
		final JmsListenerBulkheadBinding bulkheadBinding = this.getBulkheadBinding();
		boolean messageReceived = false;
		if (bulkheadBinding == null) {
			messageReceived = super.receiveAndExecute(invoker, session, consumer);
		}
		else {
			final JmsListenerBulkheadAdmission admission = this.admit(bulkheadBinding, invoker, session);
			if (admission != null) {
				try (admission) {
					this.currentAdmission.set(admission);
					messageReceived = super.receiveAndExecute(invoker, session, consumer);
				}
				finally {
					this.currentAdmission.remove();
				}
				if (messageReceived) {
					this.idleConsumers.remove(invoker);
				}
				else {
					this.idleConsumers.add(invoker);
				}
			}
		}
		return messageReceived;
	}

	/**
	 * Receives with a short timeout when the permit was taken at the gate.
	 *
	 * @see org.springframework.jms.listener.AbstractPollingMessageListenerContainer#receiveMessage(jakarta.jms.MessageConsumer)
	 */
	@Override
	protected Message receiveMessage(
			final MessageConsumer consumer) throws JMSException {
		final JmsListenerBulkheadAdmission admission = this.currentAdmission.get();
		return ((admission != null) && admission.hasPermit() ? this.receiveFromConsumer(consumer, this.getPermitReceiveTimeout())
				: super.receiveMessage(consumer));
	}

	/**
	 * Starts a new consumer only while the gate is open for the listener: a
	 * consumer started while it is closed would only wait at the gate.
	 *
	 * @see org.springframework.jms.listener.DefaultMessageListenerContainer#scheduleNewInvokerIfAppropriate()
	 */
	@Override
	protected void scheduleNewInvokerIfAppropriate() {
		final JmsListenerBulkheadBinding bulkheadBinding = this.getBulkheadBinding();
		if ((bulkheadBinding == null) || bulkheadBinding.isGateOpen()) {
			super.scheduleNewInvokerIfAppropriate();
		}
	}

}
