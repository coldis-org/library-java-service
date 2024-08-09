package org.coldis.library.service.jms;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.fury.Fury;
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
	private Fury optimizedSerializer;

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
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#toMessage(java.lang.Object,
	 *      jakarta.jms.Session)
	 */
	private Message toMessageUsingDtoInformation(
			final Object payload,
			final Session session) {
		// Message.
		Message message = null;

		if (payload != null) {

			// If optimized serializer is enabled.
			if (this.useOptimizedSerializer) {
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
			}

			// If optimized serializer is not enabled.
			else {

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
	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#fromMessage(jakarta.jms.Message)
	 */
	public Object fromMessageUsingPreferedClassesInformation(
			final Message message) throws JMSException, MessageConversionException {
		// Object.
		Object object = null;
		final TextMessage textMessage = (message instanceof TextMessage ? (TextMessage) message : null);
		final BytesMessage bytesMessage = (message instanceof BytesMessage ? (BytesMessage) message : null);

		// If it is an optimized serializer message.
		final boolean optimizedSerializerUsed = message.getBooleanProperty(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER);
		if (optimizedSerializerUsed && (bytesMessage != null)) {
			final byte[] messageBytes = new byte[(int) bytesMessage.getBodyLength()];
			bytesMessage.readBytes(messageBytes);
			object = this.optimizedSerializer.deserialize(messageBytes);
		}

		// If the message is a text or bytes message.
		else if ((textMessage != null) || (bytesMessage != null)) {
			try {

				// Converts the message to the preferred class if available.
				final Class<?> preferedClass = this.getPreferedClassFromMessage(message);
				if (preferedClass != null) {
					if (textMessage != null) {
						object = this.objectMapper.readValue(textMessage.getText(), preferedClass);
					}
					else {
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
