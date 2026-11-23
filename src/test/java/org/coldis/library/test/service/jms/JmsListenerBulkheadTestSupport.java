package org.coldis.library.test.service.jms;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.coldis.library.service.jms.ExtendedMethodJmsListenerEndpoint;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadGroupProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadRegistry;
import org.coldis.library.service.jms.StaleMessageFilterService;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;

/**
 * Builds listener containers outside the shared context's listener registry,
 * for the bulkhead tests that drive containers themselves: the listener
 * factories, endpoints and handlers of {@link JmsListenerExecutorStarvationTest}
 * and {@link JmsListenerBulkheadContainerTest}.
 */
public final class JmsListenerBulkheadTestSupport {

	/**
	 * Longest wait for a state a test expects.
	 */
	public static final Duration WAIT = Duration.ofSeconds(30);

	/**
	 * Handler logic of a test listener.
	 */
	@FunctionalInterface
	public interface MessageHandler {

		/**
		 * Handles a message.
		 *
		 * @param  message   Message.
		 * @throws Exception If the handler fails.
		 */
		void handle(Message message) throws Exception;

	}

	/**
	 * Listener bean whose method the endpoint invokes.
	 */
	public static final class HandlerBean {

		/**
		 * Handler logic.
		 */
		private final MessageHandler handler;

		/**
		 * Constructor.
		 *
		 * @param handler Handler logic.
		 */
		public HandlerBean(final MessageHandler handler) {
			this.handler = handler;
		}

		/**
		 * Consumes a message.
		 *
		 * @param  message   Message.
		 * @throws Exception If the handler fails.
		 */
		public void consume(
				final Message message) throws Exception {
			this.handler.handle(message);
		}

	}

	/**
	 * Tracks the handlers running and the most seen at once.
	 */
	public static final class InFlightProbe {

		/**
		 * Current.
		 */
		private final AtomicInteger current = new AtomicInteger();

		/**
		 * Maximum.
		 */
		private final AtomicInteger maximum = new AtomicInteger();

		/**
		 * Enters a handler.
		 */
		public void enter() {
			this.maximum.accumulateAndGet(this.current.incrementAndGet(), Math::max);
		}

		/**
		 * Exits a handler.
		 */
		public void exit() {
			this.current.decrementAndGet();
		}

		/**
		 * Gets the current.
		 *
		 * @return The current.
		 */
		public int getCurrent() {
			return this.current.get();
		}

		/**
		 * Gets the maximum.
		 *
		 * @return The maximum.
		 */
		public int getMaximum() {
			return this.maximum.get();
		}

	}

	/**
	 * No instances.
	 */
	private JmsListenerBulkheadTestSupport() {
	}

