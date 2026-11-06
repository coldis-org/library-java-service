package org.coldis.library.test.service.jms;

import java.util.Collections;
import java.util.Enumeration;
import java.util.function.Supplier;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
 * operation) of messages sent to the async hops exceeded queue. Listeners are
 * in {@link AsyncHopsExceededRetryTestListener}.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Async hops exceeded queue — broker retry")
public class AsyncHopsExceededRetryTest extends ContainerTestHelper {

	/** Async hops exceeded queue. */
	private static final String EXCEEDED_QUEUE = "async-hops/exceeded";

	/** Test message identifier property. */
	private static final String TEST_IDENTIFIER_PROPERTY = "retryTestIdentifier";

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
	 * Waits until a count reaches the expected value, then checks it does not go
	 * past it (late duplicates or fan-out).
	 *
	 * @param count         Count supplier.
	 * @param expectedCount Expected count.
	 */
	private void assertCountSettles(
			final Supplier<Long> count,
			final Long expectedCount) throws Exception {
		TestHelper.waitUntilValid(count, actualCount -> actualCount >= expectedCount, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT);
		Assertions.assertEquals(expectedCount, count.get());
		Assertions.assertFalse(TestHelper.waitUntilValid(count, actualCount -> actualCount > expectedCount, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
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
		AsyncHopsExceededRetryTestListener.reset(testIdentifier);
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
		Assertions.assertEquals(0L, AsyncHopsExceededRetryTestListener.getTotalProcessedCount());

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
	@DisplayName("Retrying an exceeded queue message returns it to its queue, processed once")
	public void testRetryQueueMessageFromExceededQueue() throws Exception {
		this.sendExceededAndRetry(this.jmsTemplate, AsyncHopsExceededRetryTestListener.ORIGINAL_QUEUE, 1);
		this.assertCountSettles(() -> AsyncHopsExceededRetryTestListener.getProcessedCount(AsyncHopsExceededRetryTestListener.QUEUE_LISTENER), 1L);
	}

	/**
	 * Tests that a message with a null-valued hop property (as copied to the
	 * exceeded queue by previous versions) is processed.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A message with a null async hop (copied by previous versions) is processed once")
	public void testNullAsyncHopMessageIsProcessed() throws Exception {
		final Long testIdentifier = RandomHelper.getPositiveRandomLong(Long.MAX_VALUE);
		AsyncHopsExceededRetryTestListener.reset(testIdentifier);
		this.jmsTemplate.convertAndSend(AsyncHopsExceededRetryTestListener.ORIGINAL_QUEUE,
				new DtoTestObject(testIdentifier, "2", "3", 4, new int[] { 5, 6 }, 7), message -> {
					message.setObjectProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, null);
					return message;
				});
		this.assertCountSettles(() -> AsyncHopsExceededRetryTestListener.getProcessedCount(AsyncHopsExceededRetryTestListener.QUEUE_LISTENER), 1L);
	}

	/**
	 * Tests retrying topic messages from the exceeded queue: each copy goes back
	 * only to the subscription it came from.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Retrying exceeded topic messages returns each to its own subscription only")
	public void testRetryTopicMessageFromExceededQueue() throws Exception {
		this.sendExceededAndRetry(this.jmsTopicTemplate, AsyncHopsExceededRetryTestListener.ORIGINAL_TOPIC, 2);
		this.assertCountSettles(AsyncHopsExceededRetryTestListener::getTotalProcessedCount, 2L);
		Assertions.assertEquals(1L, AsyncHopsExceededRetryTestListener.getProcessedCount(AsyncHopsExceededRetryTestListener.FIRST_SUBSCRIPTION_LISTENER));
		Assertions.assertEquals(1L, AsyncHopsExceededRetryTestListener.getProcessedCount(AsyncHopsExceededRetryTestListener.SECOND_SUBSCRIPTION_LISTENER));
	}

}
