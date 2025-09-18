package org.coldis.library.service.jms;

import java.io.Serializable;
import java.util.Enumeration;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageEOFException;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;

/**
 * JMS message helper.
 */
public class JmsMessageHelper {

	/**
	 * Copies the headers and properties from one message to another.
	 *
	 * @param src Source message.
	 * @param dst Destination message.
	 */
	private static void copyHeadersAndProperties(
			final Message src,
			final Message dst) throws JMSException {
		// Safe/portable header fields you usually want to carry over:
		dst.setJMSCorrelationID(src.getJMSCorrelationID());
		dst.setJMSReplyTo(src.getJMSReplyTo());
		dst.setJMSType(src.getJMSType());

		// Application properties
		@SuppressWarnings("unchecked")
		final Enumeration<String> names = src.getPropertyNames();
		while (names.hasMoreElements()) {
			final String name = names.nextElement();
			dst.setObjectProperty(name, src.getObjectProperty(name));
		}

	}

	/**
	 * Copies a message.
	 * 
	 * @param  session      Session to create the new message.
	 * @param  src          Source message.
	 * @return              The copied message.
	 * @throws JMSException If there is any problem.
	 */
	public static Message copy(
			final Session session,
			final Message src) throws JMSException {
		Message dst;

		if (src instanceof TextMessage) {
			dst = session.createTextMessage(((TextMessage) src).getText());
		}
		else if (src instanceof BytesMessage) {
			final BytesMessage s = (BytesMessage) src;
			s.reset();
			final BytesMessage d = session.createBytesMessage();
			try {
				final byte[] buf = new byte[8192];
				while (true) {
					final int n = s.readBytes(buf);
					if (n == -1) {
						break;
					}
					if (n > 0) {
						d.writeBytes(buf, 0, n);
					}
				}
			}
			catch (final MessageEOFException ignore) {
				// reached end
			}
			finally {
				s.reset(); // leave src usable for others
			}
			dst = d;
		}
		else if (src instanceof MapMessage) {
			final MapMessage s = (MapMessage) src;
			final MapMessage d = session.createMapMessage();
			@SuppressWarnings("unchecked")
			final Enumeration<String> names = s.getMapNames();
			while (names.hasMoreElements()) {
				final String name = names.nextElement();
				d.setObject(name, s.getObject(name));
			}
			dst = d;
		}
		else if (src instanceof ObjectMessage) {
			final Serializable obj = ((ObjectMessage) src).getObject();
			dst = session.createObjectMessage(obj);
		}
		else if (src instanceof StreamMessage) {
			final StreamMessage s = (StreamMessage) src;
			s.reset();
			final StreamMessage d = session.createStreamMessage();
			try {
				while (true) {
					d.writeObject(s.readObject());
				}
			}
			catch (final MessageEOFException ignore) {
				// done
			}
			finally {
				s.reset();
			}
			dst = d;
		}
		else {
			// plain Message (no body)
			dst = session.createMessage();
		}

		JmsMessageHelper.copyHeadersAndProperties(src, dst);
		return dst;
	}

}
