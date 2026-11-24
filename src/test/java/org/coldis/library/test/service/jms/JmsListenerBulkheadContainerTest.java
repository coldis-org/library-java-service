package org.coldis.library.test.service.jms;

import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.coldis.library.service.jms.ExtendedMessageListenerContainer;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.service.jms.JmsListenerBulkhead;
import org.coldis.library.service.jms.JmsListenerBulkheadGroupProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadRegistry;
import org.coldis.library.service.jms.JmsListenerBulkheadReservationProperties;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.StaleMessageFilterProperties;
import org.coldis.library.service.jms.StaleMessageFilterService;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ErrorHandler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.QueueBrowser;

/**
 * The bulkhead in real listener containers against the broker. With the gate
 * ({@link ExtendedMessageListenerContainer}): a saturated bulkhead keeps the
 * messages on the queue, consumers do not scale up, idle queues hold no permit,
 * stop and shutdown roll nothing back, the bulkhead disabled changes nothing.
 * Without the gate (a plain container factory, consumers wait holding a
 * message): no message is lost or processed twice when a wait times out. Both:
 * failures release the permit, reserved permits and the stale filter around
 * the permit. Containers are built in the test (one bulkhead group per test),
 * so the shared context's listeners are untouched.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JMS listener bulkhead — listener containers")
public class JmsListenerBulkheadContainerTest extends ContainerTestHelper {

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
	 * Error handler.
	 */
	@Autowired
	@Qualifier("enhancedJmsErrorHandler")
	private ErrorHandler errorHandler;

	/**
	 * Data source.
	 */
	@Autowired
	private DataSource dataSource;

	/**
	 * Application context.
	 */
	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * Containers created by the current test.
	 */
	private final List<DefaultMessageListenerContainer> listenerContainers = new CopyOnWriteArrayList<>();

	/**
	 * Latches the current test's handlers may block on.
	 */
	private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

	/**
	 * Helpers (executors) created by the current test.
	 */
	private final List<JmsConfigurationHelper> helpers = new CopyOnWriteArrayList<>();

	/**
	 * Releases the handlers and shuts the containers and executors down.
	 */
	@AfterEach
	public void cleanUp() {
		this.latches.forEach(CountDownLatch::countDown);
		this.listenerContainers.forEach(DefaultMessageListenerContainer::shutdown);
		this.helpers.forEach(JmsListenerBulkheadTestSupport::shutdownExecutor);
		this.latches.clear();
		this.listenerContainers.clear();
		this.helpers.clear();
	}

	/**
	 * Creates a latch released after the test.
	 *
	 * @return The latch.
	 */
	private CountDownLatch createLatch() {
		final CountDownLatch latch = new CountDownLatch(1);
		this.latches.add(latch);
		return latch;
	}

	/**
	 * Creates a unique queue name.
	 *
	 * @param  scenario Scenario.
	 * @return          The queue name.
	 */
	private static String createQueue(
			final String scenario) {
		return "bulkhead/container/" + scenario + "/" + System.nanoTime();
	}

	/**
	 * Creates a container factory with an unbounded executor, for a bulkhead
	 * group.
	 *
	 * @param  group Group.
	 * @return       The factory.
	 */
	private DefaultJmsListenerContainerFactory createFactory(
			final String group) {
		final JmsConfigurationHelper helper = JmsListenerBulkheadTestSupport.createUnboundedHelper(group);
		this.helpers.add(helper);
		final DefaultJmsListenerContainerFactory factory = JmsListenerBulkheadTestSupport.createFactory(helper,
				ConnectionFactoryUnwrapper.unwrap(this.connectionFactory), group);
		factory.setErrorHandler(this.errorHandler);
		return factory;
	}

	/**
	 * Creates a plain container factory (not built by the library builder): its
	 * containers have no gate, so consumers wait for a permit holding the
	 * message they received. Its listeners use the default group.
	 *
	 * @return The factory.
	 */
	private DefaultJmsListenerContainerFactory createPlainFactory() {
		final JmsConfigurationHelper helper = JmsListenerBulkheadTestSupport.createUnboundedHelper("plain");
		this.helpers.add(helper);
		final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
		factory.setConnectionFactory(ConnectionFactoryUnwrapper.unwrap(this.connectionFactory));
		factory.setTaskExecutor((Executor) ReflectionTestUtils.getField(helper, "jmsListenerExecutor"));
		factory.setSessionTransacted(true);
		factory.setMaxMessagesPerTask(10);
		factory.setCacheLevel(3);
		factory.setErrorHandler(this.errorHandler);
		return factory;
	}

	/**
	 * Creates a registry whose default group (the group of a plain factory's
	 * listeners) is limited.
	 *
	 * @param  configurer Default group settings.
	 * @return            The registry.
	 */
	private JmsListenerBulkheadRegistry createDefaultGroupRegistry(
			final Consumer<JmsListenerBulkheadGroupProperties> configurer) {
		final JmsListenerBulkheadProperties properties = new JmsListenerBulkheadProperties();
		properties.setEnabled(true);
		configurer.accept(properties);
		return new JmsListenerBulkheadRegistry(properties, this.applicationContext.getBeanProvider(MeterRegistry.class));
	}

	/**
	 * Counts the messages on a queue that no consumer holds.
	 *
	 * @param  queue Queue.
	 * @return       The count.
	 */
	private int countOnQueue(
			final String queue) {
		final Integer count = this.jmsTemplate.browse(queue, (
				session,
				browser) -> JmsListenerBulkheadContainerTest.count(browser));
		return (count == null ? 0 : count);
	}

	/**
	 * Counts the messages a browser sees.
	 *
	 * @param  browser       Browser.
	 * @return               The count.
	 * @throws JMSException If the browser fails.
	 */
	private static int count(
			final QueueBrowser browser) throws JMSException {
		int count = 0;
		for (final Enumeration<?> messages = browser.getEnumeration(); messages.hasMoreElements(); messages.nextElement()) {
			count++;
		}
		return count;
	}

	/**
	 * Waits until a condition has held continuously for a period, then asserts
	 * it.
	 *
	 * @param condition   Condition.
	 * @param period      Period the condition must hold.
	 * @param description Description.
	 */
	private static void waitUntilStable(
			final BooleanSupplier condition,
			final Duration period,
			final String description) {
		final long deadline = System.nanoTime() + JmsListenerBulkheadTestSupport.WAIT.toNanos();
		long heldSince = System.nanoTime();
		while (((System.nanoTime() - heldSince) < period.toNanos()) && (System.nanoTime() < deadline)) {
			if (!condition.getAsBoolean()) {
				heldSince = System.nanoTime();
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
		}
		Assertions.assertTrue(condition.getAsBoolean() && ((System.nanoTime() - heldSince) >= period.toNanos()), description);
	}

	/**
	 * Creates a registry with one group.
	 *
	 * @param  group           Group.
	 * @param  groupProperties Group settings.
	 * @return                 The registry.
	 */
	private JmsListenerBulkheadRegistry createRegistry(
			final String group,
			final JmsListenerBulkheadGroupProperties groupProperties) {
		return JmsListenerBulkheadTestSupport.createRegistry(group, groupProperties, this.applicationContext.getBeanProvider(MeterRegistry.class));
	}

	/**
	 * Creates (not started) a container of a queue.
	 *
	 * @param  factory     Factory.
	 * @param  registry    Registry.
	 * @param  queue       Queue.
	 * @param  concurrency Concurrency.
	 * @param  handler     Handler.
	 * @return             The container.
	 */
	private DefaultMessageListenerContainer createContainer(
			final DefaultJmsListenerContainerFactory factory,
			final JmsListenerBulkheadRegistry registry,
			final String queue,
			final String concurrency,
			final JmsListenerBulkheadTestSupport.MessageHandler handler) {
		final DefaultMessageListenerContainer listenerContainer = JmsListenerBulkheadTestSupport.createContainer(factory,
				JmsListenerBulkheadTestSupport.createEndpoint(queue + "/" + System.nanoTime(), queue, concurrency, handler, this.jmsConverterProperties,
						this.meterRegistry, registry));
		this.listenerContainers.add(listenerContainer);
		return listenerContainer;
	}

	/**
	 * Gets an aborted-wait count.
	 *
	 * @param  group  Group.
	 * @param  queue  Queue.
	 * @param  reason Reason tag.
	 * @return        The count.
	 */
	private double getAborted(
			final String group,
			final String queue,
			final String reason) {
		final Counter counter = this.meterRegistry.find(JmsListenerBulkheadRegistry.WAIT_ABORTED_METRIC).tag("group", group).tag("destination", queue)
				.tag("reason", reason).counter();
		return (counter == null ? 0D : counter.count());
	}

	/**
	 * Sends text messages.
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
	 * Asserts that a queue is empty.
	 *
	 * @param queue Queue.
	 */
	private void assertQueueEmpty(
			final String queue) {
		final JmsTemplate receiveTemplate = new JmsTemplate(this.connectionFactory);
		receiveTemplate.setReceiveTimeout(TestHelper.VERY_SHORT_WAIT);
		Assertions.assertNull(receiveTemplate.receive(queue), queue);
	}

	/**
	 * Asserts that no message of a queue reached the dead letter queue.
	 *
	 * @param queue Queue.
	 */
	private void assertNotDeadLettered(
			final String queue) {
		final JmsTemplate receiveTemplate = new JmsTemplate(this.connectionFactory);
		receiveTemplate.setReceiveTimeout(TestHelper.VERY_SHORT_WAIT);
		Assertions.assertNull(receiveTemplate.receiveSelected("DLQ", "_AMQ_ORIG_ADDRESS = '" + queue + "'"), queue);
	}

	/**
	 * Creates group settings with a limit.
	 *
	 * @param  maxInFlight Limit.
	 * @return             The group settings.
	 */
	private static JmsListenerBulkheadGroupProperties createGroupProperties(
			final int maxInFlight) {
		final JmsListenerBulkheadGroupProperties groupProperties = new JmsListenerBulkheadGroupProperties();
		groupProperties.setMaxInFlight(maxInFlight);
		return groupProperties;
	}

	/**
	 * Tests that a wait timeout of a consumer holding a message gives the message
	 * back without losing it, only for the first max-wait-deliveries deliveries.
	 * The gate keeps messages on the queue, so only a container without it (or a
	 * consumer racing for the last permit) waits holding a message: a plain
	 * factory's container is used.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Without the gate, a wait timeout rolls the message back for the first max-wait-deliveries deliveries, then waits, and it is processed once")
	public void testWaitTimeoutLosesNothing() throws Exception {
		final String group = JmsListenerBulkheadProperties.DEFAULT_GROUP;
		final String queue = JmsListenerBulkheadContainerTest.createQueue("timeout");
		final JmsListenerBulkheadRegistry registry = this.createDefaultGroupRegistry(groupProperties -> {
			groupProperties.setMaxInFlight(1);
			groupProperties.setMaxWait(Duration.ofMillis(300));
			groupProperties.setMaxWaitDeliveries(2);
		});
		final CountDownLatch release = this.createLatch();
		final Map<String, Integer> processedDeliveryCounts = new ConcurrentHashMap<>();
		final AtomicInteger processed = new AtomicInteger();
		final DefaultMessageListenerContainer listenerContainer = this.createContainer(this.createPlainFactory(), registry, queue, "2", message -> {
			if (processed.getAndIncrement() == 0) {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			}
			processedDeliveryCounts.merge(JmsListenerBulkheadTestSupport.getMessageId(message), JmsListenerBulkheadTestSupport.getDeliveryCount(message),
					(previous, current) -> -1);
		});
		Assertions.assertEquals(DefaultMessageListenerContainer.class, listenerContainer.getClass());
		this.send(queue, 2);
		listenerContainer.start();

		// Deliveries 1 and 2 of the waiting message time out; delivery 3 waits.
		JmsListenerBulkheadTestSupport.waitUntil(() -> this.getAborted(group, queue, "timeout") == 2D, "two wait timeouts");
		Assertions.assertFalse(TestHelper.waitUntilValid(() -> this.getAborted(group, queue, "timeout"), aborted -> aborted > 2D, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT));
		JmsListenerBulkheadTestSupport.waitUntil(() -> registry.getBulkhead(group).getWaitingWithMessage() == 1, "third delivery waiting");
		release.countDown();

		// Both processed exactly once, the waiting one on its third delivery.
		JmsListenerBulkheadTestSupport.waitUntil(() -> processedDeliveryCounts.size() == 2, "both processed");
		Assertions.assertFalse(TestHelper.waitUntilValid(processed::get, processedCount -> processedCount > 2, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(List.of(1, 3), processedDeliveryCounts.values().stream().sorted().toList());
		Assertions.assertEquals(0D, this.getAborted(group, queue, "stopped"));
		listenerContainer.shutdown();
		this.assertQueueEmpty(queue);
		this.assertNotDeadLettered(queue);
	}

	/**
	 * Tests that a saturated bulkhead keeps the messages on the queue: the gated
	 * consumers do not receive.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Saturated bulkhead: gated consumers do not receive, the messages stay on the queue, each is delivered once")
	public void testSaturatedGateKeepsMessagesOnQueue() throws Exception {
		final String group = "saturated-" + System.nanoTime();
		final String queue = JmsListenerBulkheadContainerTest.createQueue("saturated");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final CountDownLatch release = this.createLatch();
		final Map<String, Integer> processedDeliveryCounts = new ConcurrentHashMap<>();
		final AtomicInteger processed = new AtomicInteger();
		final DefaultMessageListenerContainer listenerContainer = this.createContainer(this.createFactory(group), registry, queue, "3-3", message -> {
			if (processed.getAndIncrement() == 0) {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			}
			processedDeliveryCounts.put(JmsListenerBulkheadTestSupport.getMessageId(message), JmsListenerBulkheadTestSupport.getDeliveryCount(message));
		});
		Assertions.assertInstanceOf(ExtendedMessageListenerContainer.class, listenerContainer);
		this.send(queue, 6);
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> (registry.getBulkhead(group) != null) && (registry.getBulkhead(group).getWaiting() == 2),
				"two consumers gated");
		final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);

		// One message runs; the other five stay on the queue, held by no consumer.
		Assertions.assertEquals(0, bulkhead.getWaitingWithMessage());
		Assertions.assertEquals(1, bulkhead.getInFlight());
		Assertions.assertEquals(5, this.countOnQueue(queue));
		Assertions.assertFalse(TestHelper.waitUntilValid(() -> this.countOnQueue(queue), count -> count < 5, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(1, processed.get());

		release.countDown();
		JmsListenerBulkheadTestSupport.waitUntil(() -> processedDeliveryCounts.size() == 6, "all processed");
		Assertions.assertEquals(List.of(1, 1, 1, 1, 1, 1), List.copyOf(processedDeliveryCounts.values()));
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, "permits released");
		listenerContainer.shutdown();
		this.assertQueueEmpty(queue);
	}

	/**
	 * Tests that a backlogged container does not scale up while its consumers
	 * are gated, unlike a container without the gate.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Saturated bulkhead: a backlogged container does not scale up its consumers; without the gate it climbs to its maximum, each holding a message")
	public void testNoScaleUpWhileGated() throws Exception {
		final String group = "scale-" + System.nanoTime();
		final String gatedQueue = JmsListenerBulkheadContainerTest.createQueue("scale-gated");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final CountDownLatch release = this.createLatch();
		final AtomicInteger gatedProcessed = new AtomicInteger();
		final DefaultMessageListenerContainer gatedContainer = this.createContainer(this.createFactory(group), registry, gatedQueue, "1-10", message -> {
			if (gatedProcessed.getAndIncrement() == 0) {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			}
		});
		this.send(gatedQueue, 30);
		gatedContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> gatedProcessed.get() == 1, "first message running");
		Assertions.assertFalse(TestHelper.waitUntilValid(gatedContainer::getScheduledConsumerCount, consumers -> consumers > 2, TestHelper.REGULAR_WAIT,
				TestHelper.SHORT_WAIT));
		Assertions.assertEquals(0, registry.getBulkhead(group).getWaitingWithMessage());
		Assertions.assertEquals(29, this.countOnQueue(gatedQueue));

		// The same load without the gate: every new consumer receives a message and
		// starts another one, up to the maximum, each waiting with its message.
		final String plainQueue = JmsListenerBulkheadContainerTest.createQueue("scale-plain");
		final JmsListenerBulkheadRegistry plainRegistry = this.createDefaultGroupRegistry(groupProperties -> groupProperties.setMaxInFlight(1));
		final AtomicInteger plainProcessed = new AtomicInteger();
		final DefaultMessageListenerContainer plainContainer = this.createContainer(this.createPlainFactory(), plainRegistry, plainQueue, "1-10", message -> {
			if (plainProcessed.getAndIncrement() == 0) {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			}
		});
		this.send(plainQueue, 30);
		plainContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> (plainContainer.getScheduledConsumerCount() == 10)
				&& (plainRegistry.getBulkhead(JmsListenerBulkheadProperties.DEFAULT_GROUP).getWaitingWithMessage() == 9), "plain container scaled up");

		release.countDown();
		JmsListenerBulkheadTestSupport.waitUntil(() -> (gatedProcessed.get() == 30) && (plainProcessed.get() == 30), "all processed");
		gatedContainer.shutdown();
		plainContainer.shutdown();
		this.assertQueueEmpty(gatedQueue);
		this.assertQueueEmpty(plainQueue);
	}

	/**
	 * Tests that the consumers of an idle queue hold no permit and do not slow a
	 * busy listener down.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Idle queues hold no permit while they poll, and do not keep a busy listener of the same group out")
	public void testIdleQueueHoldsNoPermit() throws Exception {
		final String group = "idle-" + System.nanoTime();
		final String idleQueue = JmsListenerBulkheadContainerTest.createQueue("idle");
		final String busyQueue = JmsListenerBulkheadContainerTest.createQueue("busy");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final DefaultJmsListenerContainerFactory factory = this.createFactory(group);
		final DefaultMessageListenerContainer idleContainer = this.createContainer(factory, registry, idleQueue, "5-5", message -> {
		});
		final JmsListenerBulkheadTestSupport.InFlightProbe probe = new JmsListenerBulkheadTestSupport.InFlightProbe();
		final AtomicInteger busyProcessed = new AtomicInteger();
		final DefaultMessageListenerContainer busyContainer = this.createContainer(factory, registry, busyQueue, "1", message -> {
			probe.enter();
			busyProcessed.incrementAndGet();
			probe.exit();
		});
		idleContainer.start();

		// Each consumer's first receive takes the permit (it has no history yet) and
		// comes back empty within the short receive timeout; after that the idle
		// consumers poll without a permit, and nobody waits at the gate.
		final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);
		JmsListenerBulkheadContainerTest.waitUntilStable(() -> (bulkhead.getInFlight() == 0) && (bulkhead.getWaiting() == 0),
				Duration.ofMillis(ExtendedMessageListenerContainer.PERMIT_RECEIVE_TIMEOUT_MILLIS * 5), "idle consumers polling without permit");
		Assertions.assertFalse(TestHelper.waitUntilValid(bulkhead::getInFlight, inFlight -> inFlight > 0, TestHelper.REGULAR_WAIT, 10));

		// The busy listener gets the only permit whenever it has a message.
		this.send(busyQueue, 50);
		final long startNanos = System.nanoTime();
		busyContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> busyProcessed.get() == 50, "busy messages processed");
		Assertions.assertTrue((System.nanoTime() - startNanos) < Duration.ofSeconds(15).toNanos(), "busy listener slowed down by idle pollers");
		Assertions.assertEquals(1, probe.getMaximum());
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, "permit released");
		idleContainer.shutdown();
		busyContainer.shutdown();
		this.assertQueueEmpty(busyQueue);
	}

	/**
	 * Tests that a disabled bulkhead changes nothing in the gated container.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Bulkhead disabled: the container receives and runs handlers as before, without gate nor limit")
	public void testDisabledBulkheadUnchanged() throws Exception {
		final String group = "disabled-" + System.nanoTime();
		final String queue = JmsListenerBulkheadContainerTest.createQueue("disabled");
		final JmsListenerBulkheadProperties properties = new JmsListenerBulkheadProperties();
		properties.setEnabled(false);
		properties.getGroups().put(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final JmsListenerBulkheadRegistry registry = new JmsListenerBulkheadRegistry(properties,
				this.applicationContext.getBeanProvider(MeterRegistry.class));
		final CountDownLatch release = this.createLatch();
		final JmsListenerBulkheadTestSupport.InFlightProbe probe = new JmsListenerBulkheadTestSupport.InFlightProbe();
		final AtomicInteger processed = new AtomicInteger();
		final DefaultMessageListenerContainer listenerContainer = this.createContainer(this.createFactory(group), registry, queue, "3", message -> {
			probe.enter();
			JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			processed.incrementAndGet();
			probe.exit();
		});
		Assertions.assertInstanceOf(ExtendedMessageListenerContainer.class, listenerContainer);
		this.send(queue, 3);
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> probe.getCurrent() == 3, "three handlers at once");
		Assertions.assertNull(registry.getBulkhead(group));
		release.countDown();
		JmsListenerBulkheadTestSupport.waitUntil(() -> processed.get() == 3, "all processed");
		listenerContainer.shutdown();
		this.assertQueueEmpty(queue);
	}

	/**
	 * Tests that a container stop ends the gate waits without rolling anything
	 * back, and a restart processes the remaining messages once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Container stop: gated consumers leave the gate holding nothing; after a restart each message is processed once, on its first delivery")
	public void testStopWhileGated() throws Exception {
		final String group = "stop-" + System.nanoTime();
		final String queue = JmsListenerBulkheadContainerTest.createQueue("stop");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final CountDownLatch release = this.createLatch();
		final Map<String, List<Integer>> processedDeliveryCounts = new ConcurrentHashMap<>();
		final AtomicInteger processed = new AtomicInteger();
		final DefaultMessageListenerContainer listenerContainer = this.createContainer(this.createFactory(group), registry, queue, "3-3", message -> {
			if (processed.getAndIncrement() == 0) {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			}
			processedDeliveryCounts.computeIfAbsent(JmsListenerBulkheadTestSupport.getMessageId(message), messageId -> new CopyOnWriteArrayList<>())
					.add(JmsListenerBulkheadTestSupport.getDeliveryCount(message));
		});
		this.send(queue, 3);
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> (registry.getBulkhead(group) != null) && (registry.getBulkhead(group).getWaiting() == 2),
				"two gated consumers");
		final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);
		Assertions.assertEquals(0, bulkhead.getWaitingWithMessage());

		listenerContainer.stop();
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getWaiting() == 0, Duration.ofMillis(TestHelper.LONG_WAIT), "gated consumers left on stop");
		Assertions.assertEquals(0D, this.getAborted(group, queue, "stopped"));
		release.countDown();
		JmsListenerBulkheadTestSupport.waitUntil(() -> processedDeliveryCounts.size() == 1, "first message processed");
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, "permit released");
		Assertions.assertEquals(2, this.countOnQueue(queue));

		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> processedDeliveryCounts.size() == 3, "remaining messages processed after the restart");
		Assertions.assertFalse(TestHelper.waitUntilValid(processed::get, processedCount -> processedCount > 3, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(List.of(List.of(1), List.of(1), List.of(1)), List.copyOf(processedDeliveryCounts.values()));
		listenerContainer.shutdown();
		this.assertQueueEmpty(queue);
	}

	/**
	 * Tests that a container shutdown ends the gate waits without rolling
	 * anything back, and a new container processes the remaining messages once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Container shutdown: gated consumers leave the gate holding nothing; a new container processes each remaining message once, on its first delivery")
	public void testShutdownWhileGated() throws Exception {
		final String group = "shutdown-" + System.nanoTime();
		final String queue = JmsListenerBulkheadContainerTest.createQueue("shutdown");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final DefaultJmsListenerContainerFactory factory = this.createFactory(group);
		final CountDownLatch release = this.createLatch();
		final Map<String, List<Integer>> processedDeliveryCounts = new ConcurrentHashMap<>();
		final AtomicInteger processed = new AtomicInteger();
		final JmsListenerBulkheadTestSupport.MessageHandler handler = message -> {
			if (processed.getAndIncrement() == 0) {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
			}
			processedDeliveryCounts.computeIfAbsent(JmsListenerBulkheadTestSupport.getMessageId(message), messageId -> new CopyOnWriteArrayList<>())
					.add(JmsListenerBulkheadTestSupport.getDeliveryCount(message));
		};
		final DefaultMessageListenerContainer listenerContainer = this.createContainer(factory, registry, queue, "3-3", handler);
		this.send(queue, 3);
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> (registry.getBulkhead(group) != null) && (registry.getBulkhead(group).getWaiting() == 2),
				"two gated consumers");
		final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);

		final Thread shutdownThread = Thread.ofPlatform().name("bulkhead-container-shutdown").start(listenerContainer::shutdown);
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getWaiting() == 0, Duration.ofMillis(TestHelper.LONG_WAIT), "gated consumers left on shutdown");
		Assertions.assertEquals(0D, this.getAborted(group, queue, "stopped") + this.getAborted(group, queue, "interrupted"));
		release.countDown();
		shutdownThread.join(JmsListenerBulkheadTestSupport.WAIT.toMillis());
		Assertions.assertFalse(shutdownThread.isAlive());
		Assertions.assertEquals(1, processedDeliveryCounts.size());
		Assertions.assertEquals(0, bulkhead.getInFlight());
		Assertions.assertEquals(2, this.countOnQueue(queue));

		final DefaultMessageListenerContainer nextContainer = this.createContainer(factory, registry, queue, "3-3", handler);
		nextContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> processedDeliveryCounts.size() == 3, "remaining messages processed by a new container");
		Assertions.assertFalse(TestHelper.waitUntilValid(processed::get, processedCount -> processedCount > 3, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(List.of(List.of(1), List.of(1), List.of(1)), List.copyOf(processedDeliveryCounts.values()));
		nextContainer.shutdown();
		this.assertQueueEmpty(queue);
	}

	/**
	 * Tests that a failing handler releases its permit and the retry is
	 * processed.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A handler failure releases the permit and the redelivery is processed")
	public void testHandlerFailureReleasesPermit() throws Exception {
		final String group = "failure-" + System.nanoTime();
		final String queue = JmsListenerBulkheadContainerTest.createQueue("failure");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final JmsListenerBulkheadTestSupport.InFlightProbe probe = new JmsListenerBulkheadTestSupport.InFlightProbe();
		final Map<String, Integer> processedDeliveryCounts = new ConcurrentHashMap<>();
		final DefaultMessageListenerContainer listenerContainer = this.createContainer(this.createFactory(group), registry, queue, "1-3", message -> {
			probe.enter();
			try {
				if (JmsListenerBulkheadTestSupport.getDeliveryCount(message) == 1) {
					throw new IllegalStateException("First delivery fails.");
				}
				processedDeliveryCounts.put(JmsListenerBulkheadTestSupport.getMessageId(message), JmsListenerBulkheadTestSupport.getDeliveryCount(message));
			}
			finally {
				probe.exit();
			}
		});
		this.send(queue, 3);
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> processedDeliveryCounts.size() == 3, "all redeliveries processed");
		Assertions.assertEquals(List.of(2, 2, 2), List.copyOf(processedDeliveryCounts.values()));
		Assertions.assertEquals(1, probe.getMaximum());
		JmsListenerBulkheadTestSupport.waitUntil(() -> registry.getBulkhead(group).getInFlight() == 0, "permits released");
	}

	/**
	 * Tests that a non-retriable failure releases its permit and drops the
	 * message.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A non-retriable failure releases the permit and drops the message as before")
	public void testNonRetriableFailureReleasesPermit() throws Exception {
		final String group = "drop-" + System.nanoTime();
		final String queue = JmsListenerBulkheadContainerTest.createQueue("drop");
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1));
		final AtomicInteger attempts = new AtomicInteger();
		final DefaultMessageListenerContainer listenerContainer = JmsListenerBulkheadTestSupport.createContainer(this.createFactory(group),
				JmsListenerBulkheadTestSupport.createEndpoint(queue, queue, "1", message -> {
					attempts.incrementAndGet();
					throw new IllegalArgumentException("Non-retriable.");
				}, this.jmsConverterProperties, List.of(IllegalArgumentException.class), null, this.meterRegistry, registry));
		this.listenerContainers.add(listenerContainer);
		this.send(queue, 1);
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> {
			final Counter dropped = this.meterRegistry.find(ExtendedMessagingMessageListenerAdapter.DROPPED_MESSAGES_METRIC).tag("destination", queue)
					.tag("exception", IllegalArgumentException.class.getName()).counter();
			return (dropped != null) && (dropped.count() == 1D);
		}, "message dropped");
		Assertions.assertFalse(TestHelper.waitUntilValid(attempts::get, attemptCount -> attemptCount > 1, TestHelper.REGULAR_WAIT, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(0, registry.getBulkhead(group).getInFlight());
	}

	/**
	 * Tests that a reserved destination is processed while unreserved listeners
	 * wait.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A reserved destination is processed while unreserved listeners wait for shared permits")
	public void testReservedDestination() throws Exception {
		final String group = "reserved-" + System.nanoTime();
		final String bulkQueue = JmsListenerBulkheadContainerTest.createQueue("reserved-bulk");
		final String criticalQueue = JmsListenerBulkheadContainerTest.createQueue("reserved-critical");
		final JmsListenerBulkheadGroupProperties groupProperties = JmsListenerBulkheadContainerTest.createGroupProperties(3);
		final JmsListenerBulkheadReservationProperties reservation = new JmsListenerBulkheadReservationProperties();
		reservation.setPermits(1);
		reservation.setDestinations(List.of(criticalQueue));
		groupProperties.getReserved().put("critical", reservation);
		final JmsListenerBulkheadRegistry registry = this.createRegistry(group, groupProperties);
		final DefaultJmsListenerContainerFactory factory = this.createFactory(group);
		final CountDownLatch release = this.createLatch();
		final JmsListenerBulkheadTestSupport.InFlightProbe probe = new JmsListenerBulkheadTestSupport.InFlightProbe();
		final AtomicInteger bulkProcessed = new AtomicInteger();
		final AtomicInteger criticalProcessed = new AtomicInteger();
		final DefaultMessageListenerContainer bulkContainer = this.createContainer(factory, registry, bulkQueue, "5-5", message -> {
			probe.enter();
			try {
				JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
				bulkProcessed.incrementAndGet();
			}
			finally {
				probe.exit();
			}
		});
		final DefaultMessageListenerContainer criticalContainer = this.createContainer(factory, registry, criticalQueue, "1", message -> {
			probe.enter();
			criticalProcessed.incrementAndGet();
			probe.exit();
		});
		this.send(bulkQueue, 5);
		bulkContainer.start();
		criticalContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(
				() -> (registry.getBulkhead(group) != null) && (registry.getBulkhead(group).getInFlight() == 2) && (registry.getBulkhead(group).getWaiting() >= 1),
				"bulk listeners hold the shared permits and wait");
		final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);

		this.send(criticalQueue, 1);
		JmsListenerBulkheadTestSupport.waitUntil(() -> criticalProcessed.get() == 1, "critical message processed");
		Assertions.assertTrue(bulkhead.getWaiting() >= 1, "bulk listeners still waiting");
		Assertions.assertEquals(0, bulkProcessed.get());
		release.countDown();
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkProcessed.get() == 5, "bulk messages processed");
		Assertions.assertTrue(probe.getMaximum() <= 3);
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, "permits released");
	}

	/**
	 * Runs the stale filter scenario: a keyed message is superseded while a
	 * blocker holds the only permit; it must be dropped without running the
	 * handler.
	 *
	 * @param  group     Group (default for the plain factory).
	 * @param  factory   Factory.
	 * @param  registry  Registry.
	 * @param  gated     If the factory's containers have the gate.
	 * @throws Exception If the test fails.
	 */
	private void assertStaleFilterAroundPermit(
			final String group,
			final DefaultJmsListenerContainerFactory factory,
			final JmsListenerBulkheadRegistry registry,
			final boolean gated) throws Exception {
		final String queue = JmsListenerBulkheadContainerTest.createQueue("stale");
		final String key = "key-" + System.nanoTime();
		final StaleMessageFilterProperties staleProperties = new StaleMessageFilterProperties();
		staleProperties.setEnabled(true);
		staleProperties.setPersistenceEnabled(false);
		staleProperties.setClockSkewMargin(Duration.ofMillis(100));
		final StaleMessageFilterService staleMessageFilterService = new StaleMessageFilterService(staleProperties,
				this.applicationContext.getBeanProvider(DataSource.class));
		final CountDownLatch release = this.createLatch();
		final List<String> handled = new CopyOnWriteArrayList<>();
		final DefaultMessageListenerContainer listenerContainer = JmsListenerBulkheadTestSupport.createContainer(factory,
				JmsListenerBulkheadTestSupport.createEndpoint(queue, queue, "2-2", message -> {
					handled.add(message.getStringProperty("name"));
					if ("blocker".equals(message.getStringProperty("name"))) {
						JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release);
					}
				}, this.jmsConverterProperties, List.of(), staleMessageFilterService, this.meterRegistry, registry));
		this.listenerContainers.add(listenerContainer);
		this.jmsTemplate.send(queue, session -> {
			final Message message = session.createTextMessage("blocker");
			message.setStringProperty("name", "blocker");
			return message;
		});
		listenerContainer.start();
		JmsListenerBulkheadTestSupport.waitUntil(() -> handled.contains("blocker"), "blocker holds the permit");
		this.jmsTemplate.send(queue, session -> {
			final Message message = session.createTextMessage("keyed");
			message.setStringProperty("name", "keyed");
			message.setStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY, key);
			message.setLongProperty(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY, 1000L);
			return message;
		});
		final JmsListenerBulkhead bulkhead = registry.getBulkhead(group);
		// With the gate the keyed message waits on the queue; without, in the consumer.
		if (gated) {
			JmsListenerBulkheadTestSupport.waitUntil(() -> (bulkhead.getWaiting() == 1) && (this.countOnQueue(queue) == 1), "keyed message on the queue");
		}
		else {
			JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getWaitingWithMessage() == 1, "keyed message waiting in the consumer");
		}

		// A same-key processing starts while the keyed message waits.
		staleMessageFilterService.recordProcessing(queue, key, 5000L);
		release.countDown();
		JmsListenerBulkheadTestSupport.waitUntil(() -> {
			final Counter dropped = this.meterRegistry.find(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC).tag("destination", queue)
					.counter();
			return (dropped != null) && (dropped.count() == 1D);
		}, "keyed message dropped");
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, "permit released");
		Assertions.assertEquals(List.of("blocker"), handled);
		listenerContainer.shutdown();
		this.assertQueueEmpty(queue);
	}

	/**
	 * Tests that a message superseded while it waited on the queue (gate) or in
	 * the consumer (no gate) is dropped without running the handler.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A keyed message superseded while it waited (on the queue behind the gate, or holding a consumer without it) is dropped without running the handler")
	public void testStaleFilterAroundPermit() throws Exception {
		final String group = "stale-" + System.nanoTime();
		this.assertStaleFilterAroundPermit(group, this.createFactory(group),
				this.createRegistry(group, JmsListenerBulkheadContainerTest.createGroupProperties(1)), true);
		this.assertStaleFilterAroundPermit(JmsListenerBulkheadProperties.DEFAULT_GROUP, this.createPlainFactory(),
				this.createDefaultGroupRegistry(groupProperties -> groupProperties.setMaxInFlight(1)), false);
	}

}
