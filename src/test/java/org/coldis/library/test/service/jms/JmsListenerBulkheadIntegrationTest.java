package org.coldis.library.test.service.jms;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.coldis.library.service.jms.ExtendedMessageListenerContainer;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsListenerBulkhead;
import org.coldis.library.service.jms.JmsListenerBulkheadRegistry;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * The bulkhead wired through the properties, the listener annotation processor
 * and the container factory, on real {@code @JmsListener} methods
 * ({@link JmsListenerBulkheadTestListener}): group {@code bulkhead-test}, limit
 * 2, selected by its factory bean name. The default group is not limited.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JMS listener bulkhead — @JmsListener integration")
public class JmsListenerBulkheadIntegrationTest extends ContainerTestHelper {

	/**
	 * Group of the test listeners.
	 */
	private static final String GROUP = "bulkhead-test";

	/**
	 * JMS template.
	 */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * Meter registry.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Bulkhead registry.
	 */
	@Autowired
	private JmsListenerBulkheadRegistry bulkheadRegistry;

	/**
	 * Listener endpoint registry.
	 */
	@Autowired
	private JmsListenerEndpointRegistry listenerEndpointRegistry;

	/**
	 * Releases the test listeners.
	 */
	@AfterEach
	public void releaseListeners() {
		JmsListenerBulkheadTestListener.release();
	}

	/**
	 * Gets the wait timer count of a destination.
	 *
	 * @param  destination Destination.
	 * @return             The count.
	 */
	private long getWaitCount(
			final String destination) {
		final Timer waitTimer = this.meterRegistry.find(JmsListenerBulkheadRegistry.WAIT_METRIC).tag("group", JmsListenerBulkheadIntegrationTest.GROUP)
				.tag("destination", destination).timer();
		return (waitTimer == null ? 0L : waitTimer.count());
	}

	/**
	 * Gets a gauge value of the test group.
	 *
	 * @param  name Name.
	 * @return      The value.
	 */
	private double getGauge(
			final String name) {
		return this.meterRegistry.find(name).tag("group", JmsListenerBulkheadIntegrationTest.GROUP).gauge().value();
	}

	/**
	 * If a stack trace has a transaction interceptor frame.
	 *
	 * @param  stackTrace Stack trace.
	 * @return            If it has a transaction interceptor frame.
	 */
	private static boolean hasTransactionFrame(
			final StackTraceElement[] stackTrace) {
		return Arrays.stream(stackTrace).anyMatch(
				frame -> frame.getClassName().contains("TransactionAspectSupport") || frame.getClassName().contains("AbstractTransactionAspect"));
	}

	/**
	 * Gets the stack traces of the threads with a frame of a class.
	 *
	 * @param  className Class name.
	 * @return           The stack traces.
	 */
	private static List<StackTraceElement[]> getStackTracesWithFrame(
			final String className) {
		return Thread.getAllStackTraces().values().stream()
				.filter(stackTrace -> Arrays.stream(stackTrace).anyMatch(frame -> frame.getClassName().equals(className))).toList();
	}

	/**
	 * Gets the listener adapter of a container.
	 *
	 * @param  listenerContainer Container.
	 * @return                   The listener adapter.
	 */
	private static ExtendedMessagingMessageListenerAdapter getAdapter(
			final MessageListenerContainer listenerContainer) {
		return Assertions.assertInstanceOf(ExtendedMessagingMessageListenerAdapter.class,
				((DefaultMessageListenerContainer) listenerContainer).getMessageListener());
	}

