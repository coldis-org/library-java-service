package org.coldis.library.test.service.jms;

import java.util.List;

import org.coldis.library.exception.BusinessException;
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
 * Typed JMS message converter test.
 */
@EnableJms
@SpringBootTest(
		webEnvironment = WebEnvironment.DEFINED_PORT,
		properties = { "org.coldis.configuration.jms-message-converter-default-enabled=false",
				"org.coldis.configuration.jms-message-converter-typed-enabled=true" }
)
public class TypedJmsMessageConverterTest {

	/**
	 * Test data.
	 */
	private static final List<DtoTestObjectDto> TEST_DATA = List.of(new DtoTestObjectDto().withId(1L).withTest7(4).withTest88(new int[] { 1, 2 }).withTest9(46),
			new DtoTestObjectDto().withId(2L).withTest7(5).withTest88(new int[] { 3, 4 }).withTest9(423),
			new DtoTestObjectDto().withId(3L).withTest7(6).withTest88(new int[] { 5, 6 }).withTest9(2342));

	/**
	 * Current test message.
	 */
	private static DtoTestObject currentTestMessage;

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
	private DtoTestObject getCurrentTestMessage() {
		return TypedJmsMessageConverterTest.currentTestMessage;
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param  message           Message.
	 * @throws BusinessException
	 */
	@JmsListener(
			destination = "jmsTest",
			containerFactory = "testJmsContainerFactory"
	)
	public void consumeMessage(
			final DtoTestObject message) throws BusinessException {
		TypedJmsMessageConverterTest.currentTestMessage = message;
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testMessageConverter() throws Exception {
		// For each test data.
		for (final DtoTestObjectDto testData : TypedJmsMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("jmsTest", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(this::getCurrentTestMessage,
					message -> testData.equals(ObjectMapperHelper.convert(this.objectMapper, message, DtoTestObjectDto.class, true)), TestHelper.LONG_WAIT,
					TestHelper.SHORT_WAIT));
		}

	}

}
