package org.coldis.library.service.jms;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
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
	private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ExtendedMessagingMessageListenerAdapter.class);

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
			exception.
			if (exception.getCause() instanceof BusinessException) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.warn("Dropping message due to business exception: " + exception.getLocalizedMessage() + ".");
				ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Dropping message due to business exception.", exception);
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
