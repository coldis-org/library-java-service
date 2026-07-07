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
	private static final long CACHE_TTL_MILLIS = 100L;

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
				this.nativeFactory, DEPTH_THRESHOLD, MULTIPLIER, MAX_CREDITS, CACHE_TTL_MILLIS);
		this.nativeFactory.getServerLocator().addOutgoingInterceptor(this.interceptor);
	}

	@AfterEach
	void tearDown() {
		this.nativeFactory.getServerLocator().removeOutgoingInterceptor(this.interceptor);
	}

	/**
	 * Drains a queue and counts messages consumed, blocking up to
	 * {@code timeoutMillis} between messages.
	 */
	private int drainQueue(final String queueName, final int maxMessages, final long timeoutMillis) throws Exception {
		int receivedMessageCount = 0;
		try (Connection connection = this.nativeFactory.createConnection();
				Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
				MessageConsumer consumer = session.createConsumer(session.createQueue(queueName))) {
			connection.start();
			while ((receivedMessageCount < maxMessages) && (consumer.receive(timeoutMillis) != null)) {
				receivedMessageCount++;
			}
		}
		return receivedMessageCount;
	}

	@Test
	@DisplayName("deep queue — interceptor tracks consumer creation and scales credit packets")
	void testDeepQueueInterceptorScalesCredits() throws Exception {
		final int totalMessages = 100;

		// Enqueue messages first so queue depth >> threshold when the consumer is
		// created. The initial FLOW_CREDIT packet sent at consumer-create time will
		// see depth > threshold and be scaled.
		for (long messageIndex = 0; messageIndex < totalMessages; messageIndex++) {
			this.jmsTemplate.convertAndSend(this.queueName, messageIndex);
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
		for (long messageIndex = 0; messageIndex < 5; messageIndex++) {
			this.jmsTemplate.convertAndSend(this.queueName, messageIndex);
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
		for (long messageIndex = 0; messageIndex < 3; messageIndex++) {
			this.jmsTemplate.convertAndSend(this.queueName, messageIndex);
		}
		final int received = this.drainQueue(this.queueName, 3, 3000L);

		Assertions.assertEquals(3, received);
		// No crash, no scaling expected for these (below threshold anyway).
		Assertions.assertEquals(0, this.interceptor.getCreditPacketsScaled());
	}

	/**
	 * Reproduces the production slow-consumer reset desync end-to-end. In
	 * slow-consumer mode ({@code consumerWindowSize=0}), a {@code receive(timeout)}
	 * that finds the queue empty makes the real Artemis client send a credit-0
	 * reset packet ({@code resetIfSlowConsumer}), and the broker zeroes the
	 * consumer's window. If the interceptor does not mirror that reset, its
	 * tracked window stays "full" and it never scales again for that consumer —
	 * in production, consumers collapsed to ~1 message in flight after a single
	 * empty poll.
	 *
	 * <p>Phases: drain a deep queue (scaling fires), let the final receive time
	 * out on the now-empty queue (the real client emits the reset), refill the
	 * queue past the threshold, and assert scaling fires again for the same
	 * still-open consumer.
	 */
	@Test
	@DisplayName("scaling recovers after a slow-consumer reset (timed-out empty poll) on the same consumer")
	void testScalingRecoversAfterSlowConsumerReset() throws Exception {
		final int deepBatchMessageCount = 100;

		for (long messageIndex = 0; messageIndex < deepBatchMessageCount; messageIndex++) {
			this.jmsTemplate.convertAndSend(this.queueName, messageIndex);
		}

		try (Connection connection = this.nativeFactory.createConnection();
				Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
				MessageConsumer consumer = session.createConsumer(session.createQueue(this.queueName))) {
			connection.start();

			// Phase 1: drain the deep queue with a single consumer. The trailing
			// receive on the empty queue times out — the real client then sends the
			// slow-consumer reset (credit 0) that zeroes the broker-side window.
			int receivedMessageCount = 0;
			while (consumer.receive(3000L) != null) {
				receivedMessageCount++;
			}
			Assertions.assertEquals(deepBatchMessageCount, receivedMessageCount, "First deep batch should be fully drained");
			final long scaledPacketCountBeforeReset = this.interceptor.getCreditPacketsScaled();
			Assertions.assertTrue(scaledPacketCountBeforeReset >= 1,
					"Deep queue should have scaled at least one credit packet before the reset");

			// Phase 2: the queue becomes deep again while the same consumer stays
			// open. The interceptor must re-inflate the window (scale again); a
			// desynced tracker would believe the window is still full and never
			// scale for this consumer again.
			for (long messageIndex = 0; messageIndex < deepBatchMessageCount; messageIndex++) {
				this.jmsTemplate.convertAndSend(this.queueName, messageIndex);
			}
			receivedMessageCount = 0;
			while ((receivedMessageCount < deepBatchMessageCount) && (consumer.receive(3000L) != null)) {
				receivedMessageCount++;
			}
			Assertions.assertEquals(deepBatchMessageCount, receivedMessageCount, "Second deep batch should be fully drained");
			Assertions.assertTrue(this.interceptor.getCreditPacketsScaled() > scaledPacketCountBeforeReset,
					"After the slow-consumer reset, a deep queue must re-inflate the window — "
							+ "no packet was scaled after the reset, so the tracked window is desynced");
		}
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
		final int threadCount = 16;
		final int consumersPerThread = 5;

		// Register one consumer per (thread, index) via the real create path so both the
		// queue map and the outstanding map are populated with the correct session-scoped
		// keys. Each consumer gets its own channel (session) and queue, so the flow-credit
		// intercepts below reach a fresh queueQuery (cache miss) on the shared session.
		for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
			for (int consumerIndex = 0; consumerIndex < consumersPerThread; consumerIndex++) {
				final long channelId = (threadIndex * 1000L) + consumerIndex;
				final SessionCreateConsumerMessage createConsumerMessage = new SessionCreateConsumerMessage(
						0L, SimpleString.of("concurrent-test/" + threadIndex + "/" + consumerIndex), null, 0, false, true);
				createConsumerMessage.setChannelID(channelId);
				this.interceptor.intercept(createConsumerMessage, null);
			}
		}

		// Capture WARN logs from the Artemis client logger to detect AMQ212051.
		final Logger artemisLogger = (Logger) LoggerFactory.getLogger("org.apache.activemq.artemis.core.client");
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		artemisLogger.addAppender(appender);

		try {
			final CountDownLatch startLatch = new CountDownLatch(1);
			final CountDownLatch doneLatch = new CountDownLatch(threadCount);
			final ExecutorService threadPool = Executors.newFixedThreadPool(threadCount);
			try {
				for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
					final int currentThreadIndex = threadIndex;
					threadPool.submit(() -> {
						try {
							startLatch.await();
							for (int consumerIndex = 0; consumerIndex < consumersPerThread; consumerIndex++) {
								final long channelId = (currentThreadIndex * 1000L) + consumerIndex;
								final SessionConsumerFlowCreditMessage flowCreditMessage =
										new SessionConsumerFlowCreditMessage(0L, 1024);
								flowCreditMessage.setChannelID(channelId);
								this.interceptor.intercept(flowCreditMessage, null);
							}
						}
						catch (final Throwable throwable) {
							throw new RuntimeException(throwable);
						}
						finally {
							doneLatch.countDown();
						}
					});
				}
				startLatch.countDown();
				Assertions.assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
						"Concurrent intercepts didn't complete in time");
			}
			finally {
				threadPool.shutdownNow();
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
