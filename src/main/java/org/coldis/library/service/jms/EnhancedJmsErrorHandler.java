package org.coldis.library.service.jms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

/**
 * Enhanced JMS error handler.
 */
@Component
public class EnhancedJmsErrorHandler implements ErrorHandler {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedJmsErrorHandler.class);

	/**
	 * Error message.
	 */
	private static final String ERROR_MESSAGE = "Error processing JMS message";

	/**
	 * @see org.springframework.util.ErrorHandler#handleError(java.lang.Throwable
	 */
	@Override
	public void handleError(
			final Throwable throwable) {
		EnhancedJmsErrorHandler.LOGGER.error(EnhancedJmsErrorHandler.ERROR_MESSAGE + ": " + throwable.getLocalizedMessage() + ".");
		EnhancedJmsErrorHandler.LOGGER.debug(EnhancedJmsErrorHandler.ERROR_MESSAGE + ".", throwable);
	}

}
