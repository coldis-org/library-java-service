package org.coldis.library.test.service.jms;

import java.util.List;

import org.coldis.library.helper.RandomHelper;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.jms.DtoJmsMessageConverter;
import org.coldis.library.service.jms.EnhancedJmsMessageConverter;
import org.coldis.library.service.jms.TypableJmsMessageConverter;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JMS message converter test.
 */
@EnableJms
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class EnhancedMessageConverterTest {

	/**
	 * Test data.
	 */
	private static final List<DtoTestObject> TEST_DATA = List.of(new DtoTestObject(1L, "2", "3", 4, new int[] { 5, 6 }, 7),
			new DtoTestObject(2L, "3", "5", 5, new int[] { 6, 7 }, 8), new DtoTestObject(3L, "4", "5", 6, new int[] { 7, 8 }, 9));

	/**
	 * Async hops.
	 */
	public static Long asyncHops = 0L;

	/**
	 * Async hops message id.
	 */
	private Long asyncHopsMessageId;

	/**
	 * Current test message.
	 */
	private static Object currentTestMessage;

	/**
	 * Maximum async hops.
	 */
	@Value("${org.coldis.configuration.jms-message-converter-enhanced.maximum-async-hops:13}")
	private Long maximumAsyncHops;

	/**
	 * If the original type should precede the DTO type when trying to convert
	 * message.
	 */
	@Value("${org.coldis.configuration.jms-message-converter-enhanced.original-type-precedence:true}")
	private Boolean originalTypePrecedence;

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
	 * Typed JMS message converter.
	 */
	@Autowired
	private DtoJmsMessageConverter dtoJmsMessageConverter;

	/**
	 * Typed JMS message converter.
	 */
	@Autowired
	private TypableJmsMessageConverter typableJmsMessageConverter;

	/**
	 * Enhanced JMS message converter.
	 */
	@Autowired
	private EnhancedJmsMessageConverter enhancedJmsMessageConverter;

	/**
	 * Sets up tests.
	 */
	@BeforeEach
	public void setUp() {
		EnhancedMessageConverterTest.asyncHops = 0L;
		this.jmsTemplate.setMessageConverter(this.enhancedJmsMessageConverter);
		this.enhancedJmsMessageConverter.setMaximumAsyncHops(this.maximumAsyncHops);
		this.enhancedJmsMessageConverter.setOriginalTypePrecedence(this.originalTypePrecedence);
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param  message              Message.
	 * @throws InterruptedException If the test fails.
	 */
	@JmsListener(
			destination = "message/original",
			concurrency = "100"
	)
	public void consumeOriginalMessage(
			final DtoTestObject message) throws InterruptedException {
		EnhancedMessageConverterTest.currentTestMessage = message;
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param  message              Message.
	 * @throws InterruptedException If the test fails.
	 */
	@JmsListener(
			destination = "message/dto",
			concurrency = "100"
	)
	public void consumeDtoMessage(
			final DtoTestObjectDto message) throws InterruptedException {
		EnhancedMessageConverterTest.currentTestMessage = message;
	}

	/**
	 * Consumes a JMS test message.
	 *
	 * @param  message              Message.
	 * @throws InterruptedException If the test fails.
	 */
	@JmsListener(
			destination = "message/loop",
			concurrency = "100"
	)
	public void consumeMessageLoop(
			final DtoTestObject message) throws InterruptedException {
		if (this.asyncHopsMessageId.equals(message.getId())) {
			EnhancedMessageConverterTest.asyncHops++;
		}
		this.jmsTemplate.convertAndSend("message/loop", message);
	}

	/**
	 * Tests async loops.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testAsyncLoops() throws Exception {
		this.asyncHopsMessageId = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
		DtoTestObject testMessage = new DtoTestObject(this.asyncHopsMessageId, "2", "3", 4, new int[] { 5, 6 }, 7);
		this.jmsTemplate.convertAndSend("message/loop", testMessage);
		TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.asyncHops, asyncHops -> asyncHops > this.maximumAsyncHops, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT);
		Assertions.assertEquals(13, EnhancedMessageConverterTest.asyncHops);

		EnhancedMessageConverterTest.asyncHops = 0L;
		this.asyncHopsMessageId = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
		testMessage = new DtoTestObject(this.asyncHopsMessageId, "2", "3", 4, new int[] { 5, 6 }, 7);
		this.jmsTemplate.convertAndSend("message/loop", testMessage);
		TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.asyncHops, asyncHops -> asyncHops > this.maximumAsyncHops, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT);
		Assertions.assertEquals(13, EnhancedMessageConverterTest.asyncHops);

	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSendObjectReceiveObject() throws Exception {
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/original", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.currentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}

	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSendDtoReceiveObject() throws Exception {
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			this.jmsTemplate.convertAndSend("message/original", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.currentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testOldDtoObjectReceiveObject() throws Exception {
		this.jmsTemplate.setMessageConverter(this.dtoJmsMessageConverter);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/original", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.currentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testOldTypableObjectReceiveObject() throws Exception {
		this.jmsTemplate.setMessageConverter(this.typableJmsMessageConverter);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/original", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.currentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSendObjectReceiveDto() throws Exception {
		this.enhancedJmsMessageConverter.setOriginalTypePrecedence(false);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/dto", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.currentTestMessage,
					message -> (message != null) && message.equals(ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true)),
					TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}

	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSendDtoReceiveDto() throws Exception {
		this.enhancedJmsMessageConverter.setOriginalTypePrecedence(false);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			this.jmsTemplate.convertAndSend("message/dto", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.currentTestMessage,
					message -> (message != null) && message.equals(ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true)),
					TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

}
