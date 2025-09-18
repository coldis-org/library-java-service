package org.coldis.library.service.jms;

import org.apache.activemq.artemis.jms.client.ActiveMQDestination;
import org.apache.commons.collections4.EnumerationUtils;
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
	 * Default constructor.
	 */
	public ExtendedMessagingMessageListenerAdapter(final JmsConverterProperties jmsConverterProperties) {
		super();
		this.jmsConverterProperties = jmsConverterProperties;
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
	 * @see org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter#onMessage(jakarta.jms.Message,
	 *      jakarta.jms.Session)
	 */
	@Override
	public void onMessage(
			final Message message,
			final Session session) throws JMSException {
		// Tries to process the message.
		try {

			// Validates async hops.
			this.validateAsyncHops(message);

			// Processes the message.
			super.onMessage(message, session);

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
			ExtendedMessagingMessageListenerAdapter.LOGGER.warn(exception.getLocalizedMessage());
			ExtendedMessagingMessageListenerAdapter.LOGGER.warn("Sending message to 'async-hops/exceeded' queue.");
		}
		// Drops message on business exception.
		catch (final ListenerExecutionFailedException exception) {
			final Throwable exceptionCause = exception.getCause();
			// Ignores exceptions that should not be retried.
			if (exceptionCause instanceof final RetriableIn retriableException) {
				if (retriableException.getRetryIn() == null) {
					ExtendedMessagingMessageListenerAdapter.LOGGER
							.warn("Dropping message due to non-retriable error '" + exception.getClass() + ": " + exception.getLocalizedMessage()
									+ "' and nested error '" + exceptionCause.getClass() + ": " + exceptionCause.getLocalizedMessage() + "'.");
					ExtendedMessagingMessageListenerAdapter.LOGGER
							.debug("Dropped message due to non-retriable error '" + exception.getClass() + ": " + exception.getLocalizedMessage()
									+ "' and nested error '" + exceptionCause.getClass() + ": " + exceptionCause.getLocalizedMessage() + "'.", exception);
				}
				else {
					throw exception;
				}
			}
			else {
				throw exception;
			}
		}
		// Clears the thread context map.
		finally {
			ThreadMapContextHolder.clear();
		}
	}

}
