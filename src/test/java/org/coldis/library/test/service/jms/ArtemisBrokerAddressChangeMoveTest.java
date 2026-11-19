package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.client.FailoverEventType;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl;
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
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageListener;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

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
	public void beforeEach() {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
	}

	/**
	 * Unbinds the request.
	 */
	@AfterEach
	public void afterEach() {
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
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
				try {
					template.convertAndSend(queue, "after the change");
					return Boolean.TRUE;
				}
				catch (final Exception exception) {
					return Boolean.FALSE;
				}
			}, isSent -> isSent, TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT), "no send reached the new broker");
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
		// A long delay of its own, so a slow test thread between the two updates stays well within it.
		final Duration delay = Duration.ofSeconds(2);
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, delay);
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + oldPort + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			// A connection of the factory itself: it reconnects in place, so a second move would fail it
			// again (the pool closes a failed connection and opens the next one only when asked).
			try (ActiveMQConnection connection = (ActiveMQConnection) artemisFactory.createConnection()) {
				final ClientSessionFactoryInternal sessionFactory = (ClientSessionFactoryInternal) connection.getSessionFactory();
				final RemotingConnection liveConnection = sessionFactory.getConnection();
				final long oldBrokerConnections = oldBroker.getActiveMQServer().getTotalConnectionCount();
				final AtomicInteger failures = new AtomicInteger();
				connection.setFailoverListener(eventType -> {
					if (eventType == FailoverEventType.FAILURE_DETECTED) {
						failures.incrementAndGet();
					}
				});

				// Apart, as the update script sends them, and within the delay: without the restart of the
				// delay, the host alone would move the connection, and the port would move it again.
				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.HOST_PROPERTY,
						"localhost", true);
				Thread.sleep(150);
				propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
						String.valueOf(newPort), true);
				// Nothing moved yet: the move waits for the delay.
				Assertions.assertSame(liveConnection, sessionFactory.getConnection());
				Assertions.assertEquals(0, failures.get());

				// The connection fails once, after the delay, and reconnects to the new host and port: the
				// old broker gets no new connection, as it would from a move to the new host on the old port.
				Assertions.assertTrue(TestHelper.waitUntilValid(failures::get, failuresCount -> failuresCount > 0, TestHelper.LONG_WAIT,
						TestHelper.VERY_SHORT_WAIT));
				Thread.sleep(delay.toMillis() / 2);
				Assertions.assertEquals(1, failures.get());
				try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
					session.createProducer(session.createQueue("address-change-once")).send(session.createTextMessage("moved once"));
				}
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
	 * Starts a listener container on an Artemis factory itself, as services
	 * listen, and waits until its consumer is registered.
	 *
	 * @param  connectionFactory Artemis connection factory.
	 * @param  queue             Queue.
	 * @param  received          Where the texts it receives go.
	 * @return                   The listener container, started.
	 * @throws Exception         If it does not start.
	 */
	private static DefaultMessageListenerContainer startListener(
			final ActiveMQConnectionFactory connectionFactory,
			final String queue,
			final BlockingQueue<String> received) throws Exception {
		final DefaultMessageListenerContainer listener = new DefaultMessageListenerContainer();
		listener.setConnectionFactory(connectionFactory);
		listener.setDestinationName(queue);
		listener.setMessageListener((MessageListener) message -> {
			try {
				received.add(((TextMessage) message).getText());
			}
			catch (final JMSException exception) {
				throw new IllegalStateException(exception);
			}
		});
		listener.afterPropertiesSet();
		listener.start();
		Assertions.assertTrue(TestHelper.waitUntilValid(listener::isRegisteredWithDestination, isRegistered -> isRegistered, TestHelper.LONG_WAIT,
				TestHelper.VERY_SHORT_WAIT));
		return listener;
	}

	/**
	 * A listener, with the client settings the services ship (it reconnects in
	 * place, without end), follows the move and consumes from the new broker.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A listener follows a moved broker and consumes from the new one")
	public void testListenerFollowsMove() throws Exception {
		final int oldPort = EmbeddedBrokerTestHelper.freePort();
		final int newPort = EmbeddedBrokerTestHelper.freePort();
		final String queue = "address-change-listener";
		final EmbeddedActiveMQ oldBroker = EmbeddedBrokerTestHelper.startBroker(oldPort);
		final EmbeddedActiveMQ newBroker = EmbeddedBrokerTestHelper.startBroker(newPort);
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();
		DefaultMessageListenerContainer listener = null;
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + oldPort + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			listener = ArtemisBrokerAddressChangeMoveTest.startListener(artemisFactory, queue, received);
			EmbeddedBrokerTestHelper.send(oldPort, queue, "before the move");
			Assertions.assertEquals("before the move", received.poll(TestHelper.LONG_WAIT, TimeUnit.MILLISECONDS));

			// The listener stays on the old broker until the move, and consumes from the new one after it.
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
					String.valueOf(newPort), true);
			EmbeddedBrokerTestHelper.send(newPort, queue, "after the move");
			Assertions.assertEquals("after the move", received.poll(TestHelper.LONG_WAIT, TimeUnit.MILLISECONDS));
			pool.stop();
		}
		finally {
			if (listener != null) {
				listener.shutdown();
			}
			change.destroy();
			oldBroker.stop();
			newBroker.stop();
		}
	}

	/**
	 * A listener moved to an address that does not answer keeps trying it; it is
	 * left alone by the move to the right address that follows (it has no
	 * connection to fail), and takes that address at its next attempt.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A listener moved to an address that does not answer takes a later fix at its next attempt")
	public void testListenerMovedToDeadAddressTakesLaterFix() throws Exception {
		final int port = EmbeddedBrokerTestHelper.freePort();
		final int deadPort = EmbeddedBrokerTestHelper.freePort();
		final String queue = "address-change-listener-dead";
		final EmbeddedActiveMQ broker = EmbeddedBrokerTestHelper.startBroker(port);
		final ArtemisBrokerAddressChange change = new ArtemisBrokerAddressChange(true, ArtemisBrokerAddressChangeMoveTest.DELAY);
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();
		DefaultMessageListenerContainer listener = null;
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + port + "?" + EmbeddedBrokerTestHelper.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeMoveTest.propertiesService(context, pool, change);
			listener = ArtemisBrokerAddressChangeMoveTest.startListener(artemisFactory, queue, received);

			// To a port where nothing listens: once moved, the listener keeps trying it.
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
					String.valueOf(deadPort), true);
			Assertions.assertTrue(change.isMoving());
			final ServerLocatorImpl serverLocator = (ServerLocatorImpl) artemisFactory.getServerLocator();
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
				synchronized (serverLocator.getFactories()) {
					return serverLocator.getFactories().stream().allMatch(sessionFactory -> sessionFactory.numConnections() == 0);
				}
			}, isReconnecting -> isReconnecting, TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			EmbeddedBrokerTestHelper.send(port, queue, "while moved away");
			Assertions.assertNull(received.poll(ArtemisBrokerAddressChangeMoveTest.DELAY.toMillis() * 2, TimeUnit.MILLISECONDS));

			// Back to the broker: the listener reconnects at its next attempt and consumes again.
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeMoveTest.BEAN_NAME, true, ArtemisBrokerAddressChangeMoveTest.PORT_PROPERTY,
					String.valueOf(port), true);
			Assertions.assertEquals("while moved away", received.poll(TestHelper.LONG_WAIT, TimeUnit.MILLISECONDS));
			pool.stop();
		}
		finally {
			if (listener != null) {
				listener.shutdown();
			}
			change.destroy();
			broker.stop();
		}
	}

	/**
	 * A missing or negative delay stops the service from starting.
	 */
	@Test
	@DisplayName("Refuses a missing or negative delay")
	public void testInvalidDelayRefused() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ArtemisBrokerAddressChange(true, null));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new ArtemisBrokerAddressChange(true, Duration.ofSeconds(-1)));
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
