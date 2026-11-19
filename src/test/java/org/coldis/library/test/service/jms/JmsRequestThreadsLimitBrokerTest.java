package org.coldis.library.test.service.jms;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.JmsRequestThreadsLimit;
import org.coldis.library.service.jms.MeteredJmsPoolConnectionFactory;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.ResourceAllocationException;

/**
 * Covers the limit on request threads holding pooled JMS connections through
 * the pooled factory, against embedded brokers: a frozen broker with more
 * requests than permits, a permit held until the transaction a send joined
 * ends, and a permit given back when no connection could be made.
 * <p>
 * It runs its own embedded brokers but holds the shared containers, as
 * {@link ArtemisBrokerAddressChangeTest} does: with no class referencing them
 * for longer than their stop delay, the containers of its fork would stop
 * under the next classes' cached Spring context.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@DisplayName("JmsRequestThreadsLimit — through the pooled factory, against embedded brokers")
public class JmsRequestThreadsLimitBrokerTest extends ContainerTestHelper {

	/**
	 * Creates a pooled factory with a limit of 2 permits and a short wait.
	 *
	 * @param  connectionFactory Connection factory to pool.
	 * @param  limit             Limit.
	 * @return                   The pooled factory.
	 */
	private static MeteredJmsPoolConnectionFactory createPool(
			final ConnectionFactory connectionFactory,
			final JmsRequestThreadsLimit limit) {
		final JmsPoolConnectionFactoryProperties poolProperties = new JmsPoolConnectionFactoryProperties();
		// One connection, so every request gets the frozen one.
		poolProperties.setMaxConnections(1);
		final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(null, poolProperties, connectionFactory);
		pool.setRequestThreadsLimit(limit);
		return pool;
	}

	/**
	 * With the broker frozen and more requests sending to it than permits, the
	 * requests past the permits are refused after the maximum wait, instead of
	 * waiting on the broker with the others; the ones that hold a permit finish
	 * once the broker answers again, and the permits come back.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("With a frozen broker, the requests past the permits are refused after the wait")
	public void testFrozenBrokerRequestsPastTheLimitRefused() throws Exception {
		final int port = EmbeddedBrokerTestHelper.freePort();
		final EmbeddedActiveMQ broker = EmbeddedBrokerTestHelper.startBroker(port);
		final SimpleMeterRegistry registry = new SimpleMeterRegistry();
		final JmsRequestThreadsLimit limit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofMillis(500));
		limit.setMeterRegistry(registry);
		limit.sizeFromRequestThreads(4);
		final List<FutureTask<Object>> requests = new ArrayList<>();
		try (FreezableProxy proxy = new FreezableProxy(port);
				ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
						"tcp://127.0.0.1:" + proxy.getPort() + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY)) {
			final MeteredJmsPoolConnectionFactory pool = JmsRequestThreadsLimitBrokerTest.createPool(artemisFactory, limit);
			final JmsTemplate template = new JmsTemplate(pool);
			// Not a request thread: not limited.
			template.convertAndSend("limit-frozen", "before the freeze");
			Assertions.assertEquals(0, limit.getHeldPermits());

			// Five requests send to the frozen broker.
			proxy.freeze();
			for (int requestIndex = 0; requestIndex < 5; requestIndex++) {
				requests.add(JmsRequestThreadsLimitUnitTest.startOnRequestThread(() -> {
					template.convertAndSend("limit-frozen", "while frozen");
					return null;
				}));
			}

			// The three past the two permits are refused after the wait, while two wait on the broker.
			final long refusalStart = System.nanoTime();
			int refusedRequests = 0;
			for (final FutureTask<Object> request : requests) {
				try {
					request.get(3, TimeUnit.SECONDS);
					Assertions.fail("no send can finish while the broker is frozen");
				}
				catch (final ExecutionException exception) {
					Assertions.assertInstanceOf(ResourceAllocationException.class, NestedExceptionUtils.getMostSpecificCause(exception));
					refusedRequests++;
				}
				catch (final TimeoutException exception) {
					// Holds a permit and waits on the broker.
				}
			}
			Assertions.assertEquals(3, refusedRequests);
			Assertions.assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - refusalStart) < 10);
			Assertions.assertEquals(2, limit.getHeldPermits());
			Assertions.assertEquals(3.0, registry.get(JmsRequestThreadsLimit.REFUSED).counter().count());

			// Once the broker answers, the two finish and give their permits back.
			proxy.thaw();
			int finishedRequests = 0;
			for (final FutureTask<Object> request : requests) {
				try {
					request.get(15, TimeUnit.SECONDS);
					finishedRequests++;
				}
				catch (final ExecutionException exception) {
					// Refused above.
				}
			}
			Assertions.assertEquals(2, finishedRequests);
			Assertions.assertEquals(0, limit.getHeldPermits());
			pool.stop();
		}
		finally {
			for (final FutureTask<Object> request : requests) {
				request.cancel(true);
			}
			broker.stop();
		}
	}

	/**
	 * A send that joins a transaction holds its permit until the transaction
	 * ends, and further sends and connections of the same request hold the same
	 * one.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A send in a transaction holds its permit until the transaction ends")
	public void testPermitHeldUntilTransactionEnds() throws Exception {
		final int port = EmbeddedBrokerTestHelper.freePort();
		final EmbeddedActiveMQ broker = EmbeddedBrokerTestHelper.startBroker(port);
		final JmsRequestThreadsLimit limit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofMillis(500));
		limit.sizeFromRequestThreads(4);
		final TransactionTemplate transactionTemplate = new TransactionTemplate(new AbstractPlatformTransactionManager() {

			private static final long serialVersionUID = 1L;

			@Override
			protected Object doGetTransaction() {
				return new Object();
			}

			@Override
			protected void doBegin(
					final Object transaction,
					final TransactionDefinition definition) {
			}

			@Override
			protected void doCommit(
					final DefaultTransactionStatus status) {
			}

			@Override
			protected void doRollback(
					final DefaultTransactionStatus status) {
			}

		});
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + port + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY)) {
			final MeteredJmsPoolConnectionFactory pool = JmsRequestThreadsLimitBrokerTest.createPool(artemisFactory, limit);
			final JmsTemplate template = new JmsTemplate(pool);
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
			try {
				transactionTemplate.executeWithoutResult(status -> {
					template.convertAndSend("limit-transaction", "first");
					Assertions.assertEquals(1, limit.getHeldPermits());
					template.convertAndSend("limit-transaction", "second");
					try (Connection secondConnection = pool.createConnection()) {
						Assertions.assertEquals(1, limit.getHeldPermits());
					}
					catch (final JMSException exception) {
						Assertions.fail(exception);
					}
					Assertions.assertEquals(1, limit.getHeldPermits());
				});
				Assertions.assertEquals(0, limit.getHeldPermits());
			}
			finally {
				RequestContextHolder.resetRequestAttributes();
			}
			Assertions.assertEquals("first", EmbeddedBrokerTestHelper.receive(port, "limit-transaction"));
			pool.stop();
		}
		finally {
			broker.stop();
		}
	}

	/**
	 * A request whose connection cannot be made gives its permit back.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A connection that cannot be made gives its permit back")
	public void testPermitReturnedWhenConnectionFails() throws Exception {
		final JmsRequestThreadsLimit limit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofMillis(500));
		limit.sizeFromRequestThreads(4);
		final ConnectionFactory failingFactory = (ConnectionFactory) Proxy.newProxyInstance(ConnectionFactory.class.getClassLoader(),
				new Class<?>[] { ConnectionFactory.class }, (proxy, method, arguments) -> switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					case "toString" -> "failing connection factory";
					default -> throw new JMSException("The broker is gone.");
				});
		final MeteredJmsPoolConnectionFactory pool = JmsRequestThreadsLimitBrokerTest.createPool(failingFactory, limit);
		JmsRequestThreadsLimitUnitTest.startOnRequestThread(() -> {
			Assertions.assertThrows(JMSException.class, pool::createConnection);
			Assertions.assertEquals(0, limit.getHeldPermits());
			return null;
		}).get(5, TimeUnit.SECONDS);
		pool.stop();
	}

}
