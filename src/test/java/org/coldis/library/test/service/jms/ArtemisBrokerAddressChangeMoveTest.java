package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.client.FailoverEventType;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnection;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.service.jms.ArtemisBrokerAddressChange;
import org.coldis.library.service.jms.MeteredJmsPoolConnectionFactory;
import org.coldis.library.service.properties.PropertiesService;
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
import org.messaginghub.pooled.jms.JmsPoolConnection;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;

/**
 * Covers how a broker address change through the properties endpoint moves
 * the live connections of an Artemis connection factory, against embedded
 * brokers. Unless a test says otherwise, the updates come from the service's
 * own host: a request on a loopback address.
 * <p>
 * It runs its own embedded brokers but holds the shared containers, as
 * {@link ArtemisBrokerAddressChangeTest} does: with no class referencing them
 * for longer than their stop delay, the containers of its fork would stop
 * under the next classes' cached Spring context.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@DisplayName("ArtemisBrokerAddressChange — live connections follow a changed broker address")
public class ArtemisBrokerAddressChangeMoveTest extends ContainerTestHelper {

	/**
	 * Host property, as java_spring_properties_artemis sends it.
	 */
	private static final String HOST_PROPERTY = "connectionFactory.serverLocator.initialConnectors.0.params.host";

	/**
	 * Port property, as java_spring_properties_artemis sends it.
	 */
	private static final String PORT_PROPERTY = "connectionFactory.serverLocator.initialConnectors.0.params.port";

	/**
	 * Bean name of the pooled factory.
	 */
	private static final String BEAN_NAME = "brokerPool";

	/**
	 * Delay of the moves in these tests.
	 */
	private static final Duration DELAY = Duration.ofMillis(300);

	/**
	 * Binds a request on a loopback address, as the update scripts make.
	 */
	@BeforeEach
	public void bindLocalRequest() {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
	}

	/**
	 * Unbinds the request.
	 */
	@AfterEach
	public void unbindRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	/**
	 * A snapshot is taken of a pool or of the factory itself, tells only whether
	 * the address changed, and compares by value: the port as text equals the
	 * port the URL gave.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Notes the address of a pool or a factory and compares it by value")
	public void testSnapshot() throws Exception {
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory("tcp://127.0.0.1:61616")) {
			final JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
			pool.setConnectionFactory(artemisFactory);
			Assertions.assertNull(change.snapshot(new Object()));
			Assertions.assertNotNull(change.snapshot(artemisFactory));
			final ArtemisBrokerAddressChange.Snapshot snapshot = change.snapshot(pool);
			Assertions.assertFalse(snapshot.toString().contains("61616"));

			ReflectionHelper.setAttribute(pool, true, ArtemisBrokerAddressChangeMoveTest.HOST_PROPERTY, "127.0.0.1");
			ReflectionHelper.setAttribute(pool, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY, "61616");
			Assertions.assertFalse(snapshot.isChanged());
			ReflectionHelper.setAttribute(pool, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY, "61617");
			Assertions.assertTrue(snapshot.isChanged());
			pool.stop();
		}
		finally {
			change.destroy();
		}
	}

	/**
	 * A broker that stops answering with its connections open (as one does
	 * while it is being moved) holds a send for the whole call timeout, and the
	 * pool keeps handing its connection out. Sending the new address through the
	 * properties endpoint returns at once, releases the held send and the next
	 * send reaches the new broker, long before the call timeout.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A new address releases a send held by a frozen broker and the next send reaches the new broker")
	public void testFrozenBrokerConnectionsMoveToNewAddress() throws Exception {
		final int oldPort = EmbeddedBrokerTestHelper.freePort();
		final int newPort = EmbeddedBrokerTestHelper.freePort();
		final String queue = "address-change-frozen";
		final EmbeddedActiveMQ oldBroker = EmbeddedBrokerTestHelper.startBroker(oldPort);
		EmbeddedActiveMQ newBroker = null;
		final ExecutorService sender = Executors.newSingleThreadExecutor();
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		try (FreezableProxy proxy = new FreezableProxy(oldPort);
				ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
						"tcp://127.0.0.1:" + proxy.getPort() + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final JmsPoolConnectionFactoryProperties poolProperties = new JmsPoolConnectionFactoryProperties();
			// One connection, so every send after the freeze gets the frozen one.
			poolProperties.setMaxConnections(1);
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(), poolProperties, artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			final JmsTemplate template = new JmsTemplate(pool);
			template.convertAndSend(queue, "before the freeze");

			// The broker stops answering and a send waits on it.
			proxy.freeze();
			final Future<?> heldSend = sender.submit(() -> template.convertAndSend(queue, "held by the frozen broker"));
			Thread.sleep(2000);
			Assertions.assertFalse(heldSend.isDone(), "the send must wait on the frozen broker");

			// The broker comes back at a new address, sent as the update script sends it. The updates
			// return at once: the move runs apart from them.
			newBroker = EmbeddedBrokerTestHelper.startBroker(newPort);
			final long changed = System.nanoTime();
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.HOST_PROPERTY,
					"127.0.0.1", true);
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
					String.valueOf(newPort), true);
			Assertions.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - changed) < 1000, "the updates must not wait for the move");

			// The held send is released, well before its 20 s call timeout.
			try {
				heldSend.get(5, TimeUnit.SECONDS);
			}
			catch (final ExecutionException exception) {
				// Released with an error: its message went with the frozen broker.
			}
			catch (final TimeoutException exception) {
				Assertions.fail("the send held by the frozen broker was not released after the address change");
			}

			// The next send reaches the new broker.
			Exception lastFailure = null;
			boolean isSent = false;
			while (!isSent && (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - changed) < 10)) {
				try {
					template.convertAndSend(queue, "after the change");
					isSent = true;
				}
				catch (final Exception exception) {
					lastFailure = exception;
					Thread.sleep(200);
				}
			}
			Assertions.assertTrue(isSent, "no send reached the new broker: " + lastFailure);
			Assertions.assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - changed) < 10,
					"sending after the change must not wait for the call timeout");
			Assertions.assertEquals("after the change", EmbeddedBrokerTestHelper.receive(newPort, queue));
			pool.stop();
		}
		finally {
			change.destroy();
			sender.shutdownNow();
			oldBroker.stop();
			if (newBroker != null) {
				newBroker.stop();
			}
		}
	}

	/**
	 * A host and a port sent one after the other, both changed, move the live
	 * connections once, after the delay, and to both.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A host and a port sent one after the other move the connections once, to both")
	public void testHostAndPortMoveOnce() throws Exception {
		final int oldPort = EmbeddedBrokerTestHelper.freePort();
		final int newPort = EmbeddedBrokerTestHelper.freePort();
		final EmbeddedActiveMQ oldBroker = EmbeddedBrokerTestHelper.startBroker(oldPort);
		final EmbeddedActiveMQ newBroker = EmbeddedBrokerTestHelper.startBroker(newPort);
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + oldPort + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			try (Connection connection = pool.createConnection()) {
				final RemotingConnection liveConnection = ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection);
				final long oldBrokerConnections = oldBroker.getActiveMQServer().getTotalConnectionCount();
				final AtomicInteger failures = new AtomicInteger();
				((ActiveMQConnection) ((JmsPoolConnection) connection).getConnection()).setFailoverListener(eventType -> {
					if (eventType == FailoverEventType.FAILURE_DETECTED) {
						failures.incrementAndGet();
					}
				});

				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.HOST_PROPERTY,
						"localhost", true);
				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
						String.valueOf(newPort), true);
				// Nothing moved yet: the move waits for the delay.
				Assertions.assertSame(liveConnection, ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection));
				Assertions.assertEquals(0, failures.get());

				// The connection fails once, after the delay, and the pool replaces it with one to the new
				// host and port: the old broker gets no new connection, as it would from a move to the new
				// host on the old port.
				Assertions.assertTrue(TestHelper.waitUntilValid(failures::get, failuresCount -> failuresCount > 0, TestHelper.LONG_WAIT,
						TestHelper.VERY_SHORT_WAIT));
				Thread.sleep(ArtemisBrokerAddressChangeMoveTest.DELAY.toMillis() * 3);
				Assertions.assertEquals(1, failures.get());
				new JmsTemplate(pool).convertAndSend("address-change-once", "moved once");
				Assertions.assertEquals("moved once", EmbeddedBrokerTestHelper.receive(newPort, "address-change-once"));
				Assertions.assertEquals(oldBrokerConnections, oldBroker.getActiveMQServer().getTotalConnectionCount());
			}
			pool.stop();
		}
		finally {
			change.destroy();
			oldBroker.stop();
			newBroker.stop();
		}
	}

	/**
	 * The same address sent again (after a restart the update scripts no longer
	 * know what they applied) leaves the live connections alone.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The same address sent again leaves the live connections alone")
	public void testSameAddressKeepsConnections() throws Exception {
		final int port = EmbeddedBrokerTestHelper.freePort();
		final EmbeddedActiveMQ broker = EmbeddedBrokerTestHelper.startBroker(port);
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + port + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			try (Connection connection = pool.createConnection()) {
				final RemotingConnection liveConnection = ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection);
				Assertions.assertNotNull(liveConnection);

				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.HOST_PROPERTY,
						"127.0.0.1", true);
				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
						String.valueOf(port), true);
				Thread.sleep(ArtemisBrokerAddressChangeMoveTest.DELAY.toMillis() * 4);

				// A failed connection would have been replaced by a reconnect.
				Assertions.assertSame(liveConnection, ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection));
				new JmsTemplate(pool).convertAndSend("address-change-same", "same address");
			}
			pool.stop();
		}
		finally {
			change.destroy();
			broker.stop();
		}
	}

	/**
	 * A new address sent from another host, from outside a request, or with the
	 * move off, changes the address but leaves the live connections alone.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An update from another host or outside a request, or with the move off, leaves the live connections alone")
	public void testRemoteUpdateOrMoveOffKeepsConnections() throws Exception {
		final int oldPort = EmbeddedBrokerTestHelper.freePort();
		final int newPort = EmbeddedBrokerTestHelper.freePort();
		final EmbeddedActiveMQ oldBroker = EmbeddedBrokerTestHelper.startBroker(oldPort);
		final EmbeddedActiveMQ newBroker = EmbeddedBrokerTestHelper.startBroker(newPort);
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		final ArtemisBrokerAddressChange changeOff = new ArtemisBrokerAddressChange(false, ArtemisBrokerAddressChangeMoveTest.DELAY);
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + oldPort + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			try (Connection connection = pool.createConnection()) {
				final RemotingConnection liveConnection = ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection);

				// From another host: the request arrived on an address that is not loopback.
				final MockHttpServletRequest remoteRequest = new MockHttpServletRequest();
				remoteRequest.setLocalAddr("10.1.2.3");
				RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(remoteRequest));
				try {
					propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
							String.valueOf(newPort), true);
				}
				finally {
					RequestContextHolder.resetRequestAttributes();
				}
				// From outside a request.
				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.HOST_PROPERTY,
						"localhost", true);
				Thread.sleep(ArtemisBrokerAddressChangeMoveTest.DELAY.toMillis() * 4);
				Assertions.assertSame(liveConnection, ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection));

				// With the move off, from the service's own host.
				propertiesService.setArtemisBrokerAddressChange(changeOff);
				final MockHttpServletRequest localRequest = new MockHttpServletRequest();
				localRequest.setLocalAddr("127.0.0.1");
				RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(localRequest));
				try {
					propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
							String.valueOf(oldPort), true);
				}
				finally {
					RequestContextHolder.resetRequestAttributes();
				}
				Thread.sleep(ArtemisBrokerAddressChangeMoveTest.DELAY.toMillis() * 4);
				Assertions.assertSame(liveConnection, ArtemisBrokerAddressChangeMoveTest.remotingConnection(connection));
			}
			pool.stop();
		}
		finally {
			change.destroy();
			changeOff.destroy();
			oldBroker.stop();
			newBroker.stop();
		}
	}

	/**
	 * The library ships the move on, one second after the last change.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("Ships the move on, one second after the last change")
	public void testShippedDefaults() throws IOException {
		final Properties libraryDefaults = PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"));
		Assertions.assertEquals("true", libraryDefaults.getProperty(ArtemisBrokerAddressChange.PROPERTY_PREFIX + ".enabled"));
		Assertions.assertEquals("1s", libraryDefaults.getProperty(ArtemisBrokerAddressChange.PROPERTY_PREFIX + ".delay"));
	}

	/**
	 * Creates the properties service over a context holding the pooled factory.
	 *
	 * @param  context Context.
	 * @param  pool    Pooled factory.
	 * @param  change  Broker address change.
	 * @return         The properties service.
	 */
	private static PropertiesService propertiesService(
			final GenericApplicationContext context,
			final JmsPoolConnectionFactory pool,
			final ArtemisBrokerAddressChange change) {
		context.getBeanFactory().registerSingleton(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, pool);
		context.refresh();
		final PropertiesService propertiesService = new PropertiesService();
		propertiesService.setApplicationContext(context);
		propertiesService.setArtemisBrokerAddressChange(change);
		return propertiesService;
	}

	/**
	 * Gets the live connection under a pooled connection.
	 *
	 * @param  connection Pooled connection.
	 * @return            The live connection.
	 * @throws Exception  If it cannot be reached.
	 */
	private static RemotingConnection remotingConnection(
			final Connection connection) throws Exception {
		final ActiveMQConnection artemisConnection = (ActiveMQConnection) ((JmsPoolConnection) connection).getConnection();
		return ((ClientSessionFactoryInternal) artemisConnection.getSessionFactory()).getConnection();
	}

}
