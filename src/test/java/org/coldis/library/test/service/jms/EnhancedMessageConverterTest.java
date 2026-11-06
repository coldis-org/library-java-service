package org.coldis.library.test.service.jms;

import java.util.List;
import java.util.Objects;

import org.coldis.library.helper.RandomHelper;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.helper.MultiLayerSessionHelper;
import org.coldis.library.service.jms.EnhancedJmsMessageConverter;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConversionException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.Message;

/**
 * JMS message converter test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Enhanced JMS message converter")
public class EnhancedMessageConverterTest extends ContainerTestHelper {

	/**
	 * Test data.
	 */
	private static final List<DtoTestObject> TEST_DATA = List.of(new DtoTestObject(1L, "2", "3", 4, new int[] { 5, 6 }, 7),
			new DtoTestObject(2L, "3", "5", 5, new int[] { 6, 7 }, 8), new DtoTestObject(3L, "4", "5", 6, new int[] { 7, 8 }, 9));

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
	
	/** JMS topic template. */
	@Autowired
	@Qualifier("jmsTopicTemplate")
	private JmsTemplate jmsTopicTemplate;

	/** Test service. */
	@Autowired
	private EnhancedMessageConverterTestServiceClient testServiceClient;

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

	/** Dto-prioritized optimized serializer. */
	@Autowired
	@Qualifier("javaDtoOptimizedSerializer")
	private org.apache.fory.BaseFory dtoOptimizedSerializer;

	/** Model-prioritized optimized serializer. */
	@Autowired
	@Qualifier("javaModelOptimizedSerializer")
	private org.apache.fory.BaseFory modelOptimizedSerializer;

	/** Original message converter captured before tests modify it. */
	private org.springframework.jms.support.converter.MessageConverter originalMessageConverter;

	/** Original maximumAsyncHops captured before tests modify it. */
	private Long originalMaximumAsyncHops;

	/** Original originalTypePrecedence captured before tests modify it. */
	private Boolean originalTypePrecedence;

	/**
	 * Sets up tests.
	 */
	@BeforeEach
	public void beforeEach() {
		this.originalMessageConverter = this.jmsTemplate.getMessageConverter();
		this.originalMaximumAsyncHops = this.jmsConverterProperties.getMaximumAsyncHops();
		this.originalTypePrecedence = this.jmsConverterProperties.getOriginalTypePrecedence();
		EnhancedMessageConverterTestListener.resetCurrentTestMessage();
		this.jmsTemplate.setMessageConverter(this.enhancedJmsMessageConverter);
		this.enhancedJmsMessageConverter.clearPreferredClassesCache();
		this.jmsConverterProperties.setMaximumAsyncHops(103L);
		this.jmsConverterProperties.setOriginalTypePrecedence(true);
	}

	/** Resets shared singletons after each test. */
	@AfterEach
	public void afterEach() {
		this.jmsTemplate.setMessageConverter(this.originalMessageConverter);
		this.jmsConverterProperties.setMaximumAsyncHops(this.originalMaximumAsyncHops);
		this.jmsConverterProperties.setOriginalTypePrecedence(this.originalTypePrecedence);
	}

	/**
	 * Tests async loops.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A message looping between listeners stops at exactly the maximum async hops")
	public void testAsyncLoops() throws Exception {
		final Long maximumAsyncHops = this.jmsConverterProperties.getMaximumAsyncHops();
		for (int loopIteration = 0; loopIteration < 5; loopIteration++) {
			final Long loopMessageIdentifier = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			EnhancedMessageConverterTestListener.resetAsyncHops(loopMessageIdentifier);
			this.jmsTopicTemplate.convertAndSend(EnhancedMessageConverterTestListener.LOOP_TOPIC,
					new DtoTestObject(loopMessageIdentifier, "2", "3", 4, new int[] { 5, 6 }, 7));
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getAsyncHops, asyncHops -> asyncHops >= maximumAsyncHops,
					TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
			Assertions.assertFalse(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getAsyncHops, asyncHops -> asyncHops > maximumAsyncHops,
					TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
			Assertions.assertEquals(maximumAsyncHops, EnhancedMessageConverterTestListener.getAsyncHops());
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Configured session attributes travel as message headers and reach the consumer thread")
	public void testSendSessionAttributes() throws Exception {

		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Generates random attributes.
			final Long attributeValue1 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attributeValue2 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attributeValue3 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attributeValue4 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attributeValue5 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attributeValue6 = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
			final Long attributeValueN = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);

			this.testServiceClient.sendMessage("message/thread", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true), attributeValue1,
					attributeValue2, attributeValue3, attributeValue4, attributeValue5, attributeValue6, attributeValueN);
			ThreadMapContextHolder.clear();
			final Message receivedMessage = this.jmsTemplate.receive("message/thread");
			final Object receivedMessageObject = this.jmsTemplate.getMessageConverter().fromMessage(receivedMessage);

			// Validates properties.
			Assertions.assertEquals(Objects.toString(attributeValue1),
					Objects.toString(receivedMessage.getObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX + "testJmsAttr1")));
			Assertions.assertEquals(Objects.toString(attributeValue2),
					Objects.toString(receivedMessage.getObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX + "testJmsAttr2")));
			Assertions.assertEquals(Objects.toString(attributeValue3),
					Objects.toString(receivedMessage.getObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX + "testJmsAttr3")));
			Assertions.assertEquals(Objects.toString(attributeValue4),
					Objects.toString(receivedMessage.getObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX + "testJmsAttr4")));
			// Assertions.assertEquals(Objects.toString(attributeValue5),Objects.toString(
			// receivedMessage.getObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX
			// + "testJmsAttr5")));
			Assertions.assertEquals(Objects.toString(attributeValue6),
					Objects.toString(receivedMessage.getObjectProperty(EnhancedJmsMessageConverter.SESSION_ATTRIBUTE_PREFIX + "testJmsAttr6")));
			Assertions.assertNull(receivedMessage.getObjectProperty("testJmsAttrN"));
			Assertions.assertNull(receivedMessage.getObjectProperty("sessionAttrN"));

			// Validates thread attributes.
			Assertions.assertEquals(Objects.toString(attributeValue1), Objects.toString(ThreadMapContextHolder.getAttribute("testJmsAttr1")));
			Assertions.assertEquals(Objects.toString(attributeValue2), Objects.toString(ThreadMapContextHolder.getAttribute("testJmsAttr2")));
			Assertions.assertEquals(Objects.toString(attributeValue3), Objects.toString(ThreadMapContextHolder.getAttribute("testJmsAttr3")));
			Assertions.assertEquals(Objects.toString(attributeValue4), Objects.toString(ThreadMapContextHolder.getAttribute("testJmsAttr4")));
			// Assertions.assertEquals(Objects.toString(attributeValue5),
			// Objects.toString(ThreadMapContextHolder.getAttribute("testJmsAttr5")));
			Assertions.assertEquals(Objects.toString(attributeValue6), Objects.toString(ThreadMapContextHolder.getAttribute("testJmsAttr6")));
			Assertions.assertEquals("message/thread",
					Objects.toString(ThreadMapContextHolder.getAttribute(EnhancedJmsMessageConverter.ORIGIN_DESTINATION_ATTRIBUTE)));
			Assertions.assertNull(ThreadMapContextHolder.getAttribute("testJmsAttrN"));
			Assertions.assertNull(ThreadMapContextHolder.getAttribute("sessionAttrN"));

			// Validates multi-layer attributes.
			Assertions.assertEquals(Objects.toString(attributeValue1), Objects.toString(MultiLayerSessionHelper.getAttribute("testJmsAttr1")));
			Assertions.assertEquals(Objects.toString(attributeValue2), Objects.toString(MultiLayerSessionHelper.getAttribute("testJmsAttr2")));
			Assertions.assertEquals(Objects.toString(attributeValue3), Objects.toString(MultiLayerSessionHelper.getAttribute("testJmsAttr3")));
			Assertions.assertEquals(Objects.toString(attributeValue4), Objects.toString(MultiLayerSessionHelper.getAttribute("testJmsAttr4")));
			// Assertions.assertEquals(Objects.toString(attributeValue5),Objects.toString(
			// this.multiLayerSessionHelper.getAttribute("testJmsAttr5")));
			Assertions.assertEquals(Objects.toString(attributeValue6), Objects.toString(MultiLayerSessionHelper.getAttribute("testJmsAttr6")));
			Assertions.assertEquals("message/thread",
					Objects.toString(MultiLayerSessionHelper.getAttribute(EnhancedJmsMessageConverter.ORIGIN_DESTINATION_ATTRIBUTE)));
			Assertions.assertNull(MultiLayerSessionHelper.getAttribute("testJmsAttrN"));
			Assertions.assertNull(MultiLayerSessionHelper.getAttribute("sessionAttrN"));

		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A model sent as JSON is received as the model")
	public void testSendObjectReceiveObject() throws Exception {
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/original", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}

	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A model sent optimized-serialized is received as the model")
	public void testSendOptimizedObjectReceiveObject() throws Exception {
		this.jmsTemplate.setMessageConverter(this.internalEnhancedJmsMessageConverter);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/original", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}

	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A DTO sent as JSON is received as the model")
	public void testSendDtoReceiveObject() throws Exception {
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			this.jmsTemplate.convertAndSend("message/original", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A model sent as JSON is received as the DTO when the DTO takes precedence")
	public void testSendObjectReceiveDto() throws Exception {
		this.jmsConverterProperties.setOriginalTypePrecedence(false);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			// Sends the test data as a JMS message.
			this.jmsTemplate.convertAndSend("message/dto", testData);
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
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
	@DisplayName("A DTO sent as JSON is received as the DTO when the DTO takes precedence")
	public void testSendDtoReceiveDto() throws Exception {
		this.jmsConverterProperties.setOriginalTypePrecedence(false);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			this.jmsTemplate.convertAndSend("message/dto", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true)),
					TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Sends alternating JSON and Fory messages of the same type to a single
	 * queue and confirms the listener decodes both. Validates the converter's
	 * routing in {@link EnhancedJmsMessageConverter#fromDynamicMessage} is the
	 * basis for migration scenarios where in-flight JSON-encoded messages
	 * coexist with new Fory-encoded ones on the same destination.
	 */
	@Test
	@DisplayName("JSON and optimized messages on the same queue are both decoded")
	public void testReceiveMixedEncodingsOnSameQueue() throws Exception {
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			EnhancedMessageConverterTestListener.resetCurrentTestMessage();
			this.jmsTemplate.setMessageConverter(this.enhancedJmsMessageConverter);
			this.jmsTemplate.convertAndSend("message/original", testData);
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

			EnhancedMessageConverterTestListener.resetCurrentTestMessage();
			this.jmsTemplate.setMessageConverter(this.internalEnhancedJmsMessageConverter);
			this.jmsTemplate.convertAndSend("message/original", testData);
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(testData), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Tests the JSON JMS message converter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A DTO sent optimized-serialized is received as the DTO")
	public void testSendOptimizedDtoReceiveDto() throws Exception {
		this.jmsConverterProperties.setOriginalTypePrecedence(false);
		this.jmsTemplate.setMessageConverter(this.internalEnhancedJmsMessageConverter);
		// For each test data.
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			this.jmsTemplate.convertAndSend("message/dto", ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true));
			// Asserts that the message is correctly converted.
			Assertions.assertTrue(TestHelper.waitUntilValid(EnhancedMessageConverterTestListener::getCurrentTestMessage,
					message -> (message != null) && message.equals(ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true)),
					TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Migration-coexistence scenario: a JSON-only producer (legacy / non-Fory
	 * caller, no optimized serializer wired) publishes to a queue read by a
	 * cross-Fory consumer (Dto outbound, Model inbound, optimized enabled).
	 * The Fory-aware consumer must still decode the JSON-wire message — Fory
	 * being configured on the receive side cannot break legitimate JSON
	 * producers in the ecosystem.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An optimized-serializer consumer decodes JSON from a JSON-only producer")
	public void testCrossForyConsumerHandlesJsonProducer() throws Exception {
		final EnhancedJmsMessageConverter jsonOnlyProducer = new EnhancedJmsMessageConverter(this.jmsConverterProperties, this.objectMapper, null, null, false,
				java.util.Set.of());
		final EnhancedJmsMessageConverter crossForyConsumer = new EnhancedJmsMessageConverter(this.jmsConverterProperties, this.objectMapper,
				this.dtoOptimizedSerializer, this.modelOptimizedSerializer, true, java.util.Set.of());

		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			final DtoTestObjectDto dto = ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true);
			this.jmsTemplate.setMessageConverter(jsonOnlyProducer);
			this.jmsTemplate.convertAndSend("message/json-to-cross-fory", dto);

			this.jmsTemplate.setMessageConverter(crossForyConsumer);
			final Object received = this.jmsTemplate.receiveAndConvert("message/json-to-cross-fory");

			Assertions.assertNotNull(received, "Cross-Fory consumer should decode JSON-wire messages.");
			Assertions.assertInstanceOf(DtoTestObject.class, received,
					"JSON producer + cross-Fory consumer should decode to Model under originalTypePrecedence=true; got " + received.getClass().getName());
			Assertions.assertEquals(testData.getId(), ((DtoTestObject) received).getId());
		}
	}

	/**
	 * Cross-class round-trip at the serializer layer (no listener): a Dto
	 * is written by a producer wired with the Dto-prioritized Fory and
	 * read back by a consumer wired with the Model-prioritized one. The
	 * payload should come out as a Model. Isolates the cross-class story
	 * from listener-binding concerns.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A DTO written with the DTO serializer is read back as the model")
	public void testCrossClassRoundTripWithSwappedSerializers() throws Exception {
		final EnhancedJmsMessageConverter crossClassConverter = new EnhancedJmsMessageConverter(this.jmsConverterProperties, this.objectMapper,
				this.dtoOptimizedSerializer, this.modelOptimizedSerializer, true, java.util.Set.of());
		this.jmsTemplate.setMessageConverter(crossClassConverter);
		for (final DtoTestObject testData : EnhancedMessageConverterTest.TEST_DATA) {
			final DtoTestObjectDto dto = ObjectMapperHelper.convert(this.objectMapper, testData, DtoTestObjectDto.class, true);
			this.jmsTemplate.convertAndSend("message/cross-class-roundtrip", dto);
			final Object received = this.jmsTemplate.receiveAndConvert("message/cross-class-roundtrip");

			Assertions.assertNotNull(received);
			Assertions.assertInstanceOf(DtoTestObject.class, received,
					"Expected Model after dto-prio sender + model-prio receiver, got " + received.getClass().getName());
			Assertions.assertEquals(testData.getId(), ((DtoTestObject) received).getId());
		}
	}

	/**
	 * Tests that a preferred type that is not a DTO type or origin is not used to
	 * convert the message (the body is left to the simple converter).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A preferred type header naming a non-DTO class is ignored")
	public void testNonDtoPreferredTypeIsIgnored() throws Exception {
		this.jmsTemplate.convertAndSend("message/preferred-type-non-dto", "[]", message -> {
			message.setStringProperty("preferedType", java.util.ArrayList.class.getName());
			return message;
		});
		final Message receivedMessage = this.jmsTemplate.receive("message/preferred-type-non-dto");
		Assertions.assertEquals("[]", this.enhancedJmsMessageConverter.fromMessage(receivedMessage));
	}

	/**
	 * Tests that a body that does not convert to its preferred type fails the
	 * conversion instead of falling back.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A body that does not convert to its preferred DTO type fails the conversion")
	public void testInvalidJsonForPreferredTypeFails() throws Exception {
		this.jmsTemplate.convertAndSend("message/preferred-type-invalid-json", "not json", message -> {
			message.setStringProperty("preferedType", DtoTestObject.class.getName());
			return message;
		});
		final Message receivedMessage = this.jmsTemplate.receive("message/preferred-type-invalid-json");
		Assertions.assertThrows(MessageConversionException.class, () -> this.enhancedJmsMessageConverter.fromMessage(receivedMessage));
	}

}
