package org.coldis.library.test.service.jms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.IntFunction;

import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.service.jms.JmsListenerBulkhead;
import org.coldis.library.service.jms.JmsListenerBulkheadGroupProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadRegistry;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpoint;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.jms.ConnectionFactory;

/**
 * The problem and its fix, on the same scenario: six queues with the
 * concurrency range 1-5, the first one flooded before its container starts,
 * then a few messages on each of the other five.
 * <ul>
 * <li>With the legacy listener executor concurrency limit, the light queues
 * process nothing and the flooded queue stops for good: consumers that hold a
 * slot wait for another one to start or resubmit consumers.</li>
 * <li>With the bulkhead (and the unbounded executor), every queue progresses,
 * the light ones while the flooded one still has a backlog, and handler
 * concurrency never exceeds the limit.</li>
 * </ul>
 * The containers are built in the test, so the shared context's listeners are
 * untouched.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JMS listener executor — starvation with the legacy limit, progress with the bulkhead")
public class JmsListenerExecutorStarvationTest extends ContainerTestHelper {

	/**
	 * Queues.
	 */
	private static final int QUEUES = 6;

	/**
	 * Messages in the flooded queue.
	 */
	private static final int FLOOD = 200;

	/**
	 * Messages in each light queue.
	 */
	private static final int LIGHT = 5;

	/**
	 * Concurrency range of every container.
	 */
	private static final String CONCURRENCY = "1-5";

	/**
	 * Handler time.
	 */
	private static final long HANDLER_MILLIS = 20;

	/**
	 * Connection factory (pooled; unwrapped for the containers).
	 */
	@Autowired
	private ConnectionFactory connectionFactory;

	/**
	 * JMS template.
	 */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * JMS converter properties.
	 */
	@Autowired
	private JmsConverterProperties jmsConverterProperties;

	/**
	 * Meter registry.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Application context.
	 */
	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * Creates the queue names of a run.
	 *
	 * @param  scenario Scenario.
	 * @return          The queue names.
	 */
	private static List<String> createQueues(
			final String scenario) {
		final long run = System.nanoTime();
		final List<String> queues = new ArrayList<>();
		for (int queueIndex = 0; queueIndex < JmsListenerExecutorStarvationTest.QUEUES; queueIndex++) {
			queues.add("bulkhead/starvation/" + scenario + "/" + run + "/" + queueIndex);
		}
		return queues;
	}

	/**
	 * Handles a message: counts it for its queue, taking the handler time.
	 *
	 * @param processed  Processed messages by queue.
	 * @param queueIndex Queue index.
	 * @param probe      In-flight probe (may be null).
	 */
	private static void handle(
			final AtomicIntegerArray processed,
			final int queueIndex,
			final JmsListenerBulkheadTestSupport.InFlightProbe probe) throws InterruptedException {
		if (probe != null) {
			probe.enter();
		}
		try {
			// Simulated handler work (not a synchronization wait).
			TimeUnit.MILLISECONDS.sleep(JmsListenerExecutorStarvationTest.HANDLER_MILLIS);
			processed.incrementAndGet(queueIndex);
		}
		finally {
			if (probe != null) {
				probe.exit();
			}
		}
	}

	/**
	 * Sends messages.
	 *
	 * @param queue    Queue.
	 * @param messages Messages.
	 */
	private void send(
			final String queue,
			final int messages) {
		for (int messageIndex = 0; messageIndex < messages; messageIndex++) {
			this.jmsTemplate.convertAndSend(queue, "message-" + messageIndex);
		}
	}

	/**
	 * Gets the processed messages of the light queues.
	 *
	 * @param  processed Processed messages by queue.
	 * @return           The processed messages of the light queues.
	 */
	private static int getLightProcessed(
			final AtomicIntegerArray processed) {
		int lightProcessed = 0;
		for (int queueIndex = 1; queueIndex < JmsListenerExecutorStarvationTest.QUEUES; queueIndex++) {
			lightProcessed += processed.get(queueIndex);
		}
		return lightProcessed;
	}

	/**
	 * Counts the threads parked in the legacy executor's concurrency throttle.
	 *
	 * @return The threads parked in the throttle.
	 */
	private static int countThrottledThreads() {
		return (int) Thread.getAllStackTraces().values().stream()
				.filter(stackTrace -> Arrays.stream(stackTrace).anyMatch(frame -> "onLimitReached".equals(frame.getMethodName())
						&& frame.getClassName().startsWith("org.springframework.util.ConcurrencyThrottleSupport")))
				.count();
	}

	/**
	 * Starts containers from a helper thread (with a limit below the container
	 * count the starting thread itself would block).
	 *
	 * @param listenerContainers Containers.
	 * @param threadName         Starting thread name.
	 */
	private static void startContainers(
			final List<DefaultMessageListenerContainer> listenerContainers,
			final String threadName) {
		Thread.ofPlatform().name(threadName).daemon(true).start(() -> listenerContainers.forEach(DefaultMessageListenerContainer::start));
	}

	/**
	 * Shuts down containers stuck on the legacy throttle: each shutdown runs on a
	 * daemon thread (the test thread would block on a container lock held by a
	 * parked consumer), and the parked consumer threads are interrupted until none
	 * is left.
	 *
	 * @param  listenerContainers   Containers.
	 * @param  threadName           Consumer thread name prefix.
	 * @return                      If consumer threads were left (asserted
	 *                              after the test's own assertions).
	 * @throws InterruptedException If interrupted.
	 */
	private static boolean shutdownStuckContainers(
			final List<DefaultMessageListenerContainer> listenerContainers,
			final String threadName) throws InterruptedException {
		final List<Thread> shutdownThreads = new ArrayList<>();
		for (final DefaultMessageListenerContainer listenerContainer : listenerContainers) {
			shutdownThreads.add(Thread.ofPlatform().name(threadName + "-shutdown").daemon(true).start(listenerContainer::shutdown));
		}
		final long deadline = System.nanoTime() + JmsListenerBulkheadTestSupport.WAIT.toNanos();
		boolean consumersLeft = true;
		while (consumersLeft && (System.nanoTime() < deadline)) {
			final List<Thread> consumerThreads = Thread.getAllStackTraces().keySet().stream()
					.filter(thread -> thread.getName().startsWith(threadName) && !thread.getName().endsWith("-shutdown")).toList();
			consumerThreads.forEach(Thread::interrupt);
			consumersLeft = !consumerThreads.isEmpty();
			if (consumersLeft) {
				TimeUnit.MILLISECONDS.sleep(250);
			}
		}
		for (final Thread shutdownThread : shutdownThreads) {
			shutdownThread.join(JmsListenerBulkheadTestSupport.WAIT.toMillis());
		}
		return consumersLeft;
	}

	/**
	 * Drains the messages left on the queues of a run (the broker container is
	 * reused across runs).
	 *
	 * @param queues Queues.
	 */
	private void drain(
			final List<String> queues) {
		final JmsTemplate receiveTemplate = new JmsTemplate(this.connectionFactory);
		receiveTemplate.setReceiveTimeout(TestHelper.VERY_SHORT_WAIT);
		for (final String queue : queues) {
			while (receiveTemplate.receive(queue) != null) {
				// Discards the message.
			}
		}
	}

	/**
	 * Creates the containers of the scenario, the flooded one first.
	 *
	 * @param  factory         Factory.
	 * @param  queues          Queues.
	 * @param  endpointFactory Endpoint of each queue index.
	 * @return                 The containers.
	 */
	private static List<DefaultMessageListenerContainer> createContainers(
			final DefaultJmsListenerContainerFactory factory,
			final List<String> queues,
			final IntFunction<JmsListenerEndpoint> endpointFactory) {
		final List<DefaultMessageListenerContainer> listenerContainers = new ArrayList<>();
		for (int queueIndex = 0; queueIndex < queues.size(); queueIndex++) {
			listenerContainers.add(JmsListenerBulkheadTestSupport.createContainer(factory, endpointFactory.apply(queueIndex)));
		}
		return listenerContainers;
	}

	/**
	 * Reproduces the problem: the legacy executor concurrency limit (8, above
	 * the 6 containers) stops every queue for good.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Legacy executor concurrency: the light queues make no progress and the flooded queue stops for good")
	@SuppressWarnings("deprecation")
	public void testLegacyExecutorConcurrencyStopsEveryQueue() throws Exception {
		final String threadName = "starvation-legacy-" + System.nanoTime();
		final List<String> queues = JmsListenerExecutorStarvationTest.createQueues("legacy");
		final AtomicIntegerArray processed = new AtomicIntegerArray(JmsListenerExecutorStarvationTest.QUEUES);
		final JmsConfigurationHelper helper = new JmsConfigurationHelper();
		helper.setJmsListenerExecutor(threadName, 5, false, 8, null, null, null, null, null, null, 20D, 60);
		final DefaultJmsListenerContainerFactory factory = JmsListenerBulkheadTestSupport.createFactory(helper,
				ConnectionFactoryUnwrapper.unwrap(this.connectionFactory), null);
		final List<DefaultMessageListenerContainer> listenerContainers = JmsListenerExecutorStarvationTest.createContainers(factory, queues, queueIndex -> {
			final SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
			endpoint.setId(queues.get(queueIndex));
			endpoint.setDestination(queues.get(queueIndex));
			endpoint.setConcurrency(JmsListenerExecutorStarvationTest.CONCURRENCY);
			endpoint.setMessageListener(message -> {
				try {
					JmsListenerExecutorStarvationTest.handle(processed, queueIndex, null);
				}
				catch (final InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
			return endpoint;
		});
		final int[] processedSnapshot = new int[JmsListenerExecutorStarvationTest.QUEUES];
		int throttledThreads = 0;
		boolean consumersLeft = false;
		try {
			this.send(queues.get(0), JmsListenerExecutorStarvationTest.FLOOD);
			JmsListenerExecutorStarvationTest.startContainers(listenerContainers, threadName + "-start");

			// Waits until the flooded queue stopped and consumers are parked in the throttle.
			final AtomicInteger previousFloodProcessed = new AtomicInteger(-1);
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
				final int floodProcessed = processed.get(0);
				final boolean stopped = (floodProcessed == previousFloodProcessed.getAndSet(floodProcessed))
						&& (JmsListenerExecutorStarvationTest.countThrottledThreads() >= 1);
				return stopped;
			}, stopped -> stopped, TestHelper.VERY_LONG_WAIT, TestHelper.REGULAR_WAIT), "flooded queue did not stop");
			final int floodProcessedBefore = processed.get(0);

			// The light queues get a few messages each and process none of them.
			for (int queueIndex = 1; queueIndex < JmsListenerExecutorStarvationTest.QUEUES; queueIndex++) {
				this.send(queues.get(queueIndex), JmsListenerExecutorStarvationTest.LIGHT);
			}
			Assertions.assertFalse(TestHelper.waitUntilValid(() -> JmsListenerExecutorStarvationTest.getLightProcessed(processed) + processed.get(0),
					total -> total > floodProcessedBefore, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT), "a queue progressed");

			// Snapshot before the cleanup frees slots.
			for (int queueIndex = 0; queueIndex < JmsListenerExecutorStarvationTest.QUEUES; queueIndex++) {
				processedSnapshot[queueIndex] = processed.get(queueIndex);
			}
			throttledThreads = JmsListenerExecutorStarvationTest.countThrottledThreads();
		}
		finally {
			consumersLeft = JmsListenerExecutorStarvationTest.shutdownStuckContainers(listenerContainers, threadName);
			this.drain(queues);
		}
		Assertions.assertEquals(0, Arrays.stream(processedSnapshot).skip(1).sum(), "light queues processed " + Arrays.toString(processedSnapshot));
		Assertions.assertTrue(processedSnapshot[0] < JmsListenerExecutorStarvationTest.FLOOD, Arrays.toString(processedSnapshot));
		Assertions.assertTrue(throttledThreads >= 1);
		Assertions.assertFalse(consumersLeft, "legacy consumer threads left");
	}

	/**
	 * The same scenario with the bulkhead (3 permits) and the unbounded executor.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Bulkhead: every queue progresses, the light ones during the flood, and in flight never exceeds the limit")
	public void testBulkheadKeepsEveryQueueProgressing() throws Exception {
		final String threadName = "starvation-bulkhead-" + System.nanoTime();
		final String group = "starvation-" + System.nanoTime();
		final List<String> queues = JmsListenerExecutorStarvationTest.createQueues("bulkhead");
		final AtomicIntegerArray processed = new AtomicIntegerArray(JmsListenerExecutorStarvationTest.QUEUES);
		final JmsListenerBulkheadTestSupport.InFlightProbe probe = new JmsListenerBulkheadTestSupport.InFlightProbe();
		final JmsListenerBulkheadGroupProperties groupProperties = new JmsListenerBulkheadGroupProperties();
		groupProperties.setMaxInFlight(3);
		final JmsListenerBulkheadRegistry registry = JmsListenerBulkheadTestSupport.createRegistry(group, groupProperties,
				this.applicationContext.getBeanProvider(MeterRegistry.class));
		final JmsConfigurationHelper helper = JmsListenerBulkheadTestSupport.createUnboundedHelper(threadName);
		final DefaultJmsListenerContainerFactory factory = JmsListenerBulkheadTestSupport.createFactory(helper,
				ConnectionFactoryUnwrapper.unwrap(this.connectionFactory), group);
		final List<DefaultMessageListenerContainer> listenerContainers = JmsListenerExecutorStarvationTest.createContainers(factory, queues,
				queueIndex -> JmsListenerBulkheadTestSupport.createEndpoint(queues.get(queueIndex), queues.get(queueIndex),
						JmsListenerExecutorStarvationTest.CONCURRENCY,
						message -> JmsListenerExecutorStarvationTest.handle(processed, queueIndex, probe), this.jmsConverterProperties, this.meterRegistry,
						registry));
		try {
			Assertions.assertEquals(group, ((ExtendedMessagingMessageListenerAdapter) listenerContainers.get(0).getMessageListener()).getBulkheadGroup());

			// Light containers first, then the flooded one.
			this.send(queues.get(0), JmsListenerExecutorStarvationTest.FLOOD);
			listenerContainers.stream().skip(1).forEach(DefaultMessageListenerContainer::start);
			JmsListenerBulkheadTestSupport.waitUntil(
					() -> listenerContainers.stream().skip(1).allMatch(listenerContainer -> listenerContainer.getActiveConsumerCount() >= 1),
					"light consumers started");
			listenerContainers.get(0).start();
			JmsListenerBulkheadTestSupport.waitUntil(() -> processed.get(0) >= 1, "flooded queue started");

			// The light queues finish while the flooded queue still has a backlog; the
			// flooded container scales to its maximum consumers although only 3 run.
			for (int queueIndex = 1; queueIndex < JmsListenerExecutorStarvationTest.QUEUES; queueIndex++) {
				this.send(queues.get(queueIndex), JmsListenerExecutorStarvationTest.LIGHT);
			}
			final AtomicBoolean floodScaledToMaximum = new AtomicBoolean();
			JmsListenerBulkheadTestSupport.waitUntil(() -> {
				floodScaledToMaximum.compareAndSet(false, listenerContainers.get(0).getScheduledConsumerCount() == 5);
				return JmsListenerExecutorStarvationTest.getLightProcessed(processed) == ((JmsListenerExecutorStarvationTest.QUEUES - 1)
						* JmsListenerExecutorStarvationTest.LIGHT);
			}, "light queues done");
			Assertions.assertTrue(processed.get(0) < JmsListenerExecutorStarvationTest.FLOOD, "light queues waited for the flood");
			JmsListenerBulkheadTestSupport.waitUntil(() -> {
				floodScaledToMaximum.compareAndSet(false, listenerContainers.get(0).getScheduledConsumerCount() == 5);
				return processed.get(0) == JmsListenerExecutorStarvationTest.FLOOD;
			}, "flooded queue done");
			Assertions.assertTrue(floodScaledToMaximum.get(), "flooded container scaled to its maximum consumers");
			Assertions.assertEquals(3, probe.getMaximum());

			// Bulkhead state and metrics.
			final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);
			JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, Duration.ofSeconds(5), "permits released");
			Assertions.assertEquals(0, bulkhead.getWaiting());
			long admitted = 0;
			double totalWaitMillis = 0;
			for (final String queue : queues) {
				final Timer waitTimer = this.meterRegistry.find(JmsListenerBulkheadRegistry.WAIT_METRIC).tag("group", group).tag("destination", queue).timer();
				Assertions.assertNotNull(waitTimer, queue);
				admitted += waitTimer.count();
				totalWaitMillis += waitTimer.totalTime(TimeUnit.MILLISECONDS);
			}
			Assertions.assertEquals(JmsListenerExecutorStarvationTest.FLOOD + ((JmsListenerExecutorStarvationTest.QUEUES - 1) * JmsListenerExecutorStarvationTest.LIGHT),
					admitted);
			Assertions.assertTrue(totalWaitMillis > 0);
			final Gauge maxInFlightGauge = this.meterRegistry.find(JmsListenerBulkheadRegistry.MAX_IN_FLIGHT_METRIC).tag("group", group).gauge();
			final Gauge inFlightGauge = this.meterRegistry.find(JmsListenerBulkheadRegistry.IN_FLIGHT_METRIC).tag("group", group).gauge();
			Assertions.assertEquals(3D, maxInFlightGauge.value());
			Assertions.assertEquals(0D, inFlightGauge.value());
		}
		finally {
			listenerContainers.forEach(DefaultMessageListenerContainer::shutdown);
			JmsListenerBulkheadTestSupport.shutdownExecutor(helper);
		}

		// Nothing left on the broker (no message was rolled back and lost track of).
		final JmsTemplate receiveTemplate = new JmsTemplate(this.connectionFactory);
		receiveTemplate.setReceiveTimeout(TestHelper.VERY_SHORT_WAIT);
		for (final String queue : queues) {
			Assertions.assertNull(receiveTemplate.receive(queue), queue);
		}
	}

}
