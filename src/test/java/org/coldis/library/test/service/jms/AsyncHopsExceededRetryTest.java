package org.coldis.library.test.service.jms;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.jms.management.JMSManagementHelper;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.helper.RandomHelper;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.QueueBrowser;
import jakarta.jms.QueueConnection;
import jakarta.jms.QueueRequestor;
import jakarta.jms.QueueSession;
import jakarta.jms.Session;

/**
 * Broker retry (the Artemis console "Retry", i.e. the queue control retry
 * operation) of messages sent to the async hops exceeded queue.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class AsyncHopsExceededRetryTest extends ContainerTestHelper {

	/** Original queue. */
	private static final String ORIGINAL_QUEUE = "async-hops/retry/queue";

	/** Original topic. */
	private static final String ORIGINAL_TOPIC = "async-hops/retry/topic";

	/** Async hops exceeded queue. */
	private static final String EXCEEDED_QUEUE = "async-hops/exceeded";

	/** Test message identifier property. */
	private static final String TEST_IDENTIFIER_PROPERTY = "retryTestIdentifier";

	/** Identifier of the message the listeners should count. */
	private static final AtomicLong EXPECTED_IDENTIFIER = new AtomicLong();

	/** How many times each listener processed the expected message. */
	private static final Map<String, AtomicLong> PROCESSED_COUNTS = new ConcurrentHashMap<>();

	/** Connection factory. */
	@Autowired
	private ConnectionFactory connectionFactory;

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** JMS topic template. */
	@Autowired
	@Qualifier("jmsTopicTemplate")
	private JmsTemplate jmsTopicTemplate;

	/** JMS converter properties. */
	@Autowired
	private JmsConverterProperties jmsConverterProperties;

	/**
	 * Counts a processed message.
	 *
	 * @param listenerName Listener name.
	 * @param message      Message.
	 */
	private static void countProcessed(
			final String listenerName,
			final DtoTestObject message) {
		if (AsyncHopsExceededRetryTest.EXPECTED_IDENTIFIER.get() == message.getId()) {
			AsyncHopsExceededRetryTest.PROCESSED_COUNTS.computeIfAbsent(listenerName, name -> new AtomicLong()).incrementAndGet();
		}
	}

	/**
	 * Gets how many times a listener processed the expected message.
	 *
	 * @param  listenerName Listener name.
	 * @return              Processed count.
	 */
	private static Long getProcessedCount(
			final String listenerName) {
		return AsyncHopsExceededRetryTest.PROCESSED_COUNTS.getOrDefault(listenerName, new AtomicLong()).get();
	}

	/**
	 * Consumes the queue test message.
	 *
	 * @param message Message.
	 */
	@JmsListener(destination = AsyncHopsExceededRetryTest.ORIGINAL_QUEUE)
	public void consumeQueue(
			final DtoTestObject message) {
		AsyncHopsExceededRetryTest.countProcessed("queue", message);
	}

	/**
	 * Consumes the topic test message (first subscription).
	 *
	 * @param message Message.
	 */
	@JmsListener(
			containerFactory = "jmsListenerTopicContainerFactory",
			destination = AsyncHopsExceededRetryTest.ORIGINAL_TOPIC,
			subscription = "async-hops/retry/topic/first"
	)
	public void consumeTopicFirstSubscription(
			final DtoTestObject message) {
		AsyncHopsExceededRetryTest.countProcessed("first", message);
	}

	/**
	 * Consumes the topic test message (second subscription).
	 *
	 * @param message Message.
	 */
	@JmsListener(
			containerFactory = "jmsListenerTopicContainerFactory",
			destination = AsyncHopsExceededRetryTest.ORIGINAL_TOPIC,
			subscription = "async-hops/retry/topic/second"
	)
	public void consumeTopicSecondSubscription(
			final DtoTestObject message) {
		AsyncHopsExceededRetryTest.countProcessed("second", message);
	}

	/**
	 * Resets the counters.
	 */
	@BeforeEach
	public void setUp() {
		AsyncHopsExceededRetryTest.PROCESSED_COUNTS.clear();
	}

	/**
	 * Gets the native connection factory.
	 *
	 * @return Native connection factory.
	 */
	private ActiveMQConnectionFactory getNativeConnectionFactory() {
		return (ActiveMQConnectionFactory) ConnectionFactoryUnwrapper.unwrap(this.connectionFactory);
	}

	/**
	 * Counts the test messages in the exceeded queue.
	 *
	 * @param  testIdentifier Test identifier.
	 * @return                Number of test messages in the exceeded queue.
	 */
	private Integer countInExceededQueue(
			final Long testIdentifier) {
		try (QueueConnection connection = this.getNativeConnectionFactory().createQueueConnection();
				QueueSession session = connection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
				QueueBrowser browser = session.createBrowser(session.createQueue(AsyncHopsExceededRetryTest.EXCEEDED_QUEUE),
						AsyncHopsExceededRetryTest.TEST_IDENTIFIER_PROPERTY + " = " + testIdentifier)) {
			connection.start();
			@SuppressWarnings("unchecked")
			final Enumeration<Message> messages = browser.getEnumeration();
			return Collections.list(messages).size();
		}
		catch (final Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * Invokes the broker retry operation on the exceeded queue (what the console
	 * "Retry" does) for the test messages.
	 *
	 * @param  testIdentifier Test identifier.
	 * @return                Number of retried messages.
	 */
	private Integer retryExceededQueue(
			final Long testIdentifier) throws Exception {
		try (QueueConnection connection = this.getNativeConnectionFactory().createQueueConnection();
				QueueSession session = connection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE)) {
			final QueueRequestor requestor = new QueueRequestor(session, session.createQueue("activemq.management"));
			connection.start();
			final Message request = session.createMessage();
			JMSManagementHelper.putOperationInvocation(request, ResourceNames.QUEUE + AsyncHopsExceededRetryTest.EXCEEDED_QUEUE, "retryMessages",
					AsyncHopsExceededRetryTest.TEST_IDENTIFIER_PROPERTY + " = " + testIdentifier);
			final Message reply = requestor.request(request);
			Assertions.assertTrue(JMSManagementHelper.hasOperationSucceeded(reply));
			return ((Number) JMSManagementHelper.getResult(reply)).intValue();
		}
	}

	/**
	 * Sends a message already over the hops limit, waits for it in the exceeded
	 * queue and retries it.
	 *
	 * @param template            Template to send with.
	 * @param destination         Destination.
	 * @param expectedCopiesCount Expected copies in the exceeded queue (one per
	 *                                consuming queue).
	 */
	private void sendExceededAndRetry(
			final JmsTemplate template,
			final String destination,
			final Integer expectedCopiesCount) throws Exception {
		final Long testIdentifier = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
		AsyncHopsExceededRetryTest.EXPECTED_IDENTIFIER.set(testIdentifier);
		final Long exceededHop = this.jmsConverterProperties.getMaximumAsyncHops() + 1;

		// Sends a message already over the hops limit, and waits for it in the
		// exceeded queue.
		template.convertAndSend(destination, new DtoTestObject(testIdentifier, "2", "3", 4, new int[] { 5, 6 }, 7), message -> {
			message.setLongProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, exceededHop);
			message.setLongProperty(AsyncHopsExceededRetryTest.TEST_IDENTIFIER_PROPERTY, testIdentifier);
			return message;
		});
		TestHelper.waitUntilValid(() -> this.countInExceededQueue(testIdentifier), count -> count >= expectedCopiesCount, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT);
		Assertions.assertEquals(expectedCopiesCount, this.countInExceededQueue(testIdentifier));
		Assertions.assertTrue(AsyncHopsExceededRetryTest.PROCESSED_COUNTS.isEmpty());

		// Retries it as the console does.
		Assertions.assertEquals(expectedCopiesCount, this.retryExceededQueue(testIdentifier));
		TestHelper.waitUntilValid(() -> this.countInExceededQueue(testIdentifier), count -> count == 0, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT);
		Assertions.assertEquals(0, this.countInExceededQueue(testIdentifier));
	}

	/**
	 * Tests retrying a queue message from the exceeded queue.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRetryQueueMessageFromExceededQueue() throws Exception {
		this.sendExceededAndRetry(this.jmsTemplate, AsyncHopsExceededRetryTest.ORIGINAL_QUEUE, 1);
		TestHelper.waitUntilValid(() -> AsyncHopsExceededRetryTest.getProcessedCount("queue"), count -> count > 0, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT);
		Assertions.assertEquals(1L, AsyncHopsExceededRetryTest.getProcessedCount("queue"));
	}

	/**
	 * Tests that a message with a null-valued hop property (as copied to the
	 * exceeded queue by previous versions) is processed.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNullAsyncHopMessageIsProcessed() throws Exception {
		final Long testIdentifier = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
		AsyncHopsExceededRetryTest.EXPECTED_IDENTIFIER.set(testIdentifier);
		this.jmsTemplate.convertAndSend(AsyncHopsExceededRetryTest.ORIGINAL_QUEUE, new DtoTestObject(testIdentifier, "2", "3", 4, new int[] { 5, 6 }, 7),
				message -> {
					message.setObjectProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, null);
					return message;
				});
		TestHelper.waitUntilValid(() -> AsyncHopsExceededRetryTest.getProcessedCount("queue"), count -> count > 0, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT);
		Assertions.assertEquals(1L, AsyncHopsExceededRetryTest.getProcessedCount("queue"));
	}

	/**
	 * Tests retrying topic messages from the exceeded queue: each copy goes back
	 * only to the subscription it came from.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testRetryTopicMessageFromExceededQueue() throws Exception {
		this.sendExceededAndRetry(this.jmsTopicTemplate, AsyncHopsExceededRetryTest.ORIGINAL_TOPIC, 2);
		TestHelper.waitUntilValid(() -> AsyncHopsExceededRetryTest.getProcessedCount("first") + AsyncHopsExceededRetryTest.getProcessedCount("second"),
				count -> count >= 2, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT);
		Assertions.assertEquals(1L, AsyncHopsExceededRetryTest.getProcessedCount("first"));
		Assertions.assertEquals(1L, AsyncHopsExceededRetryTest.getProcessedCount("second"));
	}

}
