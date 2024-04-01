package org.coldis.library.service.jms;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.EnumerationUtils;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Scope;
import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Enhanced message converter.
 */
@Primary
@Component
@Qualifier("enhancedJmsMessageConverter")
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
	 * Local observation scope.
	 */
	public static final ObservationRegistry REGISTRY = ObservationRegistry.create();

	/**
	 * Current async hop parameter.
	 */
	private static final String CURRENT_ASYNC_HOP_PARAMETER = "asyncHop";

	/**
	 * Prefered type parameter.
	 */
	private static final String PREFERED_TYPE_PARAMETER = "preferedType";

	/**
	 * Thread attributes.
	 */
	@Value(value = "#{'${org.coldis.library.service.jms.thread-attributes:}'.split(',')}")
	private List<String> threadAttributes;

	/**
	 * Maximum async hops.
	 */
	@Value("${org.coldis.configuration.jms-message-converter-enhanced.maximum-async-hops:29}")
	private Long maximumAsyncHops;

	/**
	 * If the original type should precede the DTO type when trying to convert
	 * message.
	 */
	@Value("${org.coldis.configuration.jms-message-converter-enhanced.original-type-precedence:true}")
	private Boolean originalTypePrecedence;

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * DTO JMS message converter.
	 */
	@Autowired
	private DtoJmsMessageConverter dtoJmsMessageConverter;

	/**
	 * Typable JMS message converter.
	 */
	@Autowired
	private TypableJmsMessageConverter typableJmsMessageConverter;

	/**
	 * Gets the maximumAsyncHops.
	 *
	 * @return The maximumAsyncHops.
	 */
	public Long getMaximumAsyncHops() {
		return this.maximumAsyncHops;
	}

	/**
	 * Sets the maximumAsyncHops.
	 *
	 * @param maximumAsyncHops New maximumAsyncHops.
	 */
	public void setMaximumAsyncHops(
			final Long maximumAsyncHops) {
		this.maximumAsyncHops = maximumAsyncHops;
	}

	/**
	 * Gets the originalTypePrecedence.
	 *
	 * @return The originalTypePrecedence.
	 */
	public Boolean getOriginalTypePrecedence() {
		return this.originalTypePrecedence;
	}

	/**
	 * Sets the originalTypePrecedence.
	 *
	 * @param originalTypePrecedence New originalTypePrecedence.
	 */
	public void setOriginalTypePrecedence(
			final Boolean originalTypePrecedence) {
		this.originalTypePrecedence = originalTypePrecedence;
	}

	/** Gets the context. */
	public static Observation.Scope getContext() {
		Scope scope = EnhancedJmsMessageConverter.REGISTRY.getCurrentObservationScope();
		if (scope == null) {
			scope = Observation.createNotStarted(EnhancedJmsMessageConverter.class.getName(), EnhancedJmsMessageConverter.REGISTRY).openScope();
		}
		return scope;
	}

	/**
	 * Gets a context attribute.
	 */
	public static Object getContextAttribute(
			final String name) {
		Object attribute = null;
		final Observation.Scope scope = EnhancedJmsMessageConverter.getContext();
		if (scope != null) {
			final Observation observation = scope.getCurrentObservation();
			attribute = observation.getContext().get(name);
		}
		return attribute;
	}

	/**
	 * Puts a context attribute.
	 *
	 * @param name  Name of the attribute.
	 * @param value Value of the attribute.
	 */
	public static void setContextAttribute(
			final String name,
			final Object value) {
		final Observation.Scope scope = EnhancedJmsMessageConverter.getContext();
		if (scope != null) {
			final Observation observation = scope.getCurrentObservation();
			observation.getContext().put(name, value);
		}
	}

	/**
	 * Gets the current async hop from the request.
	 *
	 * @return Current async hop.
	 */
	private Long getCurrentAsyncHop() {
		final Long currentAsyncHop = (Long) EnhancedJmsMessageConverter.getContextAttribute(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER);
		return (currentAsyncHop == null ? 1 : currentAsyncHop);
	}

	/**
	 * Validates if the maximum number of async hops was exceeded.
	 */
	private void validateAsyncHops(
			final Object payload) {
		if ((this.maximumAsyncHops > 0) && (this.getCurrentAsyncHop() > this.maximumAsyncHops)) {
			throw new IntegrationException(
					new SimpleMessage("jms.async.hops.exceeded", "The maximum number of async hops was exceeded for message '" + payload + "'."));
		}
	}

	/**
	 * Adds parameters to the JMS message.
	 */
	private void addParametersToMessage(
			final Message message) throws JMSException {
		// Adds current async hop to message.
		message.setLongProperty(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER, this.getCurrentAsyncHop());
		// Adds thread local properties.
		for (final String attributeName : this.threadAttributes) {
			final Object attributeValue = EnhancedJmsMessageConverter.getContextAttribute(attributeName);
			if (attributeValue != null) {
				message.setObjectProperty(attributeName, attributeValue);
			}
		}

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
			// Pass thread attributes to

			// Tries to get preferred classes for conversion using DTO annotations. Original
			// class is always preferred over DTO class.
			final DtoType dtoTypeAnnotation = Arrays.stream(payload.getClass().getAnnotationsByType(DtoType.class))
					.filter(dto -> "java".equals(dto.fileExtension())).findFirst().orElse(null);
			final DtoOrigin dtoOriginAnnotation = payload.getClass().getAnnotation(DtoOrigin.class);
			List<String> preferedClassesNames = (dtoTypeAnnotation != null
					? (List.of(payload.getClass().getName().toString(),
							new DtoTypeMetadata(payload.getClass().getName().toString(), dtoTypeAnnotation).getQualifiedName()))
					: dtoOriginAnnotation != null ? (List.of(dtoOriginAnnotation.originalClassName(), payload.getClass().getName().toString())) : null);
			preferedClassesNames = ((preferedClassesNames == null) || this.originalTypePrecedence ? preferedClassesNames : preferedClassesNames.reversed());
			// Serializes the payload with JSON serializer if preferred classes are
			// reacheable.
			if (CollectionUtils.isNotEmpty(preferedClassesNames)) {
				try {
					final String actualPayload = ObjectMapperHelper.serialize(this.objectMapper, payload, null, false);
					message = session.createTextMessage(actualPayload);
					// Adds the preferred types to the message.
					final String preferedClassesNamesAttribute = preferedClassesNames.stream().reduce((
							name1,
							name2) -> StringUtils.joinWith(",", name1, name2)).get();
					message.setStringProperty(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER, preferedClassesNamesAttribute);
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
		this.validateAsyncHops(payload);

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
				final List<String> preferedClassesNames = (StringUtils.isBlank(preferedClassesNamesAttribute) ? List.of()
						: List.of(preferedClassesNamesAttribute.split(",")));
				final List<String> availablePreferedClasses = preferedClassesNames.stream()
						.filter(className -> ClassUtils.isPresent(className, message.getClass().getClassLoader())).toList();
				final Class<?> preferedClass = (CollectionUtils.isEmpty(availablePreferedClasses) ? null
						: ClassUtils.forName(availablePreferedClasses.getFirst(), message.getClass().getClassLoader()));

				// Converts the message to the preferred class if available.
				if (preferedClass != null) {
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
	@SuppressWarnings("unchecked")
	private Long incrementCurrentAsyncHopOnRequest(
			final Message message) throws JMSException {
		Long currentAsyncHop = (EnumerationUtils.toList(message.getPropertyNames()).contains(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER)
				? message.getLongProperty(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER)
				: null);
		currentAsyncHop = (currentAsyncHop == null ? 1 : currentAsyncHop + 1);
		EnhancedJmsMessageConverter.setContextAttribute(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER, currentAsyncHop);
		return currentAsyncHop;
	}

	/**
	 * Get parameters from the JMS message.
	 */
	private void getParametersFromMessage(
			final Message message) throws JMSException {
		// Increments the current async hop on the request.
		this.incrementCurrentAsyncHopOnRequest(message);
		// Adds thread local properties.
		for (final String attributeName : this.threadAttributes) {
			final Object attributeValue = message.getObjectProperty(attributeName);
			if (attributeValue != null) {
				EnhancedJmsMessageConverter.setContextAttribute(attributeName, attributeValue);
			}
		}
	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#fromMessage(jakarta.jms.Message)
	 */
	@Override
	public Object fromMessage(
			final Message message) throws JMSException, MessageConversionException {

		// Gets parameters from the message.
		this.getParametersFromMessage(message);

		// Tries to convert using preferred classes.
		Object object = this.fromMessageUsingPreferedClassesInformation(message);

		// Tries converting the message using deprecated converters.
		if (object == null) {
			object = this.typableJmsMessageConverter.fromMessageUsingCurrentConverter(message);
		}
		if (object == null) {
			object = this.dtoJmsMessageConverter.fromMessageUsingCurrentConverter(message);
		}

		// Tries using simple converter.
		if (object == null) {
			object = super.fromMessage(message);
		}

		// Returns the object.
		return object;
	}

}
