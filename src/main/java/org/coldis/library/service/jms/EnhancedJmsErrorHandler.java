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
	 * @see org.springframework.util.ErrorHandler#handleError(java.lang.Throwable
	 */
	@Override
	public void handleError(
			final Throwable throwable) {
		EnhancedJmsErrorHandler.LOGGER.error("Error '" + throwable.getClass() + ": " + throwable.getLocalizedMessage() + "'.");
		if (throwable.getCause() != null) {
			EnhancedJmsErrorHandler.LOGGER.error("Nested error '" + throwable.getCause().getClass() + ": " + throwable.getCause().getLocalizedMessage() + "'.");
		}
		EnhancedJmsErrorHandler.LOGGER.error("Error '" + throwable.getClass() + ": " + throwable.getLocalizedMessage() + "'.", throwable);
	}

}
