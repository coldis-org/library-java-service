package org.coldis.library.service.localization;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

/**
 * Message service.
 */
@Service
public class LocalizedMessageService {

	/**
	 * Logger.
	 */
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LocalizedMessageService.class);

	/**
	 * Message source.
	 */
	@Autowired
	private MessageSource messageSource;

	/**
	 * Gets the message text for the code and arguments.
	 *
	 * @param  code      Message code.
	 * @param  arguments Message arguments.
	 * @return           The message text.
	 */
	public String getMessage(
			final String code,
			final Object... arguments) {
		String message = code;
		try {
			message = this.messageSource.getMessage(code, arguments, LocaleContextHolder.getLocale());
		}
		// Logs error if message not found.
		catch (final Exception exception) {
			LocalizedMessageService.LOGGER.error("Message not found for code: " + code);
		}
		return message;
	}

}