	/**
	 * Tests the limit, the metrics and that the permit is taken before the
	 * listener's transaction starts.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("At most 2 handlers of the group run; waiters wait at the gate, holding no message nor transaction; metrics follow")
	public void testLimitAndPermitBeforeTransaction() throws Exception {
		JmsListenerBulkheadTestListener.reset();
		final long firstWaitCountBefore = this.getWaitCount(JmsListenerBulkheadTestListener.FIRST_QUEUE);
		final long secondWaitCountBefore = this.getWaitCount(JmsListenerBulkheadTestListener.SECOND_QUEUE);
		for (int messageIndex = 0; messageIndex < 5; messageIndex++) {
			this.jmsTemplate.convertAndSend(JmsListenerBulkheadTestListener.FIRST_QUEUE, "first-" + messageIndex);
			this.jmsTemplate.convertAndSend(JmsListenerBulkheadTestListener.SECOND_QUEUE, "second-" + messageIndex);
		}
		JmsListenerBulkheadTestSupport.waitUntil(() -> (JmsListenerBulkheadTestListener.getProbe().getCurrent() == 2)
				&& (this.bulkheadRegistry.getBulkhead(JmsListenerBulkheadIntegrationTest.GROUP) != null)
				&& (this.bulkheadRegistry.getBulkhead(JmsListenerBulkheadIntegrationTest.GROUP).getWaiting() >= 1), "2 handlers running and waiters");
		final JmsListenerBulkhead bulkhead = this.bulkheadRegistry.getBulkhead(JmsListenerBulkheadIntegrationTest.GROUP);
		Assertions.assertEquals(2, bulkhead.getInFlight());
		Assertions.assertEquals(2D, this.getGauge(JmsListenerBulkheadRegistry.IN_FLIGHT_METRIC));
		Assertions.assertTrue(this.getGauge(JmsListenerBulkheadRegistry.WAITING_METRIC) >= 1D);
		Assertions.assertEquals(0D, this.getGauge(JmsListenerBulkheadRegistry.WAITING_WITH_MESSAGE_METRIC));
		Assertions.assertEquals(2D, this.getGauge(JmsListenerBulkheadRegistry.MAX_IN_FLIGHT_METRIC));

		// Waiting listeners have not started a transaction; running handlers have.
		final List<StackTraceElement[]> waitingStackTraces = JmsListenerBulkheadIntegrationTest
				.getStackTracesWithFrame(JmsListenerBulkhead.class.getName());
		final List<StackTraceElement[]> runningStackTraces = JmsListenerBulkheadIntegrationTest
				.getStackTracesWithFrame(JmsListenerBulkheadTestListener.class.getName());
		Assertions.assertFalse(waitingStackTraces.isEmpty());
		Assertions.assertEquals(2, runningStackTraces.size());
		waitingStackTraces.forEach(stackTrace -> Assertions.assertFalse(JmsListenerBulkheadIntegrationTest.hasTransactionFrame(stackTrace)));
		runningStackTraces.forEach(stackTrace -> Assertions.assertTrue(JmsListenerBulkheadIntegrationTest.hasTransactionFrame(stackTrace)));

		JmsListenerBulkheadTestListener.release();
		JmsListenerBulkheadTestSupport.waitUntil(() -> JmsListenerBulkheadTestListener.getProcessed() == 10, "all processed");
		Assertions.assertEquals(2, JmsListenerBulkheadTestListener.getProbe().getMaximum());
		Assertions.assertEquals(10, JmsListenerBulkheadTestListener.getTransactionActive().size());
		Assertions.assertTrue(JmsListenerBulkheadTestListener.getTransactionActive().stream().allMatch(Boolean.TRUE::equals));
		JmsListenerBulkheadTestSupport.waitUntil(() -> bulkhead.getInFlight() == 0, "permits released");
		Assertions.assertEquals(5L, this.getWaitCount(JmsListenerBulkheadTestListener.FIRST_QUEUE) - firstWaitCountBefore);
		Assertions.assertEquals(5L, this.getWaitCount(JmsListenerBulkheadTestListener.SECOND_QUEUE) - secondWaitCountBefore);
	}

	/**
	 * Tests the tags of the test factory's listeners and that the default group
	 * is not limited.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Listeners carry their factory bean name; the test factory's resolve to the test group, the default factory's are not limited")
	public void testListenerTagsAndDefaultGroup() throws Exception {
		final MessageListenerContainer firstContainer = this.listenerEndpointRegistry.getListenerContainer(JmsListenerBulkheadTestListener.FIRST_LISTENER);
		Assertions.assertEquals(ExtendedMessageListenerContainer.class, firstContainer.getClass());
		final ExtendedMessagingMessageListenerAdapter firstAdapter = JmsListenerBulkheadIntegrationTest.getAdapter(firstContainer);
		Assertions.assertEquals(JmsListenerBulkheadTestListener.CONTAINER_FACTORY, firstAdapter.getFactoryBeanName());
		Assertions.assertEquals(JmsListenerBulkheadIntegrationTest.GROUP,
				this.bulkheadRegistry.resolveGroup(firstAdapter.getFactoryBeanName(), firstAdapter.getBulkheadGroup()));

		final DefaultMessageListenerContainer droppedContainer = this.listenerEndpointRegistry.getListenerContainers().stream()
				.map(DefaultMessageListenerContainer.class::cast)
				.filter(listenerContainer -> Objects.equals(DroppedMessageTestListener.TEST_QUEUE, listenerContainer.getDestinationName())).findFirst()
				.orElseThrow();
		final ExtendedMessagingMessageListenerAdapter droppedAdapter = JmsListenerBulkheadIntegrationTest.getAdapter(droppedContainer);
		Assertions.assertEquals("jmsListenerContainerFactory", droppedAdapter.getFactoryBeanName());
		Assertions.assertTrue(this.bulkheadRegistry.isEnabled());
		Assertions.assertNull(this.bulkheadRegistry.bind(droppedContainer, droppedAdapter.getFactoryBeanName(), droppedAdapter.getBulkheadGroup()));
	}

}
