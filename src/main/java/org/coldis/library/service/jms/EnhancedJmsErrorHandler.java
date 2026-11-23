package org.coldis.library.service.jms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

/**
 * Enhanced JMS error handler.
 */
@Component
@Qualifier("enhancedJmsErrorHandler")
public class EnhancedJmsErrorHandler implements ErrorHandler {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedJmsErrorHandler.class);

	/**
	 * Logs an error. A JMS listener bulkhead wait that ended without a permit is
	 * not a failure of the handler (the message goes back to the broker): it is
	 * logged as one line without stack trace, a warning for a wait timeout and a
	 * debug line for a container stop or shutdown.
	 *
	 * @see org.springframework.util.ErrorHandler#handleError(java.lang.Throwable)
	 */
	@Override
	public void handleError(
			final Throwable throwable) {
		if (throwable instanceof final JmsListenerBulkheadException bulkheadException) {
			if (bulkheadException.getReason() == JmsListenerBulkheadException.Reason.WAIT_TIMEOUT) {
				EnhancedJmsErrorHandler.LOGGER.warn(bulkheadException.getLocalizedMessage());
			}
			else {
				EnhancedJmsErrorHandler.LOGGER.debug(bulkheadException.getLocalizedMessage());
			}
		}
		else {
			EnhancedJmsErrorHandler.LOGGER.error("Error '" + throwable.getClass() + ": " + throwable.getLocalizedMessage() + "'.");
			if (throwable.getCause() != null) {
				EnhancedJmsErrorHandler.LOGGER
						.error("Nested error '" + throwable.getCause().getClass() + ": " + throwable.getCause().getLocalizedMessage() + "'.");
			}
			EnhancedJmsErrorHandler.LOGGER.error("Error '" + throwable.getClass() + ": " + throwable.getLocalizedMessage() + "'.", throwable);
		}
	}

}
