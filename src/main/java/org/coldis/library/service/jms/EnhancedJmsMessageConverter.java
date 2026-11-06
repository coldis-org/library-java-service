package org.coldis.library.service.jms;

import java.io.IOException;
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
import org.apache.fory.BaseFory;
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
	 * Optimized serializer used to write messages.
	 */
	private BaseFory optimizedSerializer;

	/**
	 * Optimized deserializer used to read messages. Allowed to differ from
	 * the serializer when, e.g., the producer needs a Dto-prioritized
	 * configuration so that a peer reading with the Model-prioritized one
	 * canonicalizes to the Model.
	 */
	private BaseFory optimizedDeserializer;

	/** Use optimized serializer. */
	private Boolean useOptimizedSerializer = false;

	/**
	 * If the session info should be included in messages on message headers.
	 */
	private final Set<String> includeSessionAttributesAsMessageHeaders;

	/** Alternative class names (or parts). */
	private SequencedMap<String, String> alternativeClassNames;

	/** Maximum number of cached preferred classes header values. */
	private static final int PREFERRED_CLASSES_CACHE_MAXIMUM_SIZE = 1024;

	private final Map<String, Class<?>> preferredClassesCache = MapUtils.synchronizedMap(new HashMap<>());

	/**
	 * Constructor with separate optimized serializer and deserializer.
	 *
	 * @param jmsConverterProperties                   JMS converter properties.
	 * @param objectMapper                             Object mapper.
	 * @param optimizedSerializer                      Optimized serializer
	 *                                                     used to write
	 *                                                     messages.
	 * @param optimizedDeserializer                    Optimized deserializer
	 *                                                     used to read
	 *                                                     messages.
	 * @param useOptimizedSerializer                   Whether writes should
	 *                                                     default to optimized
	 *                                                     serialization.
	 * @param includeSessionAttributesAsMessageHeaders Session attributes to
	 *                                                     include as headers.
	 */
	public EnhancedJmsMessageConverter(
			final JmsConverterProperties jmsConverterProperties,
			final ObjectMapper objectMapper,
			final BaseFory optimizedSerializer,
			final BaseFory optimizedDeserializer,
			final Boolean useOptimizedSerializer,
			final Set<String> includeSessionAttributesAsMessageHeaders) {
		super();
		this.jmsConverterProperties = jmsConverterProperties;
		this.objectMapper = objectMapper;
		this.optimizedSerializer = optimizedSerializer;
		this.optimizedDeserializer = optimizedDeserializer;
		this.useOptimizedSerializer = useOptimizedSerializer;
		this.includeSessionAttributesAsMessageHeaders = includeSessionAttributesAsMessageHeaders;
	}

	/** Constructor using the same instance as both serializer and deserializer. */
	public EnhancedJmsMessageConverter(
			final JmsConverterProperties jmsConverterProperties,
			final ObjectMapper objectMapper,
			final BaseFory optimizedSerializerAndDeserializer,
			final Boolean useOptimizedSerializer,
			final Set<String> includeSessionAttributesAsMessageHeaders) {
		this(jmsConverterProperties, objectMapper, optimizedSerializerAndDeserializer, optimizedSerializerAndDeserializer, useOptimizedSerializer,
				includeSessionAttributesAsMessageHeaders);
	}

	/** Constructor using the same instance as both serializer and deserializer. */
	public EnhancedJmsMessageConverter(
			final JmsConverterProperties jmsConverterProperties,
			final ObjectMapper objectMapper,
			final BaseFory optimizedSerializerAndDeserializer,
			final Set<String> includeSessionAttributesAsMessageHeaders) {
		this(jmsConverterProperties, objectMapper, optimizedSerializerAndDeserializer, optimizedSerializerAndDeserializer, false,
				includeSessionAttributesAsMessageHeaders);
	}

	/** Clear preferred classes cache. */
	public void clearPreferredClassesCache() {
		this.preferredClassesCache.clear();
	}

	/**
	 * Replaces the optimized serializer.
	 *
	 * @param optimizedSerializer New optimized serializer.
	 */
	public void setOptimizedSerializer(
			final BaseFory optimizedSerializer) {
		this.optimizedSerializer = optimizedSerializer;
	}

	/**
	 * Replaces the optimized deserializer.
	 *
	 * @param optimizedDeserializer New optimized deserializer.
	 */
	public void setOptimizedDeserializer(
			final BaseFory optimizedDeserializer) {
		this.optimizedDeserializer = optimizedDeserializer;
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
			EnhancedJmsMessageConverter.LOGGER.warn("Object could not be serialized: {}", exception.getLocalizedMessage());
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
				EnhancedJmsMessageConverter.LOGGER.warn("Object could not be serialized to JSON: {}", exception.getLocalizedMessage());
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
		final byte[] messageBytes = new byte[(int) bytesMessage.getBodyLength()];
		bytesMessage.readBytes(messageBytes);
		try {
			return this.optimizedDeserializer.deserialize(messageBytes);
		}
		catch (final RuntimeException exception) {
			EnhancedJmsMessageConverter.LOGGER.warn("Object could not be deserialized: {}", exception.getLocalizedMessage());
			EnhancedJmsMessageConverter.LOGGER.debug("Object could not be deserialized.", exception);
			throw exception;
		}
	}

	/**
	 * Checks if a class is a DTO type or origin.
	 *
	 * @param  candidateClass Class.
	 * @return                If the class is annotated as a DTO type or origin.
	 */
	private boolean isDtoClass(
			final Class<?> candidateClass) {
		return (candidateClass.getAnnotationsByType(DtoType.class).length > 0) || candidateClass.isAnnotationPresent(DtoOrigin.class);
	}

	/**
	 * Gets the preferred class from the message.
	 *
	 * @param  preferedClassesNamesAttribute Preferred classes header value.
	 * @return                               Preferred class (a DTO type or
	 *                                           origin), or null.
	 * @throws ClassNotFoundException        If the preferred class cannot be
	 *                                           found.
	 * @throws LinkageError           If the preferred class cannot be loaded.
	 */
	private Class<?> getPreferedClassFromMessage(
			final String preferedClassesNamesAttribute) throws ClassNotFoundException, LinkageError {
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

			// Gets the first available class that is a DTO type or origin (the only
			// classes this converter names in the header). Classes are loaded without
			// being initialized, so a rejected class runs no code.
			final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
			final List<String> availablePreferedClasses = preferedClassesNames.stream()
					.filter(className -> ClassUtils.isPresent(className, classLoader)).toList();
			// Stops at the first DTO class: later candidates are not loaded.
			for (int candidateIndex = 0; (preferedClass == null) && (candidateIndex < availablePreferedClasses.size()); candidateIndex++) {
				final Class<?> candidateClass = ClassUtils.forName(availablePreferedClasses.get(candidateIndex), classLoader);
				if (this.isDtoClass(candidateClass)) {
					preferedClass = candidateClass;
				}
				else {
					EnhancedJmsMessageConverter.LOGGER.warn("Prefered class '{}' is not a DTO type or origin and is not used.", candidateClass.getName());
				}
			}
			if (preferedClass == null) {
				EnhancedJmsMessageConverter.LOGGER.error("Prefered class could not be loaded from name: {}", preferedClassesNames);
			}

			// Caches the preferred class (bounded: header values come from producers).
			if (this.preferredClassesCache.size() < EnhancedJmsMessageConverter.PREFERRED_CLASSES_CACHE_MAXIMUM_SIZE) {
				this.preferredClassesCache.put(preferedClassesNamesAttribute, preferedClass);
			}
		}

		return preferedClass;
	}

	/**
	 * Converts the message to JSON. A message whose preferred class cannot be
	 * resolved is left for the simple converter (its body untouched); a body that
	 * does not convert to the resolved class fails the conversion.
	 *
	 * @param  message                    Message.
	 * @return                            Object, or null when no preferred class
	 *                                        resolves.
	 * @throws JMSException               If the message cannot be read.
	 * @throws MessageConversionException If the body does not convert to the
	 *                                        preferred class.
	 */
	private Object fromJsonMessage(
			final Message message) throws JMSException, MessageConversionException {
		Object object = null;

		// Resolves the preferred class.
		Class<?> preferedClass = null;
		try {
			preferedClass = this.getPreferedClassFromMessage(message.getStringProperty(EnhancedJmsMessageConverter.PREFERED_TYPE_PARAMETER));
		}
		catch (final ClassNotFoundException | LinkageError exception) {
			EnhancedJmsMessageConverter.LOGGER.error("Prefered class could not be loaded: {}", exception.getLocalizedMessage());
			EnhancedJmsMessageConverter.LOGGER.debug("Prefered class could not be loaded.", exception);
		}

		// Converts the message to the preferred class if available.
		if (preferedClass != null) {
			try {
				if (message instanceof final TextMessage textMessage) {
					object = this.objectMapper.readValue(textMessage.getText(), preferedClass);
				}
				else if (message instanceof final BytesMessage bytesMessage) {
					final byte[] messageBytes = new byte[(int) bytesMessage.getBodyLength()];
					bytesMessage.readBytes(messageBytes);
					object = this.objectMapper.readValue(messageBytes, preferedClass);
				}
			}
			catch (final IOException | IllegalArgumentException exception) {
				throw new MessageConversionException("Object could not be converted from JSON to " + preferedClass.getName() + ".", exception);
			}
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
		final boolean messageMarkedOptimized = message.propertyExists(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER)
				&& message.getBooleanProperty(EnhancedJmsMessageConverter.OPTIMIZED_SERIALIZER_PARAMETER);
		final boolean optimizedSerializerUsed = (this.optimizedDeserializer != null) && messageMarkedOptimized;
		if (optimizedSerializerUsed && message instanceof final BytesMessage bytesMessage) {
			object = this.fromSerializedMessage(bytesMessage);
		}
		// Producer explicitly marked the wire as optimized but this converter has no deserializer
		// to decode it. Refuse to fall through to JSON — Fory wire bytes are not parseable as JSON
		// and the silent attempt only hides the configuration mismatch.
		else if (messageMarkedOptimized) {
			throw new MessageConversionException("Message marked as optimized-serialized but no optimized deserializer is configured on this converter.");
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
	private Long incrementCurrentAsyncHopOnRequest(
			final Message message) throws JMSException {
		Long currentAsyncHop = JmsMessageHelper.getLongProperty(message, ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, 0L);

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
