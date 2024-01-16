package org.coldis.library.test.service.jms;

import java.util.List;

import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JMS message converter test.
 */
@EnableJms
@SpringBootTest(
		webEnvironment = WebEnvironment.DEFINED_PORT,
		properties = { "org.coldis.configuration.jms-message-converter-default-enabled=false",
				"org.coldis.configuration.jms-message-converter-dto-enabled=true" }
)
public class DtoJmsMessageConverterTest {

	/**
	 * Test data.
	 */
	private static final List<DtoTestObject> TEST_DATA = List.of(new DtoTestObject(1L, "2", "3", 4, new int[] { 5, 6 }, 7),
			new DtoTestObject(2L, "3", "5", 5, new int[] { 6, 7 }, 8), new DtoTestObject(3L, "4", "5", 6, new int[] { 7, 8 }, 9));

	/**
	 * Current test message.
	 */
	private static DtoTestObjectDto currentTestMessage;

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * JMS template.
	 */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * Gets the currentTestMessage.
	 *
	 * @return The currentTestMessage.
	 */
	private DtoTestObjectDto getCurrentTestMessage() {
		return DtoJmsMessageConverterTest.currentTestMessage;
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(destination = "jmsTest")
	public void consumeMessage(
			final DtoTestObjectDto message) {
		DtoJmsMessageConverterTest.currentTestMessage = message;
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testMessageConverter() throws Exception {
		// For each test data.
		for (final DtoTestObject testData : DtoJmsMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("jmsTest", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(this::getCurrentTestMessage,
					message -> (message != null) && message.equals(ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true)),
					TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}

	}

}
