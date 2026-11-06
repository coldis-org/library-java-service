package org.coldis.library.test.service.jms;

import java.util.ArrayList;
import java.util.List;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.JmsMessageHelper;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
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
 * {@link JmsMessageHelper} tests, with messages from a real broker session.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JmsMessageHelper — copy and long property")
public class JmsMessageHelperTest extends ContainerTestHelper {

	/** Long property name. */
	private static final String LONG_PROPERTY = "testLong";

	/** Connection factory. */
	@Autowired
	private ConnectionFactory connectionFactory;

	/**
	 * Opens a connection to the broker.
	 *
	 * @return The connection.
	 */
	private Connection openConnection() throws Exception {
		return ((ActiveMQConnectionFactory) ConnectionFactoryUnwrapper.unwrap(this.connectionFactory)).createConnection();
	}

	/**
	 * Sets the test properties on a message.
	 *
	 * @param message Message.
	 */
	private void setTestProperties(
			final Message message) throws Exception {
		message.setStringProperty("stringProperty", "value");
		message.setLongProperty("longProperty", 7L);
		message.setJMSCorrelationID("correlation");
		message.setJMSType("type");
	}

	/**
	 * Checks the test properties on a copied message.
	 *
	 * @param copiedMessage Copied message.
	 */
	private void assertTestProperties(
			final Message copiedMessage) throws Exception {
		Assertions.assertEquals("value", copiedMessage.getStringProperty("stringProperty"));
		Assertions.assertEquals(7L, copiedMessage.getLongProperty("longProperty"));
		Assertions.assertEquals("correlation", copiedMessage.getJMSCorrelationID());
		Assertions.assertEquals("type", copiedMessage.getJMSType());
	}

	/**
	 * Tests copying each message type.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Copies text, bytes, map, object, stream and plain messages with their properties")
	public void testCopyEachMessageType() throws Exception {
		try (Connection connection = this.openConnection(); Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {

			final TextMessage textMessage = session.createTextMessage("100% text");
			this.setTestProperties(textMessage);
			final TextMessage copiedTextMessage = (TextMessage) JmsMessageHelper.copy(session, textMessage);
			Assertions.assertEquals("100% text", copiedTextMessage.getText());
			this.assertTestProperties(copiedTextMessage);

			final BytesMessage bytesMessage = session.createBytesMessage();
			bytesMessage.writeBytes(new byte[] { 1, 2, 3, 4 });
			this.setTestProperties(bytesMessage);
			final BytesMessage copiedBytesMessage = (BytesMessage) JmsMessageHelper.copy(session, bytesMessage);
			copiedBytesMessage.reset();
			final byte[] copiedBody = new byte[(int) copiedBytesMessage.getBodyLength()];
			copiedBytesMessage.readBytes(copiedBody);
			Assertions.assertArrayEquals(new byte[] { 1, 2, 3, 4 }, copiedBody);
			this.assertTestProperties(copiedBytesMessage);
			final byte[] sourceBodyAfterCopy = new byte[(int) bytesMessage.getBodyLength()];
			bytesMessage.readBytes(sourceBodyAfterCopy);
			Assertions.assertArrayEquals(new byte[] { 1, 2, 3, 4 }, sourceBodyAfterCopy);

			final MapMessage mapMessage = session.createMapMessage();
			mapMessage.setString("first", "one");
			mapMessage.setInt("second", 2);
			this.setTestProperties(mapMessage);
			final MapMessage copiedMapMessage = (MapMessage) JmsMessageHelper.copy(session, mapMessage);
			Assertions.assertEquals("one", copiedMapMessage.getString("first"));
			Assertions.assertEquals(2, copiedMapMessage.getInt("second"));
			this.assertTestProperties(copiedMapMessage);

			final ObjectMessage objectMessage = session.createObjectMessage(new ArrayList<>(List.of("first", "second")));
			this.setTestProperties(objectMessage);
			final ObjectMessage copiedObjectMessage = (ObjectMessage) JmsMessageHelper.copy(session, objectMessage);
			Assertions.assertEquals(List.of("first", "second"), copiedObjectMessage.getObject());
			this.assertTestProperties(copiedObjectMessage);

			final StreamMessage streamMessage = session.createStreamMessage();
			streamMessage.writeString("first");
			streamMessage.writeLong(2L);
			this.setTestProperties(streamMessage);
			final StreamMessage copiedStreamMessage = (StreamMessage) JmsMessageHelper.copy(session, streamMessage);
			copiedStreamMessage.reset();
			Assertions.assertEquals("first", copiedStreamMessage.readString());
			Assertions.assertEquals(2L, copiedStreamMessage.readLong());
			Assertions.assertThrows(MessageEOFException.class, copiedStreamMessage::readObject);
			this.assertTestProperties(copiedStreamMessage);
			Assertions.assertEquals("first", streamMessage.readString());
			Assertions.assertEquals(2L, streamMessage.readLong());

			final Message plainMessage = session.createMessage();
			this.setTestProperties(plainMessage);
			this.assertTestProperties(JmsMessageHelper.copy(session, plainMessage));
		}
	}

	/**
	 * Tests that object messages holding classes outside the deserialization
	 * allow list are not deserialized (here, by the copy).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Object message bodies outside the deserialization allow list are refused")
	public void testDeserializationAllowList() throws Exception {
		try (Connection connection = this.openConnection(); Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
			final ObjectMessage allowedMessage = session.createObjectMessage(new ArrayList<>(List.of("allowed")));
			Assertions.assertEquals(List.of("allowed"), ((ObjectMessage) JmsMessageHelper.copy(session, allowedMessage)).getObject());

			final ObjectMessage refusedMessage = session.createObjectMessage(new java.net.URI("https://example.com"));
			Assertions.assertThrows(JMSException.class, () -> JmsMessageHelper.copy(session, refusedMessage));
		}
	}

	/**
	 * Tests reading long properties.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Reads long properties: missing or null as default, integral numbers and numeric strings, rejects others")
	public void testGetLongProperty() throws Exception {
		try (Connection connection = this.openConnection(); Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
			final Message message = session.createMessage();
			Assertions.assertEquals(5L, JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));

			message.setObjectProperty(JmsMessageHelperTest.LONG_PROPERTY, null);
			Assertions.assertEquals(5L, JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));

			message.setLongProperty(JmsMessageHelperTest.LONG_PROPERTY, 11L);
			Assertions.assertEquals(11L, JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));

			message.setIntProperty(JmsMessageHelperTest.LONG_PROPERTY, 12);
			Assertions.assertEquals(12L, JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));

			message.setStringProperty(JmsMessageHelperTest.LONG_PROPERTY, "13");
			Assertions.assertEquals(13L, JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));

			message.setStringProperty(JmsMessageHelperTest.LONG_PROPERTY, "not a number");
			final MessageFormatException notNumberException = Assertions.assertThrows(MessageFormatException.class,
					() -> JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));
			Assertions.assertInstanceOf(NumberFormatException.class, notNumberException.getCause());

			message.setDoubleProperty(JmsMessageHelperTest.LONG_PROPERTY, 2.7);
			Assertions.assertThrows(MessageFormatException.class, () -> JmsMessageHelper.getLongProperty(message, JmsMessageHelperTest.LONG_PROPERTY, 5L));
		}
	}

}
