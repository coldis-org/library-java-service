package org.coldis.library.service.jms;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.coldis.library.dto.DtoOrigin;
import org.coldis.library.dto.DtoType;
import org.coldis.library.dto.DtoTypeMetadata;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Enhanced message converter.
 */
@Primary
@Component
@Qualifier("dtoJmsMessageConverter")
@ConditionalOnClass(value = Message.class)
@ConditionalOnProperty(
		name = "org.coldis.configuration.jms-message-converter-enhanced-enabled",
		havingValue = "true",
		matchIfMissing = true
)
public class EnhancedJmsMessageConverter extends SimpleMessageConverter {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedJmsMessageConverter.class);

	/**
	 * Current async hop parameter.
	 */
	private static final String CURRENT_ASYNC_HOP_PARAMETER = "currentAsyncHop";

	/**
	 * Prefered type parameter.
	 */
	private static final String PREFERED_TYPE_PARAMETER = "preferedType";

	/**
	 * DTO type parameter.
	 */
	@Deprecated
	private static final String DTO_TYPE_PARAMETER = "dtoType";

	/**
	 * Maximum async hops.
	 */
	@Value("${org.coldis.configuration.jms-message-converter-enhanced.maximum-async-hops:13}")
	private Long maximumAsyncHops;

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Gets the current async hop from the request.
	 *
	 * @return Current async hop.
	 */
	private Long getCurrentAsyncHopFromRequest() {
		final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
		final Long currentAsyncHop = (Long) requestAttributes.getAttribute(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER,
				RequestAttributes.SCOPE_REQUEST);
		return (currentAsyncHop == null ? 1 : currentAsyncHop);
	}

	/**
	 * Validates if the maximum number of async hops was exceeded.
	 */
	private void validateAsyncHops() {
		if (this.getCurrentAsyncHopFromRequest() > this.maximumAsyncHops) {
			throw new IntegrationException(new SimpleMessage("jms.async.hops.exceeded"));
		}
	}

	/**
	 * Adds parameters to the JMS message.
	 */
	private void addParametersToMessage(
			final Message message) throws JMSException {
		// Adds current async hop to message.
		message.setLongProperty(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER, this.getCurrentAsyncHopFromRequest());

	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#toMessage(java.lang.Object,
	 *      jakarta.jms.Session)
	 */
	private Message toMessageUsingDtoInformation(
			final Object payload,
			final Session session) {
		// Message.
		Message message = null;

		if (payload != null) {
			// Tries to get preferred classes for conversion using DTO annotations. Original
			// class is always preferred over DTO class.
			final DtoType dtoTypeAnnotation = Arrays.stream(payload.getClass().getAnnotationsByType(DtoType.class))
					.filter(dto -> "java".equals(dto.fileExtension())).findFirst().orElse(null);
			final DtoOrigin dtoOriginAnnotation = payload.getClass().getAnnotation(DtoOrigin.class);
			final List<String> preferedClassesNames = (dtoTypeAnnotation != null
					? (List.of(payload.getClass().getName().toString(),
							new DtoTypeMetadata(payload.getClass().getName().toString(), dtoTypeAnnotation).getName()))
					: dtoOriginAnnotation != null ? (List.of(dtoOriginAnnotation.originalClassName(), payload.getClass().getName().toString())) : null);
			// Serializes the payload with JSON serializer if preferred classes are
			// reacheable.
			if (CollectionUtils.isNotEmpty(preferedClassesNames)) {
				try {
					final String actualPayload = ObjectMapperHelper.serialize(this.objectMapper, payload, null, false);
					message = session.createTextMessage(actualPayload);
					// Adds the preferred types to the message.
					message.setStringProperty(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER, preferedClassesNames.stream().reduce((
							a,
							b) -> a + "," + b).get());
				}
				// If the object cannot be converted from JSON.
				catch (final Exception exception) {
					// Logs it.
					EnhancedJmsMessageConverter.LOGGER.error("Object could not be serialized to JSON: ", exception.getLocalizedMessage());
					EnhancedJmsMessageConverter.LOGGER.debug("Object could not be serialized to JSON.", exception);
				}
			}
		}

		// Returns the message.
		return message;

	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#toMessage(java.lang.Object,
	 *      jakarta.jms.Session)
	 */
	@Override
	public Message toMessage(
			final Object payload,
			final Session session) throws JMSException, MessageConversionException {

		// Validates async hops.
		this.validateAsyncHops();

		// Tries creating a message with DTO information.
		Message message = this.toMessageUsingDtoInformation(payload, session);

		// Tries using the simple message converter if no message has been prepared.
		if (message == null) {
			message = super.toMessage(payload, session);
		}
		// Adds parameters to the message.
		this.addParametersToMessage(message);
		// Returns the message.
		return message;

	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#fromMessage(jakarta.jms.Message)
	 */
	public Object fromMessageUsingPreferedClassesInformation(
			final Message message) throws JMSException, MessageConversionException {
		// Object.
		Object object = null;

		if ((message instanceof TextMessage) && StringUtils.isNotBlank(((TextMessage) message).getText())) {
			try {

				// Gets the preferred classes for conversion.
				final String preferedClassesNamesAttribute = message.getStringProperty(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER);
				final List<String> preferedClassesNames = (StringUtils.isBlank(preferedClassesNamesAttribute) ? null
						: List.of(preferedClassesNamesAttribute.split(",")));
				final List<String> availablePreferedClasses = preferedClassesNames.stream()
						.filter(className -> ClassUtils.isPresent(className, message.getClass().getClassLoader())).toList();
				final Class<?> preferedClass = ClassUtils.forName(availablePreferedClasses.getFirst(), message.getClass().getClassLoader());

				// Converts the message to the preferred class if available.
				if (CollectionUtils.isNotEmpty(availablePreferedClasses)) {
					object = ObjectMapperHelper.deserialize(this.objectMapper, ((TextMessage) message).getText(), preferedClass, false);
				}

			}
			// Logs errors.
			catch (final Exception exception) {
				EnhancedJmsMessageConverter.LOGGER.error("Object could not be converted from JSON: ", exception.getLocalizedMessage());
				EnhancedJmsMessageConverter.LOGGER.debug("Object could not be converted from JSON.", exception);
			}
		}

		// Returns the object.
		return object;
	}

	/**
	 * Increments the current async hop from the request.
	 *
	 * @return              Current async hop.
	 * @throws JMSException If the increment cannot be performed.
	 */
	private Long incrementCurrentAsyncHopOnRequest(
			final Message message) throws JMSException {
		Long currentAsyncHop = message.getLongProperty(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER);
		currentAsyncHop = (currentAsyncHop == null ? 1 : currentAsyncHop + 1);
		final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
		requestAttributes.setAttribute(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER, currentAsyncHop, RequestAttributes.SCOPE_REQUEST);
		return currentAsyncHop;
	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#fromMessage(jakarta.jms.Message)
	 */
	@Override
	public Object fromMessage(
			final Message message) throws JMSException, MessageConversionException {

		// Increments the current async hop on the request.
		this.incrementCurrentAsyncHopOnRequest(message);

		// Tries to convert using preferred classes.
		Object object = this.fromMessageUsingPreferedClassesInformation(message);

		// Tries converting the message using deprecated converters.
		if (object == null) {

		}

		// Tries using simple converter.
		if (object == null) {
			object = super.fromMessage(message);
		}

		// Returns the object.
		return object;
	}

}
