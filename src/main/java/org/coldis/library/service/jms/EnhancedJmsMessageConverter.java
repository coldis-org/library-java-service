package org.coldis.library.service.jms;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.fury.BaseFury;
import org.coldis.library.dto.DtoOrigin;
import org.coldis.library.dto.DtoType;
import org.coldis.library.dto.DtoTypeMetadata;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.util.ClassUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Enhanced message converter.
 */
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
	 * Optimized serializer parameter.
	 */
	private static final String OPTIMIZED_SERIALIZER_PARAMETER = "optSer";

	/**
	 * JMS converter properties.
	 */
	@Autowired
	private JmsConverterProperties jmsConverterProperties;

	/**
	 * Object mapper.
	 */
	@Autowired
	@Qualifier("thinJsonMapper")
	private ObjectMapper objectMapper;

	/**
	 * Optimized serializer.
	 */
	@Autowired
	@Qualifier(value = "javaOptimizedSerializer")
	private BaseFury optimizedSerializer;

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
	 * Use optimized serializer.
	 */
	private Boolean useOptimizedSerializer = false;

	/** Constructor. */
	public EnhancedJmsMessageConverter(final Boolean useOptimizedSerializer) {
		super();
		this.useOptimizedSerializer = useOptimizedSerializer;
	}

	/**
	 * Gets the current async hop from the request.
	 *
	 * @return Current async hop.
	 */
	private Long getCurrentAsyncHop() {
		final Long currentAsyncHop = (Long) ThreadMapContextHolder.getAttribute(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER);
		return (currentAsyncHop == null ? 1 : currentAsyncHop);
	}

	/**
	 * Validates if the maximum number of async hops was exceeded.
	 */
	private void validateAsyncHops(
			final Object payload) {
		final Long maximumAsyncHops = this.jmsConverterProperties.getMaximumAsyncHops();
		if ((maximumAsyncHops > 0) && (this.getCurrentAsyncHop() > maximumAsyncHops)) {
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
		for (final String attributeName : this.jmsConverterProperties.getThreadAttributes()) {
			final Object attributeValue = ThreadMapContextHolder.getAttribute(attributeName);
			if (attributeValue != null) {
				message.setObjectProperty(attributeName, attributeValue);
			}
		}
	}

	/**
	 * Checks if the message is simple.
	 */
	private boolean isSimpleMessage(
			final Object payload) {
		return (payload instanceof Message) || (payload instanceof String) || (payload instanceof byte[]) || (payload instanceof Map<?, ?>);
	}

	/**
	 * Serializes the message.
	 *
	 * @param  payload Payload.
	 * @param  session Session.
	 * @return         Serialized message.
	 */
	private Message toSerializedMessage(
			final Object payload,
			final Session session) {
		Message message = null;
		try {
			final byte[] actualPayload = this.optimizedSerializer.serialize(payload);
			message = session.createBytesMessage();
			((BytesMessage) message).writeBytes(actualPayload);
			message.setBooleanProperty(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER, true);
		}
		// If the object cannot be serialized.
		catch (final Exception exception) {
			// Logs it.
			EnhancedJmsMessageConverter.LOGGER.error("Object could not be serialized: ", exception.getLocalizedMessage());
			EnhancedJmsMessageConverter.LOGGER.debug("Object could not be serialized.", exception);
		}
		return message;
	}

	/**
	 * Gets the preferred classes from the payload.
	 *
	 * @param  payload Payload.
	 * @return         Preferred classes.
	 */
	private List<String> getPreferedClassesFromPayload(
			final Object payload) {
		final DtoType dtoTypeAnnotation = Arrays.stream(payload.getClass().getAnnotationsByType(DtoType.class))
				.filter(dto -> "java".equals(dto.fileExtension())).findFirst().orElse(null);
		final DtoOrigin dtoOriginAnnotation = payload.getClass().getAnnotation(DtoOrigin.class);
		List<String> preferedClassesNames = (dtoTypeAnnotation != null
				? (List.of(payload.getClass().getName().toString(),
						new DtoTypeMetadata(payload.getClass().getName().toString(), dtoTypeAnnotation).getQualifiedName()))
				: dtoOriginAnnotation != null ? (List.of(dtoOriginAnnotation.originalClassName(), payload.getClass().getName().toString())) : null);
		preferedClassesNames = ((preferedClassesNames == null) || this.jmsConverterProperties.getOriginalTypePrecedence() ? preferedClassesNames
				: preferedClassesNames.reversed());
		return preferedClassesNames;
	}

	/**
	 * Converts the message to JSON.
	 *
	 * @param payload Payload.
	 * @param session Session.
	 */
	private Message toJsonMessage(
			final Object payload,
			final Session session) {
		Message message = null;
		// Serializes the payload with JSON serializer if preferred classes are
		// reacheable.
		final List<String> preferedClassesNames = this.getPreferedClassesFromPayload(payload);
		if (CollectionUtils.isNotEmpty(preferedClassesNames)) {
			try {
				message = session.createBytesMessage();
				((BytesMessage) message).writeBytes(this.objectMapper.writeValueAsBytes(payload));
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
		return message;
	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#toMessage(java.lang.Object,
	 *      jakarta.jms.Session)
	 */
	private Message toDynamicMessage(
			final Object payload,
			final Session session) throws MessageConversionException, JMSException {
		// Message.
		Message message = null;

		if (payload != null) {

			// Sends a non-simple message.
			if (!this.isSimpleMessage(payload)) {
				// If optimized serializer is enabled.
				if (this.useOptimizedSerializer) {
					message = this.toSerializedMessage(payload, session);
				}
				// If optimized serializer is not enabled.
				if (message == null) {
					message = this.toJsonMessage(payload, session);
				}
			}

			// Generates a simple message.
			if (message == null) {
				message = super.toMessage(payload, session);
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
		final Message message = this.toDynamicMessage(payload, session);

		// Adds parameters to the message.
		this.addParametersToMessage(message);

		// Returns the message.
		return message;

	}

	/**
	 * Converts the message to JSON.
	 *
	 * @param  message Message.
	 * @return         Object.
	 */
	private Object fromSerializedMessage(
			final BytesMessage bytesMessage) throws JMSException {
		Object object;
		final byte[] messageBytes = new byte[(int) bytesMessage.getBodyLength()];
		bytesMessage.readBytes(messageBytes);
		object = this.optimizedSerializer.deserialize(messageBytes);
		return object;
	}

	/**
	 * Gets the preferred class from the message.
	 *
	 * @param  message                Message.
	 * @return                        Preferred class.
	 * @throws JMSException           If the preferred class cannot be obtained.
	 * @throws ClassNotFoundException If the preferred class cannot be found.
	 * @throws LinkageError           If the preferred class cannot be loaded.
	 */
	private Class<?> getPreferedClassFromMessage(
			final Message message) throws JMSException, ClassNotFoundException, LinkageError {
		final String preferedClassesNamesAttribute = message.getStringProperty(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER);
		final List<String> preferedClassesNames = (StringUtils.isBlank(preferedClassesNamesAttribute) ? List.of()
				: List.of(preferedClassesNamesAttribute.split(",")));
		final List<String> availablePreferedClasses = preferedClassesNames.stream()
				.filter(className -> ClassUtils.isPresent(className, message.getClass().getClassLoader())).toList();
		final Class<?> preferedClass = (CollectionUtils.isEmpty(availablePreferedClasses) ? null
				: ClassUtils.forName(availablePreferedClasses.getFirst(), message.getClass().getClassLoader()));
		return preferedClass;
	}

	/**
	 * Converts the message to JSON.
	 *
	 * @param  message Message.
	 * @return         Object.
	 */
	private Object fromJsonMessage(
			final Message message) throws LinkageError {
		Object object = null;
		try {
			// Converts the message to the preferred class if available.
			final Class<?> preferedClass = this.getPreferedClassFromMessage(message);
			if (preferedClass != null) {
				if (message instanceof final TextMessage textMessage) {
					object = this.objectMapper.readValue(textMessage.getText(), preferedClass);
				}
				else if (message instanceof final BytesMessage bytesMessage) {
					final byte[] messageBytes = new byte[(int) bytesMessage.getBodyLength()];
					bytesMessage.readBytes(messageBytes);
					object = this.objectMapper.readValue(messageBytes, preferedClass);
				}
			}

		}
		// Logs errors.
		catch (final Exception exception) {
			EnhancedJmsMessageConverter.LOGGER.error("Object could not be converted from JSON: ", exception.getLocalizedMessage());
			EnhancedJmsMessageConverter.LOGGER.debug("Object could not be converted from JSON.", exception);
		}
		return object;
	}

	/**
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#fromMessage(jakarta.jms.Message)
	 */
	public Object fromDynamicMessage(
			final Message message) throws JMSException, MessageConversionException {
		// Object.
		Object object = null;

		// If it is an optimized serializer message.
		final boolean optimizedSerializerUsed = (message.propertyExists(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER)
				&& message.getBooleanProperty(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER));
		if (optimizedSerializerUsed && message instanceof final BytesMessage bytesMessage) {
			object = this.fromSerializedMessage(bytesMessage);
		}
		// If the message has a prefered type.
		else if (message.propertyExists(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER)) {
			object = this.fromJsonMessage(message);
		}

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
		final String destination = Objects.toString(message.getJMSDestination());
		final String[] destinationParts = destination.split("[\\[\\]]");
		final String convertedDestination = (destinationParts.length > 1 ? destinationParts[1] : destination).replaceAll("/", "-");
		if (!this.jmsConverterProperties.getMaximumAsyncHopsIgnoredFor(convertedDestination)) {
			currentAsyncHop = (currentAsyncHop == null ? 1 : currentAsyncHop + 1);
		}
		ThreadMapContextHolder.setAttribute(EnhancedJmsMessageConverter.CURRENT_ASYNC_HOP_PARAMETER, currentAsyncHop);
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
		for (final String attributeName : this.jmsConverterProperties.getThreadAttributes()) {
			final Object attributeValue = message.getObjectProperty(attributeName);
			if (attributeValue != null) {
				ThreadMapContextHolder.setAttribute(attributeName, attributeValue);
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
		final Object object = this.fromDynamicMessage(message);

		// Returns the object.
		return object;
	}

}
