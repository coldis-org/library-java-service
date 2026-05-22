package org.coldis.library.test.service.jms;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.DynamicCreditClientInterceptor;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import jakarta.jms.ConnectionFactory;

/**
 * Integration tests for {@link DynamicCreditClientInterceptor}.
 *
 * <p>Registers the interceptor on the shared Artemis connection factory, enqueues
 * messages to produce shallow and deep queue scenarios, and asserts:
 * <ul>
 *   <li>All messages are delivered (no deadlock or stall with windowSize=0).</li>
 *   <li>Both concurrent consumers receive messages (fair distribution).</li>
 * </ul>
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@DisplayName("DynamicCreditClientInterceptor — integration")
class DynamicCreditClientInterceptorTest extends ContainerTestHelper {

	private static final String QUEUE = "dynamic-credit/test";
	private static final int TOTAL_MESSAGES = 100;

	/** Tracks how many messages each thread name received. */
	static final ConcurrentHashMap<String, Integer> receivedPerThread = new ConcurrentHashMap<>();

	/** Total messages received across all consumers. */
	static final AtomicInteger totalReceived = new AtomicInteger(0);

	@Autowired
	private ConnectionFactory connectionFactory;

	@Autowired
	private JmsTemplate jmsTemplate;

	private DynamicCreditClientInterceptor interceptor;

	@BeforeEach
	void setUp() {
		DynamicCreditClientInterceptorTest.receivedPerThread.clear();
		DynamicCreditClientInterceptorTest.totalReceived.set(0);

		// Depth threshold=10: at 100 messages the queue is 10× above threshold so the
		// interceptor scales credits up. windowSize is left at the factory default —
		// setConsumerWindowSize cannot be called after the factory is already in use.
		// The interceptor is registered on the ServerLocator which is always mutable.
		final ActiveMQConnectionFactory nativeFactory =
				(ActiveMQConnectionFactory) ConnectionFactoryUnwrapper.unwrap(this.connectionFactory);
		this.interceptor = new DynamicCreditClientInterceptor(nativeFactory, 10L, 2.0, 10 * 1024 * 1024, 1000L);
		nativeFactory.getServerLocator().addOutgoingInterceptor(this.interceptor);
	}

	@AfterEach
	void tearDown() {
		final ActiveMQConnectionFactory nativeFactory =
				(ActiveMQConnectionFactory) ConnectionFactoryUnwrapper.unwrap(this.connectionFactory);
		nativeFactory.getServerLocator().removeOutgoingInterceptor(this.interceptor);
	}

	/**
	 * Two concurrent consumers on the same queue.
	 * Each records its thread name so we can verify both threads received messages.
	 */
	@JmsListener(destination = QUEUE, concurrency = "2")
	void consume(final Long id) {
		DynamicCreditClientInterceptorTest.receivedPerThread.merge(
				Thread.currentThread().getName(), 1, Integer::sum);
		DynamicCreditClientInterceptorTest.totalReceived.incrementAndGet();
	}

	@Test
	@DisplayName("deep queue — all messages delivered and distributed across both consumers")
	void testDeepQueueAllMessagesDeliveredAndDistributed() throws Exception {
		// Enqueue TOTAL_MESSAGES quickly so the queue is deep when consumers start
		// draining — this exercises the above-threshold credit scaling path.
		for (long i = 0; i < TOTAL_MESSAGES; i++) {
			this.jmsTemplate.convertAndSend(QUEUE, i);
		}

		Assertions.assertTrue(
				TestHelper.waitUntilValid(
						() -> DynamicCreditClientInterceptorTest.totalReceived.get(),
						count -> count >= TOTAL_MESSAGES,
						TestHelper.LONG_WAIT,
						TestHelper.SHORT_WAIT),
				"Not all messages were consumed — possible deadlock with windowSize=0.");

		Assertions.assertEquals(TOTAL_MESSAGES, DynamicCreditClientInterceptorTest.totalReceived.get());

		Assertions.assertTrue(
				DynamicCreditClientInterceptorTest.receivedPerThread.size() >= 2,
				"Expected at least 2 consumer threads to receive messages, got: "
						+ DynamicCreditClientInterceptorTest.receivedPerThread);
	}

	@Test
	@DisplayName("shallow queue — messages below threshold delivered without stall")
	void testShallowQueueMessagesDeliveredBelowThreshold() throws Exception {
		// 5 messages — well below threshold of 10, credits pass through unchanged.
		for (long i = 0; i < 5; i++) {
			this.jmsTemplate.convertAndSend(QUEUE, i);
		}

		Assertions.assertTrue(
				TestHelper.waitUntilValid(
						() -> DynamicCreditClientInterceptorTest.totalReceived.get(),
						count -> count >= 5,
						TestHelper.LONG_WAIT,
						TestHelper.SHORT_WAIT),
				"Messages below depth threshold were not delivered.");

		Assertions.assertEquals(5, DynamicCreditClientInterceptorTest.totalReceived.get());
	}
}
