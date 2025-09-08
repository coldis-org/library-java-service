package org.coldis.library.service.jms;

import org.coldis.library.model.RetriableIn;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;

/**
 * Context-aware messaging message listener adapter.
 */
public class ExtendedMessagingMessageListenerAdapter extends MessagingMessageListenerAdapter {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ExtendedMessagingMessageListenerAdapter.class);

	/**
	 * @see org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter#onMessage(jakarta.jms.Message,
	 *      jakarta.jms.Session)
	 */
	@Override
	public void onMessage(
			final Message jmsMessage,
			final Session session) throws JMSException {
		// Tries to process the message.
		try {
			super.onMessage(jmsMessage, session);
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
