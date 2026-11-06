package org.coldis.library.service.jms;

import java.util.Collection;
import java.util.Set;

import org.apache.activemq.artemis.jms.client.ActiveMQDestination;
import org.apache.activemq.artemis.utils.CompositeAddress;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.RetriableIn;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

/**
 * Context-aware messaging message listener adapter.
 */
public class ExtendedMessagingMessageListenerAdapter extends MessagingMessageListenerAdapter {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ExtendedMessagingMessageListenerAdapter.class);

	/**
	 * Current async hop parameter.
	 */
	public static final String CURRENT_ASYNC_HOP_PARAMETER = "asyncHop";

	/**
	 * JMS converter properties.
	 */
	private final JmsConverterProperties jmsConverterProperties;

	/**
	 * Non-retryable exceptions.
	 */
	private final Collection<Class<?>> nonRetriableExceptions;

	/**
	 * Stale message filter service (may be null when unavailable).
	 */
	private final StaleMessageFilterService staleMessageFilterService;

	/**
	 * Listener container (may be null when unavailable).
	 */
	private AbstractMessageListenerContainer listenerContainer;

	/**
	 * Default constructor.
	 */
	public ExtendedMessagingMessageListenerAdapter(final JmsConverterProperties jmsConverterProperties, final Collection<Class<?>> nonRetriableExceptions,
			final StaleMessageFilterService staleMessageFilterService) {
		super();
		this.jmsConverterProperties = jmsConverterProperties;
		this.nonRetriableExceptions = (nonRetriableExceptions == null ? Set.of() : Set.copyOf(nonRetriableExceptions));
		this.staleMessageFilterService = staleMessageFilterService;
	}

	/**
	 * Sets the listener container, used to resolve the queue the listener consumes
	 * from (the original queue of messages sent to the async hops exceeded queue).
	 * Set by the listener endpoint of {@link EnhancedJmsBootstrapConfiguration}
	 * after the adapter is created. When not set, queue listeners fall back to the
	 * message destination and topic subscriptions get no original queue (their
	 * exceeded messages cannot be retried by the broker).
	 *
	 * @param listenerContainer Listener container.
	 */
	public void setListenerContainer(
			final AbstractMessageListenerContainer listenerContainer) {
		this.listenerContainer = listenerContainer;
	}

	/**
	 * Resolves the broker queue the listener consumes from: the queue the
	 * container listens to (or, without it, the message destination), or the
	 * subscription queue for durable or shared topic subscriptions. The
	 * subscription queue is named with the container client id: a client id set
	 * only on the connection factory is not seen here, and the resolved name then
	 * does not match the broker queue.
	 *
	 * @param  destination Message destination.
	 * @return             The queue name, or null when it cannot be resolved (e.g.
	 *                     a non-durable non-shared subscription, whose queue is
	 *                     temporary).
	 */
	private String getConsumedQueueName(
			final ActiveMQDestination destination) {
		String queueName = null;
		if (destination.isQueue()) {
			final String listenedDestinationName = ((this.listenerContainer != null) && !this.listenerContainer.isPubSubDomain()
					? this.listenerContainer.getDestinationName()
					: null);
			queueName = CompositeAddress.extractQueueName(listenedDestinationName != null ? listenedDestinationName : destination.getName());
		}
		else if ((this.listenerContainer != null) && (this.listenerContainer.getSubscriptionName() != null)
				&& (this.listenerContainer.isSubscriptionDurable() || this.listenerContainer.isSubscriptionShared())) {
			queueName = ActiveMQDestination.createQueueNameForSubscription(this.listenerContainer.isSubscriptionDurable(),
					this.listenerContainer.getClientId(), this.listenerContainer.getSubscriptionName()).toString();
		}
		return queueName;
	}

	/**
	 * Validates if the maximum number of async hops was exceeded.
	 *
	 * @throws JMSException If there is any problem accessing the message.
	 */
	private void validateAsyncHops(
			final Message message) throws JMSException {
		final Long maximumAsyncHops = this.jmsConverterProperties.getMaximumAsyncHops();
		final Long currentAsyncHop = JmsMessageHelper.getLongProperty(message, ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, 0L);
		if ((maximumAsyncHops > 0) && (currentAsyncHop > maximumAsyncHops)) {
			final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
			throw new JmsAsyncHopsExceededException(destination, currentAsyncHop);
		}
	}

	/**
	 * Resolves when the message was posted (wall-clock epoch millis): the
	 * explicit override property, the scheduled delivery time (a delayed message
	 * only becomes current at its scheduled time) or the JMS timestamp.
	 *
	 * @return When the message was posted, or null when unknown (fail-open).
	 */
	private Long getStaleFilterPostedAt(
			final Message message) throws JMSException {
		Long postedAt = null;
		if (message.propertyExists(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY)) {
			postedAt = message.getLongProperty(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY);
		}
		else if (message.propertyExists(org.apache.activemq.artemis.api.core.Message.HDR_SCHEDULED_DELIVERY_TIME.toString())) {
			postedAt = message.getLongProperty(org.apache.activemq.artemis.api.core.Message.HDR_SCHEDULED_DELIVERY_TIME.toString());
		}
		else if (message.getJMSTimestamp() > 0) {
			postedAt = message.getJMSTimestamp();
		}
		return postedAt;
	}

	/**
	 * Checks if the message carries a stale filter key and a same-key message
	 * started successful processing after this one was posted (in which case the
	 * processing already reflected the state that originated this message and it
	 * can be dropped). Fails open on any error.
	 */
	private boolean isSupersededByNewerProcessing(
			final Message message) {
		boolean superseded = false;
		if ((this.staleMessageFilterService != null) && this.staleMessageFilterService.isEnabled()) {
			try {
				if (message.propertyExists(JmsMessage.STALE_FILTER_KEY_PROPERTY)) {
					final Long postedAt = this.getStaleFilterPostedAt(message);
					if (postedAt != null) {
						final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
						superseded = this.staleMessageFilterService.hasNewerProcessing(destination,
								message.getStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY), postedAt);
					}
				}
			}
			catch (final JMSException exception) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Stale message filter check failed (processing the message).", exception);
			}
		}
		return superseded;
	}

	/**
	 * Records that a message with a stale filter key started successful
	 * processing.
	 *
	 * @param processingStartTimestamp When the processing started (wall-clock
	 *                                     epoch millis).
	 */
	private void recordStaleFilterProcessing(
			final Message message,
			final long processingStartTimestamp) {
		if ((this.staleMessageFilterService != null) && this.staleMessageFilterService.isEnabled()) {
			try {
				if (message.propertyExists(JmsMessage.STALE_FILTER_KEY_PROPERTY)) {
					final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
					this.staleMessageFilterService.recordProcessing(destination, message.getStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY),
							processingStartTimestamp);
				}
			}
			catch (final JMSException exception) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Stale message filter processing record failed.", exception);
			}
		}
	}

	/**
	 * @see org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter#onMessage(jakarta.jms.Message,
	 *      jakarta.jms.Session)
	 */
	@Override
	public void onMessage(
			final Message message,
			final Session session) throws JMSException {
		// Tries to process the message.
		try {

			// Drops (acknowledges without processing) messages superseded by a newer
			// same-key processing.
			if (this.isSupersededByNewerProcessing(message)) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Dropping stale message superseded by a newer same-key processing.");
			}
			else {

				// Validates async hops.
				this.validateAsyncHops(message);

				// Processes the message. The JMS timestamp is producer wall-clock: the
				// clock skew margin absorbs small divergences from the local clock.
				final long processingStartTimestamp = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime());
				super.onMessage(message, session);
				this.recordStaleFilterProcessing(message, processingStartTimestamp);

			}

		}
		// Sends messages with async hops exceeded to a new specific queue.
		// The original address and queue are what the broker retry operation (e.g. the
		// console "Retry") uses to move the message back; the hops restart on retry.
		catch (final JmsAsyncHopsExceededException exception) {
			final ActiveMQDestination destination = (ActiveMQDestination) message.getJMSDestination();
			final Message copiedMessage = JmsMessageHelper.copy(session, message);
			copiedMessage.setStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_ORIGINAL_ADDRESS.toString(), destination.getAddress());
			// Always overwritten: an original queue inherited from the consumed message must
			// not steer the retry (null makes the broker skip it).
			copiedMessage.setObjectProperty(org.apache.activemq.artemis.api.core.Message.HDR_ORIGINAL_QUEUE.toString(),
					this.getConsumedQueueName(destination));
			copiedMessage.setLongProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, 0L);
			try (MessageProducer producer = session.createProducer(session.createQueue("async-hops/exceeded"))) {
				producer.send(copiedMessage);
			}
			ExtendedMessagingMessageListenerAdapter.LOGGER.warn("{} Sending message '{}' to 'async-hops/exceeded' queue.", exception.getLocalizedMessage(),
					message.getJMSMessageID());
		}
		// Handles listener execution failed exceptions.
		catch (final ListenerExecutionFailedException exception) {
			final Throwable exceptionCause = exception.getCause();

			// If not a non-retriable exception.
			if (!this.nonRetriableExceptions.contains(exceptionCause.getClass())) {
				// Retries unless the cause is a retriable-aware exception without a retry-in
				// value (which is dropped below).
				if (!(exceptionCause instanceof final RetriableIn retriableException) || (retriableException.getRetryIn() != null)) {
					// Re-throws the exception to trigger a retry.
					throw exception;
				}
			}

			// Logs and drops the message.
			ExtendedMessagingMessageListenerAdapter.LOGGER
					.warn("Dropping message due to non-retriable error '" + exception.getClass() + ": " + exception.getLocalizedMessage()
							+ "' and nested error '" + exceptionCause.getClass() + ": " + exceptionCause.getLocalizedMessage() + "'.");
			ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Dropped message due to non-retriable error '" + exception.getClass() + ": "
					+ exception.getLocalizedMessage() + "' and nested error '" + exceptionCause.getClass() + ": " + exceptionCause.getLocalizedMessage() + "'.",
					exception);
		}
		// Clears the thread context map.
		finally {
			ThreadMapContextHolder.clear();
		}
	}

}
