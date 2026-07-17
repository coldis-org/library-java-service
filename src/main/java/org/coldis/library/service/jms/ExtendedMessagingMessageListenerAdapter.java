package org.coldis.library.service.jms;

import java.util.Collection;
import java.util.Set;

import org.apache.activemq.artemis.jms.client.ActiveMQDestination;
import org.apache.commons.collections4.EnumerationUtils;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.RetriableIn;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	 * Validates if the maximum number of async hops was exceeded.
	 *
	 * @throws JMSException If there is any problem accessing the message.
	 */
	private void validateAsyncHops(
			final Message message) throws JMSException {
		final Long maximumAsyncHops = this.jmsConverterProperties.getMaximumAsyncHops();
		@SuppressWarnings("unchecked")
		final Long currentAsyncHop = (EnumerationUtils.toList(message.getPropertyNames())
				.contains(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER)
						? message.getLongProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER)
						: 0L);
		if ((maximumAsyncHops > 0) && (currentAsyncHop > maximumAsyncHops)) {
			final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
			throw new JmsAsyncHopsExceededException(destination, currentAsyncHop, message.getBody(Object.class));
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
		catch (final JmsAsyncHopsExceededException exception) {
			final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
			final Message copiedMessage = JmsMessageHelper.copy(session, message);
			copiedMessage.setStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_ORIGINAL_ADDRESS.toString(), destination);
			copiedMessage.setObjectProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, null);
			try (MessageProducer producer = session.createProducer(session.createQueue("async-hops/exceeded"))) {
				producer.send(copiedMessage);
			}
			ExtendedMessagingMessageListenerAdapter.LOGGER.warn(exception.getLocalizedMessage() + "Sending message to 'async-hops/exceeded' queue.");
		}
		// Handles listener execution failed exceptions.
		catch (final ListenerExecutionFailedException exception) {
			final Throwable exceptionCause = exception.getCause();

			// If not an non-retrieable exception.
			if (!this.nonRetriableExceptions.contains(exceptionCause.getClass())) {
				// If not a retrieable exception or is a retrieable exception without a retry-in
				// value.
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
