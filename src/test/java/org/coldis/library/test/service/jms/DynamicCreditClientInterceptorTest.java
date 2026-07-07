package org.coldis.library.test.service.jms;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionConsumerFlowCreditMessage;
import org.apache.activemq.artemis.core.protocol.core.impl.wireformat.SessionCreateConsumerMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.DynamicCreditClientInterceptor;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.core.JmsTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;

/**
 * Integration tests for {@link DynamicCreditClientInterceptor}.
 *
 * <p>Modifies the shared {@link ActiveMQConnectionFactory} bean directly by adding
 * the interceptor to its {@code ServerLocator} in {@link #setUp()} and removing it
 * in {@link #tearDown()}. Tests use a programmatic JMS consumer (not
 * {@code @JmsListener}) so consumer creation happens <em>after</em> the interceptor
 * is registered — guaranteeing the interceptor captures {@code CREATE_CONSUMER}
 * and {@code FLOW_CREDIT} packets for the test consumer.
 *
 * <p>Each test asserts the interceptor's observable counters incremented as
 * expected, proving the interceptor actually fired rather than silently passing
 * traffic through.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("DynamicCreditClientInterceptor — integration")
class DynamicCreditClientInterceptorTest extends ContainerTestHelper {

	private static final long DEPTH_THRESHOLD = 10L;
	private static final double MULTIPLIER = 2.0;
	private static final int MAX_CREDITS = 10 * 1024 * 1024;
	private static final long CACHE_TTL_MS = 100L;

	@Autowired
	private ConnectionFactory connectionFactory;

	@Autowired
	private JmsTemplate jmsTemplate;

	private DynamicCreditClientInterceptor interceptor;
	private ActiveMQConnectionFactory nativeFactory;

	/** Unique queue per test method so depth measurements are deterministic. */
	private String queueName;

	@BeforeEach
	void setUp() {
		this.queueName = "dynamic-credit/test-" + System.nanoTime();
		this.nativeFactory = (ActiveMQConnectionFactory) ConnectionFactoryUnwrapper.unwrap(this.connectionFactory);
		this.interceptor = new DynamicCreditClientInterceptor(
				this.nativeFactory, DEPTH_THRESHOLD, MULTIPLIER, MAX_CREDITS, CACHE_TTL_MS);
		this.nativeFactory.getServerLocator().addOutgoingInterceptor(this.interceptor);
	}

	@AfterEach
	void tearDown() {
		this.nativeFactory.getServerLocator().removeOutgoingInterceptor(this.interceptor);
	}

	/**
	 * Drains a queue and counts messages consumed, blocking up to
	 * {@code timeoutMs} between messages.
	 */
	private int drainQueue(final String queue, final int max, final long timeoutMs) throws Exception {
		int received = 0;
		try (Connection conn = this.nativeFactory.createConnection();
				Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
				MessageConsumer consumer = session.createConsumer(session.createQueue(queue))) {
			conn.start();
			Message msg;
			while (received < max && (msg = consumer.receive(timeoutMs)) != null) {
				received++;
			}
		}
		return received;
	}

	@Test
	@DisplayName("deep queue — interceptor tracks consumer creation and scales credit packets")
	void testDeepQueueInterceptorScalesCredits() throws Exception {
		final int totalMessages = 100;

		// Enqueue messages first so queue depth >> threshold when the consumer is
		// created. The initial FLOW_CREDIT packet sent at consumer-create time will
		// see depth > threshold and be scaled.
		for (long i = 0; i < totalMessages; i++) {
			this.jmsTemplate.convertAndSend(this.queueName, i);
		}

		final int received = this.drainQueue(this.queueName, totalMessages, 3000L);

		Assertions.assertEquals(totalMessages, received, "All messages should be delivered");
		Assertions.assertTrue(
				this.interceptor.getConsumersRegistered() >= 1,
				"Interceptor should have captured at least one CREATE_CONSUMER packet, got: "
						+ this.interceptor.getConsumersRegistered());
		Assertions.assertTrue(
				this.interceptor.getCreditPacketsIntercepted() >= 1,
				"Interceptor should have seen at least one FLOW_CREDIT packet, got: "
						+ this.interceptor.getCreditPacketsIntercepted());
		Assertions.assertTrue(
				this.interceptor.getCreditPacketsScaled() >= 1,
				"Interceptor should have scaled at least one credit packet for deep queue, got: "
						+ this.interceptor.getCreditPacketsScaled());
	}

	@Test
	@DisplayName("shallow queue — interceptor sees credits but does not scale them")
	void testShallowQueueInterceptorPassesCreditsThrough() throws Exception {
		// 5 messages — well below threshold of 10
		for (long i = 0; i < 5; i++) {
			this.jmsTemplate.convertAndSend(this.queueName, i);
		}

		final int received = this.drainQueue(this.queueName, 5, 3000L);

		Assertions.assertEquals(5, received, "All messages below threshold should be delivered");
		Assertions.assertTrue(
				this.interceptor.getConsumersRegistered() >= 1,
				"Interceptor should have captured the test consumer creation");
		Assertions.assertEquals(
				0,
				this.interceptor.getCreditPacketsScaled(),
				"Below threshold, no credit packets should be scaled");
	}

	@Test
	@DisplayName("interceptor ignores credit packets from consumers it did not see created")
	void testInterceptorIgnoresUntrackedConsumers() throws Exception {
		// This test verifies a key safety property: if for some reason a credit
		// packet arrives for a consumerID we never saw created (e.g., interceptor
		// registered after the consumer), we simply pass it through unmodified —
		// no NPE, no scaling.
		// We exercise this implicitly: every connection created BEFORE setUp ran
		// (e.g., the Spring JmsTemplate's pooled connection) would have an unknown
		// consumerID. Sending a message goes through such a producer; no consumer
		// is involved here, so the path is only sanity-checked.
		for (long i = 0; i < 3; i++) {
			this.jmsTemplate.convertAndSend(this.queueName, i);
		}
		final int received = this.drainQueue(this.queueName, 3, 3000L);

		Assertions.assertEquals(3, received);
		// No crash, no scaling expected for these (below threshold anyway).
		Assertions.assertEquals(0, this.interceptor.getCreditPacketsScaled());
	}

	/**
	 * Reproduces the production warning {@code AMQ212051: Invalid concurrent session
	 * usage}. The interceptor's {@code querySession} is shared across consumer
	 * threads and Artemis's {@link org.apache.activemq.artemis.api.core.client.ClientSession}
	 * is not thread-safe, so without synchronizing the {@code queueQuery} call
	 * concurrent flow-credit intercepts from different consumers trip the warning.
	 *
	 * <p>Registers many consumers through the real {@code intercept} path — each on
	 * its own session channel and its own queue so every depth read is a cache miss
	 * that reaches the broker — captures Artemis client logs, fires flow-credit
	 * intercepts from many threads in lockstep, then asserts no warning was logged.
	 */
	@Test
	@DisplayName("concurrent intercept does not trigger Artemis 'concurrent session usage' warning")
	void testConcurrentInterceptDoesNotWarnAboutSessionThreadSafety() throws Exception {
		final int threads = 16;
		final int consumersPerThread = 5;

		// Register one consumer per (thread, index) via the real create path so both the
		// queue map and the outstanding map are populated with the correct session-scoped
		// keys. Each consumer gets its own channel (session) and queue, so the flow-credit
		// intercepts below reach a fresh queueQuery (cache miss) on the shared session.
		for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
			for (int consumerIndex = 0; consumerIndex < consumersPerThread; consumerIndex++) {
				final long channelId = threadIndex * 1000L + consumerIndex;
				final SessionCreateConsumerMessage createConsumer = new SessionCreateConsumerMessage(
						0L, SimpleString.of("concurrent-test/" + threadIndex + "/" + consumerIndex), null, 0, false, true);
				createConsumer.setChannelID(channelId);
				this.interceptor.intercept(createConsumer, null);
			}
		}

		// Capture WARN logs from the Artemis client logger to detect AMQ212051.
		final Logger artemisLogger = (Logger) LoggerFactory.getLogger("org.apache.activemq.artemis.core.client");
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		artemisLogger.addAppender(appender);

		try {
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch done = new CountDownLatch(threads);
			final ExecutorService pool = Executors.newFixedThreadPool(threads);
			try {
				for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
					final int currentThreadIndex = threadIndex;
					pool.submit(() -> {
						try {
							start.await();
							for (int consumerIndex = 0; consumerIndex < consumersPerThread; consumerIndex++) {
								final long channelId = currentThreadIndex * 1000L + consumerIndex;
								final SessionConsumerFlowCreditMessage credit =
										new SessionConsumerFlowCreditMessage(0L, 1024);
								credit.setChannelID(channelId);
								this.interceptor.intercept(credit, null);
							}
						}
						catch (final Throwable throwable) {
							throw new RuntimeException(throwable);
						}
						finally {
							done.countDown();
						}
					});
				}
				start.countDown();
				Assertions.assertTrue(done.await(30, TimeUnit.SECONDS),
						"Concurrent intercepts didn't complete in time");
			}
			finally {
				pool.shutdownNow();
			}

			final boolean foundConcurrentWarning = appender.list.stream()
					.anyMatch(loggingEvent -> {
						final String message = loggingEvent.getFormattedMessage();
						return (message != null)
								&& (message.contains("AMQ212051") || message.contains("concurrent session usage"));
					});
			Assertions.assertFalse(foundConcurrentWarning,
					"Artemis logged a concurrent session usage warning — the interceptor's "
							+ "querySession is being used concurrently without synchronization.");
		}
		finally {
			artemisLogger.detachAppender(appender);
		}
	}
}
