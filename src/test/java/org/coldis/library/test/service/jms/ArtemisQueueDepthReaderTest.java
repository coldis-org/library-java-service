package org.coldis.library.test.service.jms;

import java.util.Optional;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.ArtemisQueueDepthReader;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
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
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;

/**
 * {@link ArtemisQueueDepthReader} against the test broker: a queue's message count, an unknown
 * queue, the cache, and an unreadable depth.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("ArtemisQueueDepthReader — integration")
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
		final ArtemisQueueDepthReader reader = this.reader(0L);
		for (int message = 0; message < ArtemisQueueDepthReaderTest.MESSAGES; message++) {
			this.jmsTemplate.convertAndSend(queueName, "message-" + message);
		}
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> reader.findDepth(queueName), depth -> Optional.of((long) ArtemisQueueDepthReaderTest.MESSAGES).equals(depth),
				TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(Optional.of(0L), reader.findDepth("queue-depth/unknown-" + System.nanoTime()));
	}

	/**
	 * A depth read within the TTL is reused: messages sent after it are not seen until it expires.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("a depth is cached for the TTL")
	void testCache() throws Exception {
		final String queueName = "queue-depth/cache-" + System.nanoTime();
		final ArtemisQueueDepthReader reader = this.reader(60_000L);
		Assertions.assertEquals(Optional.of(0L), reader.findDepth(queueName));
		this.jmsTemplate.convertAndSend(queueName, "message");
		Assertions.assertEquals(Optional.of(0L), reader.findDepth(queueName));
		Assertions.assertEquals(0L, reader.getLastDepth(queueName));
		Assertions.assertEquals(Optional.of(1L), this.reader(0L).findDepth(queueName));
	}

	/**
	 * No connection factory: the depth is empty, never an exception.
	 */
	@Test
	@DisplayName("an unreadable depth is empty, not an exception")
	void testUnreadable() {
		Assertions.assertEquals(Optional.empty(), new ArtemisQueueDepthReader(null, 0L).findDepth("queue-depth/any"));
	}

}
