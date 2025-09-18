package org.coldis.library.service.jms;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.fury.BaseFury;
import org.coldis.library.dto.DtoOrigin;
import org.coldis.library.dto.DtoType;
import org.coldis.library.dto.DtoTypeMetadata;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.service.helper.MultiLayerSessionHelper;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.util.ClassUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

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
	 * Session attribute.
	 */
	public static final String SESSION_ATTRIBUTE_PREFIX = "_SES_ATT_";

	/**
	 * Origin queue attribute.
	 */
	public static final String ORIGIN_DESTINATION_ATTRIBUTE = "originDestination";

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
	private final JmsConverterProperties jmsConverterProperties;

	/**
	 * Object mapper.
	 */
	private final ObjectMapper objectMapper;

	/**
	 * Optimized serializer.
	 */
	private final BaseFury optimizedSerializer;

	/** Use optimized serializer. */
	private Boolean useOptimizedSerializer = false;

	/**
	 * If the session info should be included in messages on message headers.
	 */
	private final Set<String> includeSessionAttributesAsMessageHeaders;

	/** Alternative class names (or parts). */
	private SequencedMap<String, String> alternativeClassNames;

	private final Map<String, Class<?>> preferredClassesCache = MapUtils.synchronizedMap(new HashMap<>());

	/** Constructor. */
	public EnhancedJmsMessageConverter(
			final JmsConverterProperties jmsConverterProperties,
			final ObjectMapper objectMapper,
			final BaseFury optimizedSerializer,
			final Boolean useOptimizedSerializer,
			final Set<String> includeSessionAttributesAsMessageHeaders) {
		super();
		this.jmsConverterProperties = jmsConverterProperties;
		this.objectMapper = objectMapper;
		this.optimizedSerializer = optimizedSerializer;
		this.useOptimizedSerializer = useOptimizedSerializer;
		this.includeSessionAttributesAsMessageHeaders = includeSessionAttributesAsMessageHeaders;
	}

	/** Constructor. */
	public EnhancedJmsMessageConverter(
			final JmsConverterProperties jmsConverterProperties,
			final ObjectMapper objectMapper,
			final BaseFury optimizedSerializer,
			final Set<String> includeSessionAttributesAsMessageHeaders) {
		this(jmsConverterProperties, objectMapper, optimizedSerializer, false, includeSessionAttributesAsMessageHeaders);
	}

	/** Clear preferred classes cache. */
	public void clearPreferredClassesCache() {
		this.preferredClassesCache.clear();
	}

	/**
	 * Gets the alternativeClassNames.
	 *
	 * @return The alternativeClassNames.
	 */
	private SequencedMap<String, String> getAlternativeClassNames() {
		this.alternativeClassNames = (this.alternativeClassNames == null ? new LinkedHashMap<>() : this.alternativeClassNames);
		if (!this.alternativeClassNames.containsKey("")) {
			this.alternativeClassNames.putFirst("", "");
		}
		return this.alternativeClassNames;
	}

	/**
	 * Sets the alternativeClassNames.
	 *
	 * @param alternativeClassNames New alternativeClassNames.
	 */
	public void setAlternativeClassNames(
			final SequencedMap<String, String> alternativeClassNames) {
		this.alternativeClassNames = alternativeClassNames;
	}

	/**
	 * Checks if the object is primitive.
	 *
	 * @param  object Object.
	 * @return        If the object is primitive.
	 */
	private Boolean isPrimitive(
			final Object object) {
		return (object instanceof String) || (object instanceof Integer) || (object instanceof Long) || (object instanceof Float) || (object instanceof Double)
				|| (object instanceof Boolean) || (object instanceof Byte);
	}

	/**
	 * Converts the object to JMS attribute.
	 *
	 * @param  object Object.
	 * @return        JMS attribute.
	 */
	private Object toJmsAttribute(
			final Object object) {
		return this.isPrimitive(object) ? object : Objects.toString(object);
	}

	/**
	 * Gets the current async hop from the request.
	 *
	 * @return Current async hop.
	 */
	private Long getCurrentAsyncHop() {
		final Long currentAsyncHop = (Long) ThreadMapContextHolder.getAttribute(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER);
		return (currentAsyncHop == null ? 1 : currentAsyncHop);
	}

	/**
	 * Adds parameters to the JMS message.
	 */
	private void addParametersToMessage(
			final Message message) throws JMSException {

		// Adds current async hop to message.
		message.setLongProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, this.getCurrentAsyncHop());

		// Sets session headers.
		if (CollectionUtils.isNotEmpty(this.includeSessionAttributesAsMessageHeaders)) {
			for (final String sessionAttribute : this.includeSessionAttributesAsMessageHeaders) {
				final Object sessionAttributeValue = MultiLayerSessionHelper.getAttribute(sessionAttribute);
				if (sessionAttributeValue != null) {
					message.setObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX + sessionAttribute,
							this.toJmsAttribute(sessionAttributeValue));
				}
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
				if ((this.optimizedSerializer != null) && this.useOptimizedSerializer) {
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
			final String preferedClassesNamesAttribute) throws JMSException, ClassNotFoundException, LinkageError {
		Class<?> preferedClass = null;

		// Checks if the preferred class was already cached.
		if (this.preferredClassesCache.containsKey(preferedClassesNamesAttribute)) {
			preferedClass = this.preferredClassesCache.get(preferedClassesNamesAttribute);
		}
		else {
			// Parses preferred classes.
			List<String> preferedClassesNames = (StringUtils.isBlank(preferedClassesNamesAttribute) ? List.of()
					: List.of(preferedClassesNamesAttribute.split(",")));

			// Adds alternatives classes names to preferred classes.
			preferedClassesNames = preferedClassesNames.stream().flatMap(className -> this.getAlternativeClassNames().entrySet().stream()
					.map(alternative -> className.replace(alternative.getKey(), alternative.getValue()))).distinct().toList();

			// Checks for available classes, and get the first available.
			final List<String> availablePreferedClasses = preferedClassesNames.stream()
					.filter(className -> ClassUtils.isPresent(className, Thread.currentThread().getContextClassLoader())).toList();
			preferedClass = (CollectionUtils.isEmpty(availablePreferedClasses) ? null
					: ClassUtils.forName(availablePreferedClasses.getFirst(), Thread.currentThread().getContextClassLoader()));
			if (preferedClass == null) {
				EnhancedJmsMessageConverter.LOGGER.error("Prefered class could not be loaded from name: " + preferedClassesNames);
			}
			// Caches the preferred class.
			this.preferredClassesCache.put(preferedClassesNamesAttribute, preferedClass);
		}

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
			final Class<?> preferedClass = this.getPreferedClassFromMessage(message.getStringProperty(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER));
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
		final boolean optimizedSerializerUsed = (this.optimizedSerializer != null)
				&& (message.propertyExists(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER)
						&& message.getBooleanProperty(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER));
		if (optimizedSerializerUsed && message instanceof final BytesMessage bytesMessage) {
			object = this.fromSerializedMessage(bytesMessage);
		}
		// If the message has a prefered type.
		else if (message.propertyExists(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER)) {
			object = this.fromJsonMessage(message);
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
		Long currentAsyncHop = (EnumerationUtils.toList(message.getPropertyNames())
				.contains(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER)
						? message.getLongProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER)
						: 0L);

		// Resets the hops if queue is ignored.
		final String destination = Objects.toString(message.getJMSDestination());
		final String[] destinationParts = destination.split("[\\[\\]]");
		final String convertedDestination = (destinationParts.length > 1 ? destinationParts[1] : destination).replaceAll("/", "-");
		if (this.jmsConverterProperties.getMaximumAsyncHopsIgnoredFor(convertedDestination)) {
			currentAsyncHop = 0L;
		}

		// Increments the hops if not.
		else {
			currentAsyncHop = (currentAsyncHop == null ? 1L : currentAsyncHop + 1);
		}

		// Sets the message attribute.
		ThreadMapContextHolder.setAttribute(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, currentAsyncHop);
		return currentAsyncHop;
	}

	/**
	 * Get parameters from the JMS message.
	 */
	@SuppressWarnings("unchecked")
	private void getParametersFromMessage(
			final Message message) throws JMSException {
		// Increments the current async hop on the request.
		this.incrementCurrentAsyncHopOnRequest(message);

		// Sets session headers.
		for (final Object sessionPropertyNameObject : EnumerationUtils.toList(message.getPropertyNames())) {
			if (sessionPropertyNameObject instanceof final String sessionPropertyName
					&& sessionPropertyName.startsWith(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX)) {
				final String sessionAttributeName = sessionPropertyName.substring(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX.length());
				final Object sessionAttributeValue = message.getObjectProperty(sessionPropertyName);
				if (sessionAttributeValue != null) {
					MultiLayerSessionHelper.getThreadSession().put(sessionAttributeName, sessionAttributeValue);
				}
			}
		}

		// Sets the origin queue.
		final String destination = Objects.toString(ObjectUtils.firstNonNull(ReflectionHelper.getAttribute(message.getJMSDestination(), "address"),
				ReflectionHelper.getAttribute(message.getJMSDestination(), "name"), "unknown-queue"));
		MultiLayerSessionHelper.getThreadSession().put(EnhancedJmsMessageConverter.ORIGIN_DESTINATION_ATTRIBUTE, destination);

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