	/**
	 * Waits until a condition holds, then asserts it.
	 *
	 * @param condition   Condition.
	 * @param wait        Longest wait.
	 * @param description Description.
	 */
	public static void waitUntil(
			final BooleanSupplier condition,
			final Duration wait,
			final String description) {
		final long deadline = System.nanoTime() + wait.toNanos();
		while (!condition.getAsBoolean() && (System.nanoTime() < deadline)) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
		}
		Assertions.assertTrue(condition.getAsBoolean(), description);
	}

	/**
	 * Waits until a condition holds, then asserts it.
	 *
	 * @param condition   Condition.
	 * @param description Description.
	 */
	public static void waitUntil(
			final BooleanSupplier condition,
			final String description) {
		JmsListenerBulkheadTestSupport.waitUntil(condition, JmsListenerBulkheadTestSupport.WAIT, description);
	}

	/**
	 * Waits on a latch ignoring interrupts (a container shutdown interrupts the
	 * handlers it waits for; a test handler must still finish and commit), with
	 * the interrupt flag cleared afterwards so the session commit is not
	 * disturbed.
	 *
	 * @param latch Latch.
	 */
	public static void awaitIgnoringInterrupts(
			final CountDownLatch latch) {
		final long deadline = System.nanoTime() + JmsListenerBulkheadTestSupport.WAIT.toNanos();
		boolean released = false;
		while (!released && (System.nanoTime() < deadline)) {
			try {
				released = latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
			}
			catch (final InterruptedException exception) {
				released = (latch.getCount() == 0);
			}
		}
		Assertions.assertTrue(released, "test latch released");
	}

	/**
	 * Creates a helper with an unbounded listener executor that keeps no idle
	 * threads.
	 *
	 * @param  threadName Thread name prefix.
	 * @return            The helper.
	 */
	public static JmsConfigurationHelper createUnboundedHelper(
			final String threadName) {
		final JmsConfigurationHelper helper = new JmsConfigurationHelper();
		helper.setJmsListenerExecutor(threadName, 5, false, null, null, null, null, null, null, 0, null, 1, true);
		return helper;
	}

	/**
	 * Shuts the listener executor of a helper down (when it is a pool).
	 *
	 * @param helper Helper.
	 */
	public static void shutdownExecutor(
			final JmsConfigurationHelper helper) {
		if (ReflectionTestUtils.getField(helper, "jmsListenerExecutor") instanceof final ExecutorService executorService) {
			executorService.shutdownNow();
		}
	}

	/**
	 * Creates a container factory with the library's listener defaults
	 * (transacted session, 10 messages per task, consumer cache).
	 *
	 * @param  helper            Helper (gives the executor).
	 * @param  connectionFactory Native connection factory.
	 * @param  bulkheadGroup     Bulkhead group (may be null).
	 * @return                   The factory.
	 */
	public static DefaultJmsListenerContainerFactory createFactory(
			final JmsConfigurationHelper helper,
			final ConnectionFactory connectionFactory,
			final String bulkheadGroup) {
		return helper.createJmsListenerContainerFactoryBuilder().connectionFactory(connectionFactory).sessionTransacted(true).maxMessagesPerTask(10)
				.cacheLevel(3).bulkheadGroup(bulkheadGroup).build();
	}

	/**
	 * Creates a registry with one limited group.
	 *
	 * @param  group                 Group.
	 * @param  groupProperties       Group settings.
	 * @param  meterRegistryProvider Meter registry provider.
	 * @return                       The registry.
	 */
	public static JmsListenerBulkheadRegistry createRegistry(
			final String group,
			final JmsListenerBulkheadGroupProperties groupProperties,
			final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		final JmsListenerBulkheadProperties properties = new JmsListenerBulkheadProperties();
		properties.setEnabled(true);
		properties.getGroups().put(group, groupProperties);
		return new JmsListenerBulkheadRegistry(properties, meterRegistryProvider);
	}

	/**
	 * Creates a method endpoint as the listener annotation processor does.
	 *
	 * @param  id                        Endpoint id.
	 * @param  destination               Destination.
	 * @param  concurrency               Concurrency.
	 * @param  handler                   Handler logic.
	 * @param  jmsConverterProperties    JMS converter properties.
	 * @param  nonRetriableExceptions    Non-retriable exceptions.
	 * @param  staleMessageFilterService Stale message filter service (may be
	 *                                       null).
	 * @param  meterRegistry             Meter registry.
	 * @param  bulkheadRegistry          Bulkhead registry.
	 * @return                           The endpoint.
	 */
	public static ExtendedMethodJmsListenerEndpoint createEndpoint(
			final String id,
			final String destination,
			final String concurrency,
			final MessageHandler handler,
			final JmsConverterProperties jmsConverterProperties,
			final Collection<Class<?>> nonRetriableExceptions,
			final StaleMessageFilterService staleMessageFilterService,
			final MeterRegistry meterRegistry,
			final JmsListenerBulkheadRegistry bulkheadRegistry) {
		final ExtendedMethodJmsListenerEndpoint endpoint = new ExtendedMethodJmsListenerEndpoint(jmsConverterProperties, nonRetriableExceptions,
				() -> staleMessageFilterService, () -> meterRegistry, () -> bulkheadRegistry);
		final DefaultMessageHandlerMethodFactory messageHandlerMethodFactory = new DefaultMessageHandlerMethodFactory();
		messageHandlerMethodFactory.afterPropertiesSet();
		try {
			final Method method = HandlerBean.class.getMethod("consume", Message.class);
			endpoint.setBean(new HandlerBean(handler));
			endpoint.setMethod(method);
		}
		catch (final NoSuchMethodException exception) {
			throw new IllegalStateException(exception);
		}
		endpoint.setMessageHandlerMethodFactory(messageHandlerMethodFactory);
		endpoint.setId(id);
		endpoint.setDestination(destination);
		endpoint.setConcurrency(concurrency);
		return endpoint;
	}

	/**
	 * Creates a method endpoint without non-retriable exceptions nor stale
	 * filter.
	 *
	 * @param  id                     Endpoint id.
	 * @param  destination            Destination.
	 * @param  concurrency            Concurrency.
	 * @param  handler                Handler logic.
	 * @param  jmsConverterProperties JMS converter properties.
	 * @param  meterRegistry          Meter registry.
	 * @param  bulkheadRegistry       Bulkhead registry.
	 * @return                        The endpoint.
	 */
	public static ExtendedMethodJmsListenerEndpoint createEndpoint(
			final String id,
			final String destination,
			final String concurrency,
			final MessageHandler handler,
			final JmsConverterProperties jmsConverterProperties,
			final MeterRegistry meterRegistry,
			final JmsListenerBulkheadRegistry bulkheadRegistry) {
		return JmsListenerBulkheadTestSupport.createEndpoint(id, destination, concurrency, handler, jmsConverterProperties, List.of(), null, meterRegistry,
				bulkheadRegistry);
	}

	/**
	 * Creates and initializes (does not start) a container, as the listener
	 * endpoint registry does.
	 *
	 * @param  factory  Factory.
	 * @param  endpoint Endpoint.
	 * @return          The container.
	 */
	public static DefaultMessageListenerContainer createContainer(
			final DefaultJmsListenerContainerFactory factory,
			final JmsListenerEndpoint endpoint) {
		final DefaultMessageListenerContainer listenerContainer = factory.createListenerContainer(endpoint);
		listenerContainer.afterPropertiesSet();
		return listenerContainer;
	}

	/**
	 * Gets the delivery count of a message.
	 *
	 * @param  message Message.
	 * @return         The delivery count.
	 */
	public static int getDeliveryCount(
			final Message message) {
		try {
			return message.getIntProperty("JMSXDeliveryCount");
		}
		catch (final Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * Gets the id of a message.
	 *
	 * @param  message Message.
	 * @return         The id.
	 */
	public static String getMessageId(
			final Message message) {
		try {
			return message.getJMSMessageID();
		}
		catch (final Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

}
