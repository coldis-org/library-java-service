package org.coldis.library.service.serialization;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.localization.LocalizedMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import jakarta.validation.ConstraintViolationException;

/**
 * Controller exception handler.
 */
@ControllerAdvice
public class ControllerExceptionHandler {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ControllerExceptionHandler.class);

	/**
	 * Message service.
	 */
	@Autowired
	private LocalizedMessageService messageService;
	
	/**
	 * Enriches a message by its code and arguments.
	 *
	 * @param  message Message to be enriched.
	 * @return         The enriched message.
	 */
	protected SimpleMessage enrichMessage(
			final SimpleMessage message) {
		// The default message is the original one.
		String actualMessage = this.messageService.getMessage(message.getCode(), message.getParameters());
		actualMessage = (Objects.equals(message.getCode(), actualMessage) && StringUtils.isNotBlank(message.getContent()) ? message.getContent()
				: actualMessage);
		// Returns the enriched message
		message.setContent(actualMessage);
		return message;
	}

	/**
	 * Processes constraint validation exceptions.
	 *
	 * @param  exception The exception to be processed.
	 * @return           Response with the exception message.
	 */
	@ResponseBody
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ExceptionHandler(ConstraintViolationException.class)
	public SimpleMessage[] processValidationError(
			final ConstraintViolationException exception) {
		// Enriches the violation messages.
		final List<SimpleMessage> violations = exception.getConstraintViolations().stream().map(violation -> {
			// Gets the message code.
			final String messageCode = (violation.getMessageTemplate().startsWith("{") || StringUtils.isEmpty(violation.getMessageTemplate())
					? violation.getRootBeanClass().getSimpleName().toLowerCase() + "." + violation.getPropertyPath().toString().toLowerCase() + "."
							+ violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName().toLowerCase()
					: violation.getMessageTemplate());
			// Gets the message.
			final String message = (violation.getRootBeanClass().getSimpleName().toLowerCase() + "." + violation.getPropertyPath().toString().toLowerCase()
					+ ": " + violation.getMessage());
			// Gets the message parameters.
			final Object[] messageParameters = ArrayUtils.addAll(new Object[] { violation.getInvalidValue() }, violation.getExecutableParameters());
			// Returns the enriched message.
			return this.enrichMessage(new SimpleMessage(messageCode, message, messageParameters));
		}).collect(Collectors.toList());
		// Returns the messages.
		ControllerExceptionHandler.LOGGER.debug("Contraint violation exception returned. Violations: " + violations.stream()
				.map(violation -> violation.getContent()).reduce("\n", (
						message,
						messages) -> messages + message + "\n"),
				exception);
		return violations.toArray(new SimpleMessage[] {});
	}

	/**
	 * Processes business exceptions.
	 *
	 * @param  exception The exception to be processed.
	 * @return           Response with the exception message.
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<SimpleMessage[]> processBusinessException(
			final BusinessException exception) {
		// Enriches the exceptions messages.
		exception.getMessages().forEach(this::enrichMessage);
		// Returns the message with the exception status code.
		ControllerExceptionHandler.LOGGER.debug("Business exception returned.", exception);
		return new ResponseEntity<>(exception.getMessages().toArray(new SimpleMessage[] {}), HttpStatus.valueOf(exception.getStatusCode()));
	}

	/**
	 * Processes integration exceptions.
	 *
	 * @param  exception The exception to be processed.
	 * @return           Response with the exception message.
	 */
	@ExceptionHandler(IntegrationException.class)
	public ResponseEntity<SimpleMessage[]> processIntegrationException(
			final IntegrationException exception) {
		// Enriches the exception message.
		this.enrichMessage(exception.getInternalMessage());
		// Returns the messages with the exception status code.
		ControllerExceptionHandler.LOGGER.error("Integration exception returned.", exception);
		return new ResponseEntity<>(new SimpleMessage[] { exception.getInternalMessage() }, HttpStatus.valueOf(exception.getStatusCode()));
	}

	/**
	 * Processes unknown exceptions.
	 *
	 * @param  exception The exception to be processed.
	 * @return           Response with the exception message.
	 */
	@ResponseBody
	@ExceptionHandler(Throwable.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public SimpleMessage[] processOtherException(
			final Throwable exception) {
		// Returns a generic message.
		ControllerExceptionHandler.LOGGER.error("Exception returned.", exception);
		return new SimpleMessage[] { new SimpleMessage("error.unexpected", exception.getMessage()) };
	}

}
