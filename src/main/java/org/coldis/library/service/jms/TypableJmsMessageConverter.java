//package org.coldis.library.service.jms;
//
//import org.apache.commons.lang3.reflect.MethodUtils;
//import org.coldis.library.model.Typable;
//import org.coldis.library.serialization.ObjectMapperHelper;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
//import org.springframework.jms.support.converter.MessageConversionException;
//import org.springframework.jms.support.converter.SimpleMessageConverter;
//import org.springframework.stereotype.Component;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//import jakarta.jms.JMSException;
//import jakarta.jms.Message;
//import jakarta.jms.Session;
//import jakarta.jms.TextMessage;
//
///**
// * Typed object JMS message converter.
// */
//@Component
//@Qualifier("typedJmsMessageConverter")
//@ConditionalOnClass(value = Message.class)
//@ConditionalOnProperty(
//		name = "org.coldis.configuration.jms-message-converter-typed-enabled",
//		havingValue = "true",
//		matchIfMissing = false
//)
//public class TypableJmsMessageConverter extends SimpleMessageConverter {
//
//	/**
//	 * Logger.
//	 */
//	private static final Logger LOGGER = LoggerFactory.getLogger(TypableJmsMessageConverter.class);
//
//	/**
//	 * Object mapper.
//	 */
//	@Autowired
//	private ObjectMapper objectMapper;
//
//	/**
//	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#fromMessage(jakarta.jms.Message)
//	 */
//	@Override
//	public Object fromMessage(
//			final Message message) throws JMSException, MessageConversionException {
//		// Object.
//		Object object = null;
//		// If the target class is a typed object.
//		if ((message instanceof TextMessage) && ((TextMessage) message).getText().contains("typeName")) {
//			// Tries to convert the JSON object.
//			try {
//				object = ObjectMapperHelper.deserialize(this.objectMapper, ((TextMessage) message).getText(), Typable.class, false);
//			}
//			// If the object cannot be converted from JSON.
//			catch (final Exception exception) {
//				// Logs it.
//				TypableJmsMessageConverter.LOGGER.error("Object could not be converted from JSON: ", exception.getLocalizedMessage());
//				TypableJmsMessageConverter.LOGGER.debug("Object could not be converted from JSON.", exception);
//			}
//		}
//		// If the object could not be converted from JSON.
//		if (object == null) {
//			// Tries using default converter.
//			object = super.fromMessage(message);
//		}
//		// Returns the object.
//		return object;
//	}
//
//	/**
//	 * @see org.springframework.jms.support.converter.SimpleMessageConverter#toMessage(java.lang.Object,
//	 *      jakarta.jms.Session)
//	 */
//	@Override
//	public Message toMessage(
//			final Object payload,
//			final Session session) throws JMSException, MessageConversionException {
//		// Actual payload.
//		Object actualPayload = payload;
//		// If the payload is a typed object.
//		if ((payload != null) && (MethodUtils.getMatchingMethod(payload.getClass(), "getTypeName") != null)) {
//			// Tries to serialize the payload.
//			try {
//				actualPayload = ObjectMapperHelper.serialize(this.objectMapper, actualPayload, null, false); // TODO Add
//				// views.
//			}
//			// If the object cannot be converted from JSON.
//			catch (final Exception exception) {
//				// Logs it.
//				TypableJmsMessageConverter.LOGGER.error("Object could not be serialized to JSON: ", exception.getLocalizedMessage());
//				TypableJmsMessageConverter.LOGGER.debug("Object could not be serialized to JSON.", exception);
//			}
//		}
//		// Returns the message.
//		return super.toMessage(actualPayload, session);
//	}
//
//}
