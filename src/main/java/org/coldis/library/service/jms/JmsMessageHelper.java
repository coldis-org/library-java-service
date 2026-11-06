package org.coldis.library.service.jms;

import java.util.Enumeration;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.MessageFormatException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

/**
 * JMS message helper.
 */
public class JmsMessageHelper {

	/**
	 * Gets a long property, tolerating a missing or null-valued property (the JMS
	 * long getter fails on a property that exists with a null value). Otherwise
	 * follows the JMS long conversion: integral numbers and numeric strings.
	 *
	 * @param  message                Message.
	 * @param  propertyName           Property name.
	 * @param  defaultValue           Value when the property is missing or null.
	 * @return                        The property value.
	 * @throws MessageFormatException If the property is not convertible to long.
	 * @throws JMSException           If the property cannot be read.
	 */
	public static Long getLongProperty(
			final Message message,
			final String propertyName,
			final Long defaultValue) throws JMSException {
		final Object propertyValue = message.getObjectProperty(propertyName);
		final Long longValue;
		if (propertyValue == null) {
			longValue = defaultValue;
		}
		else if ((propertyValue instanceof Long) || (propertyValue instanceof Integer) || (propertyValue instanceof Short)
				|| (propertyValue instanceof Byte)) {
			longValue = ((Number) propertyValue).longValue();
		}
		else if (propertyValue instanceof final String stringValue) {
			try {
				longValue = Long.valueOf(stringValue);
			}
			catch (final NumberFormatException exception) {
				final MessageFormatException formatException = new MessageFormatException("Property '" + propertyName + "' is not a long: " + stringValue);
				formatException.initCause(exception);
				throw formatException;
			}
		}
		else {
			throw new MessageFormatException("Property '" + propertyName + "' of type " + propertyValue.getClass().getName() + " is not convertible to long.");
		}
		return longValue;
	}

	/**
	 * Copies the headers and properties from one message to another.
	 *
	 * @param sourceMessage Source message.
	 * @param targetMessage Target message.
	 */
	private static void copyHeadersAndProperties(
			final Message sourceMessage,
			final Message targetMessage) throws JMSException {
		// Portable header fields worth carrying over.
		targetMessage.setJMSCorrelationID(sourceMessage.getJMSCorrelationID());
		targetMessage.setJMSReplyTo(sourceMessage.getJMSReplyTo());
		targetMessage.setJMSType(sourceMessage.getJMSType());

		// Application properties.
		@SuppressWarnings("unchecked")
		final Enumeration<String> propertyNames = sourceMessage.getPropertyNames();
		while (propertyNames.hasMoreElements()) {
			final String propertyName = propertyNames.nextElement();
			targetMessage.setObjectProperty(propertyName, sourceMessage.getObjectProperty(propertyName));
		}
	}

	/**
	 * Copies a bytes message body.
	 *
	 * @param  session            Session to create the new message.
	 * @param  sourceBytesMessage Source message.
	 * @return                    The copied message.
	 */
	private static BytesMessage copyBytesMessage(
			final Session session,
			final BytesMessage sourceBytesMessage) throws JMSException {
		final BytesMessage targetBytesMessage = session.createBytesMessage();
		sourceBytesMessage.reset();
		try {
			final byte[] body = new byte[(int) sourceBytesMessage.getBodyLength()];
			sourceBytesMessage.readBytes(body);
			targetBytesMessage.writeBytes(body);
		}
		// Leaves the source usable for others.
		finally {
			sourceBytesMessage.reset();
		}
		return targetBytesMessage;
	}

	/**
	 * Copies a map message body.
	 *
	 * @param  session          Session to create the new message.
	 * @param  sourceMapMessage Source message.
	 * @return                  The copied message.
	 */
	private static MapMessage copyMapMessage(
			final Session session,
			final MapMessage sourceMapMessage) throws JMSException {
		final MapMessage targetMapMessage = session.createMapMessage();
		@SuppressWarnings("unchecked")
		final Enumeration<String> entryNames = sourceMapMessage.getMapNames();
		while (entryNames.hasMoreElements()) {
			final String entryName = entryNames.nextElement();
			targetMapMessage.setObject(entryName, sourceMapMessage.getObject(entryName));
		}
		return targetMapMessage;
	}

	/**
	 * Copies a stream message body (a stream message only signals its end by
	 * throwing on read).
	 *
	 * @param  session             Session to create the new message.
	 * @param  sourceStreamMessage Source message.
	 * @return                     The copied message.
	 */
	private static StreamMessage copyStreamMessage(
			final Session session,
			final StreamMessage sourceStreamMessage) throws JMSException {
		final StreamMessage targetStreamMessage = session.createStreamMessage();
		sourceStreamMessage.reset();
		try {
			boolean endOfStream = false;
			while (!endOfStream) {
				try {
					targetStreamMessage.writeObject(sourceStreamMessage.readObject());
				}
				catch (final MessageEOFException endOfStreamException) {
					endOfStream = true;
				}
			}
		}
		// Leaves the source usable for others.
		finally {
			sourceStreamMessage.reset();
		}
		return targetStreamMessage;
	}

	/**
	 * Copies a message (body, portable headers and properties).
	 *
	 * @param  session       Session to create the new message.
	 * @param  sourceMessage Source message.
	 * @return               The copied message.
	 * @throws JMSException  If there is any problem.
	 */
	public static Message copy(
			final Session session,
			final Message sourceMessage) throws JMSException {
		final Message targetMessage;
		if (sourceMessage instanceof final TextMessage sourceTextMessage) {
			targetMessage = session.createTextMessage(sourceTextMessage.getText());
		}
		else if (sourceMessage instanceof final BytesMessage sourceBytesMessage) {
			targetMessage = JmsMessageHelper.copyBytesMessage(session, sourceBytesMessage);
		}
		else if (sourceMessage instanceof final MapMessage sourceMapMessage) {
			targetMessage = JmsMessageHelper.copyMapMessage(session, sourceMapMessage);
		}
		else if (sourceMessage instanceof final ObjectMessage sourceObjectMessage) {
			targetMessage = session.createObjectMessage(sourceObjectMessage.getObject());
		}
		else if (sourceMessage instanceof final StreamMessage sourceStreamMessage) {
			targetMessage = JmsMessageHelper.copyStreamMessage(session, sourceStreamMessage);
		}
		// Plain message (no body).
		else {
			targetMessage = session.createMessage();
		}
		JmsMessageHelper.copyHeadersAndProperties(sourceMessage, targetMessage);
		return targetMessage;
	}

}
