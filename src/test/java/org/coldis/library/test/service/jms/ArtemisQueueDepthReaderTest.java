package org.coldis.library.test.service.jms;

import java.util.Optional;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.ArtemisQueueDepthReader;
import org.coldis.library.service.jms.QueueDepthComponent;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;

/**
 * {@link ArtemisQueueDepthReader} against the test broker: a queue's message count, an unknown
 * queue, the cache, and an unreadable depth; and {@link QueueDepthComponent}'s choice of connection
 * factory (the given one, else the unique or primary one).
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("ArtemisQueueDepthReader and QueueDepthComponent — integration")
class ArtemisQueueDepthReaderTest extends ContainerTestHelper {

	/** Messages sent. */
	private static final int MESSAGES = 7;

	/** Connection factory. */
	@Autowired
	private ConnectionFactory connectionFactory;

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * A reader with no cache on the test broker.
	 *
	 * @param  cacheTtlMillis Cache TTL.
	 * @return                The reader.
	 */
	private ArtemisQueueDepthReader reader(
			final long cacheTtlMillis) {
		return new ArtemisQueueDepthReader((ActiveMQConnectionFactory) ConnectionFactoryUnwrapper.unwrap(this.connectionFactory), cacheTtlMillis);
	}

	/**
	 * A queue holding N messages (no consumer) reads N; an unknown queue reads 0.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("depth is the queue's message count; an unknown queue has depth 0")
	void testDepth() throws Exception {
		final String queueName = "queue-depth/test-" + System.nanoTime();
		try (ArtemisQueueDepthReader reader = this.reader(0L)) {
			for (int message = 0; message < ArtemisQueueDepthReaderTest.MESSAGES; message++) {
				this.jmsTemplate.convertAndSend(queueName, "message-" + message);
			}
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> reader.findDepth(queueName),
					depth -> Optional.of((long) ArtemisQueueDepthReaderTest.MESSAGES).equals(depth), TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
			Assertions.assertEquals(Optional.of(0L), reader.findDepth("queue-depth/unknown-" + System.nanoTime()));
		}
	}

	/**
	 * A depth read within the TTL is reused: a message sent after it is not seen until the cache
	 * is dropped (close), while an uncached reader sees it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("a depth is cached for the TTL; close releases the session and the cache")
	void testCache() throws Exception {
		final String queueName = "queue-depth/cache-" + System.nanoTime();
		try (ArtemisQueueDepthReader cachedReader = this.reader(60_000L); ArtemisQueueDepthReader uncachedReader = this.reader(0L)) {
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> cachedReader.findDepth(queueName), depth -> Optional.of(0L).equals(depth), TestHelper.REGULAR_WAIT,
					TestHelper.SHORT_WAIT));
			this.jmsTemplate.convertAndSend(queueName, "message");
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> uncachedReader.findDepth(queueName), depth -> Optional.of(1L).equals(depth), TestHelper.REGULAR_WAIT,
					TestHelper.SHORT_WAIT));
			Assertions.assertEquals(Optional.of(0L), cachedReader.findDepth(queueName));
			Assertions.assertEquals(0L, cachedReader.getLastDepth(queueName));
			// Closed: session and cache released; the next read opens a new session.
			cachedReader.close();
			Assertions.assertEquals(Optional.of(1L), cachedReader.findDepth(queueName));
		}
	}

	/**
	 * No connection factory: the depth is empty, never an exception.
	 */
	@Test
	@DisplayName("an unreadable depth is empty, not an exception")
	void testUnreadable() {
		try (ArtemisQueueDepthReader reader = new ArtemisQueueDepthReader(null, 0L)) {
			Assertions.assertEquals(Optional.empty(), reader.findDepth("queue-depth/any"));
		}
	}

	/**
	 * A queue depth component on these connection factories.
	 *
	 * @param  unreachableIsPrimary   Whether the unreachable factory is registered too, as primary
	 *                                (null: not registered).
	 * @param  unreachableFactory     A factory on no broker.
	 * @param  registerTestFactoryTwice Whether the test broker's factory is registered twice (none
	 *                                primary), as services do with one per service client.
	 * @return                        The component.
	 */
	private QueueDepthComponent component(
			final Boolean unreachableIsPrimary,
			final ConnectionFactory unreachableFactory,
			final Boolean registerTestFactoryTwice) {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("testServiceJmsConnectionFactory", this.connectionFactory);
		if (registerTestFactoryTwice) {
			beanFactory.registerSingleton("otherServiceJmsConnectionFactory", this.connectionFactory);
		}
		if (unreachableIsPrimary != null) {
			final RootBeanDefinition definition = new RootBeanDefinition(ConnectionFactory.class, () -> unreachableFactory);
			definition.setPrimary(unreachableIsPrimary);
			beanFactory.registerBeanDefinition("unreachableJmsConnectionFactory", definition);
		}
		return new QueueDepthComponent(beanFactory.getBeanProvider(ConnectionFactory.class));
	}

	/**
	 * The component reads on the factory it is given (the test broker's: the message count; one on
	 * no broker: empty); with no factory given, on the application's unique or primary one, and
	 * empty, never an exception, with several and none primary (one per service client, as in
	 * services).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("the component reads on the given factory, else on the unique or primary one, and is empty with several and none primary")
	void testComponentConnectionFactories() throws Exception {
		final String queueName = "queue-depth/test-" + System.nanoTime();
		final Optional<Long> sent = Optional.of((long) ArtemisQueueDepthReaderTest.MESSAGES);
		for (int message = 0; message < ArtemisQueueDepthReaderTest.MESSAGES; message++) {
			this.jmsTemplate.convertAndSend(queueName, "message-" + message);
		}
		final ActiveMQConnectionFactory unreachableFactory = new ActiveMQConnectionFactory("tcp://127.0.0.1:1");
		final QueueDepthComponent unique = this.component(null, unreachableFactory, false);
		final QueueDepthComponent primaryUnreachable = this.component(true, unreachableFactory, false);
		final QueueDepthComponent severalNonePrimary = this.component(false, unreachableFactory, true);
		try {
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> unique.findDepth(queueName), sent::equals, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
			Assertions.assertEquals(Optional.empty(), primaryUnreachable.findDepth(queueName));
			Assertions.assertEquals(Optional.empty(), severalNonePrimary.findDepth(queueName));
			Assertions.assertEquals(Optional.empty(), severalNonePrimary.findDepth(unreachableFactory, queueName));
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> severalNonePrimary.findDepth(this.jmsTemplate.getConnectionFactory(), queueName), sent::equals,
					TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		}
		finally {
			unique.close();
			primaryUnreachable.close();
			severalNonePrimary.close();
			unreachableFactory.close();
		}
	}

}
