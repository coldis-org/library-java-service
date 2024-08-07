package org.coldis.library.test.service.jms;

import java.util.List;
import java.util.Objects;

import org.coldis.library.helper.RandomHelper;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.jms.DtoJmsMessageConverter;
import org.coldis.library.service.jms.EnhancedJmsMessageConverter;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.service.jms.TypableJmsMessageConverter;
import org.coldis.library.test.ContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.Message;

/**
 * JMS message converter test.
 */
@EnableJms
@ExtendWith(ContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
public class EnhancedMessageConverterTest {

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

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
	 * JMS converter properties.
	 */
	@Autowired
	private JmsConverterProperties jmsConverterProperties;

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
	 * Enhanced JMS message converter.
	 */
	@Autowired
	@Qualifier("internalEnhancedJmsMessageConverter")
	private EnhancedJmsMessageConverter internalEnhancedJmsMessageConverter;

	/**
	 * Sets up tests.
	 */
	@BeforeEach
	public void setUp() {
		EnhancedMessageConverterTest.asyncHops = 0L;
		this.jmsTemplate.setMessageConverter(this.enhancedJmsMessageConverter);
		this.jmsConverterProperties.setMaximumAsyncHops(103L);
		this.jmsConverterProperties.setOriginalTypePrecedence(true);
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
		if (Objects.equals(this.asyncHopsMessageId, message.getId())) {
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
	// @Disabled
	public void testAsyncLoops() throws Exception {
		for (int i = 0; i < 17; i++) {
			EnhancedMessageConverterTest.asyncHops = 0L;
			this.asyncHopsMessageId = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final DtoTestObject testMessage = new DtoTestObject(this.asyncHopsMessageId, "2", "3", 4, new int[] { 5, 6 }, 7);
			this.jmsTemplate.convertAndSend("message/loop", testMessage);
			TestHelper.waitUntilValid(() -> EnhancedMessageConverterTest.asyncHops, asyncHops -> asyncHops > this.jmsConverterProperties.getMaximumAsyncHops(),
					TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT);
			Assertions.assertEquals(this.jmsConverterProperties.getMaximumAsyncHops(), EnhancedMessageConverterTest.asyncHops);
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	// @Disabled
	public void testSendThreadAttributes() throws Exception {
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Generates random attributes.
			final Long attr1 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attr2 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attr3 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			ThreadMapContextHolder.setAttribute("testJmsAttr1", attr1);
			ThreadMapContextHolder.setAttribute("testJmsAttr2", attr2);
			ThreadMapContextHolder.setAttribute("testJmsAttr3", attr3);

			this.jmsTemplate.convertAndSend("message/thread", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
			ThreadMapContextHolder.clear();
			final Message receivedMessage = this.jmsTemplate.receive("message/thread");

			Assertions.assertEquals(attr1, receivedMessage.getObjectProperty("testJmsAttr1"));
			Assertions.assertEquals(attr2, receivedMessage.getObjectProperty("testJmsAttr2"));
			Assertions.assertNull(receivedMessage.getObjectProperty("testJmsAttr3"));

		}
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
	public void testSendOptimizedObjectReceiveObject() throws Exception {
		this.jmsTemplate.setMessageConverter(this.internalEnhancedJmsMessageConverter);
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
		this.jmsConverterProperties.setOriginalTypePrecedence(false);
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
		this.jmsConverterProperties.setOriginalTypePrecedence(false);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			this.jmsTemplate.convertAndSend("message/dto", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
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
	public void testSendOptimizedDtoReceiveDto() throws Exception {
		this.jmsConverterProperties.setOriginalTypePrecedence(false);
		this.jmsTemplate.setMessageConverter(this.internalEnhancedJmsMessageConverter);
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
