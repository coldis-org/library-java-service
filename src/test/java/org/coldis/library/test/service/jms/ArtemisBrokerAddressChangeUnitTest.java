package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnection;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.service.jms.ArtemisBrokerAddressChange;
import org.coldis.library.service.jms.MeteredJmsPoolConnectionFactory;
import org.coldis.library.service.properties.PropertiesService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnection;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jms.core.JmsTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Covers how a broker address change through the properties endpoint moves
 * the live connections of an Artemis connection factory, against embedded
 * brokers.
 */
@DisplayName("ArtemisBrokerAddressChange — live connections follow a changed broker address")
public class ArtemisBrokerAddressChangeUnitTest {

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
	 * Client settings of the library (service.properties), so the calls on a
	 * frozen broker wait as long as they do in a service.
	 */
	private static final String CLIENT_QUERY = "useTopologyForLoadBalancing=false;callTimeout=20000;callFailoverTimeout=10000;reconnectAttempts=-1;"
			+ "retryInterval=3000;retryIntervalMultiplier=1.5;clientFailureCheckPeriod=20000;connectionTTL=120000;blockOnDurableSend=true";

	/**
	 * The factory is found on a pool or on itself, and an address compares by
	 * value: the port as text equals the port the URL gave.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Finds the Artemis factory and compares addresses by value")
	public void testConnectionFactoryAndAddress() throws Exception {
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory("tcp://127.0.0.1:61616")) {
			final JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
			pool.setConnectionFactory(artemisFactory);
			Assertions.assertSame(artemisFactory, ArtemisBrokerAddressChange.getConnectionFactory(pool));
			Assertions.assertSame(artemisFactory, ArtemisBrokerAddressChange.getConnectionFactory(artemisFactory));
			Assertions.assertNull(ArtemisBrokerAddressChange.getConnectionFactory(new Object()));
			Assertions.assertNull(ArtemisBrokerAddressChange.getAddress(null));

			final String address = ArtemisBrokerAddressChange.getAddress(artemisFactory);
			ReflectionHelper.setAttribute(pool, true, ArtemisBrokerAddressChangeUnitTest.HOST_PROPERTY, "127.0.0.1");
			ReflectionHelper.setAttribute(pool, true, ArtemisBrokerAddressChangeUnitTest.PORT_PROPERTY, "61616");
			Assertions.assertEquals(address, ArtemisBrokerAddressChange.getAddress(artemisFactory));
			ReflectionHelper.setAttribute(pool, true, ArtemisBrokerAddressChangeUnitTest.PORT_PROPERTY, "61617");
			Assertions.assertNotEquals(address, ArtemisBrokerAddressChange.getAddress(artemisFactory));
			pool.stop();
		}
	}

	/**
	 * A broker that stops answering with its connections open (as one does
	 * while it is being moved) holds a send for the whole call timeout, and the
	 * pool keeps handing its connection out. Sending the new address through the
	 * properties endpoint releases the held send and the next send reaches the
	 * new broker, long before the call timeout.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A new address releases a send held by a frozen broker and the next send reaches the new broker")
	public void testFrozenBrokerConnectionsMoveToNewAddress() throws Exception {
		final int oldPort = ArtemisBrokerAddressChangeUnitTest.freePort();
		final int newPort = ArtemisBrokerAddressChangeUnitTest.freePort();
		final String queue = "address-change-frozen";
		final EmbeddedActiveMQ oldBroker = ArtemisBrokerAddressChangeUnitTest.startBroker(oldPort);
		EmbeddedActiveMQ newBroker = null;
		final ExecutorService sender = Executors.newSingleThreadExecutor();
		try (FreezableProxy proxy = new FreezableProxy(oldPort);
				ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
						"tcp://127.0.0.1:" + proxy.getPort() + "?" + ArtemisBrokerAddressChangeUnitTest.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final JmsPoolConnectionFactoryProperties poolProperties = new JmsPoolConnectionFactoryProperties();
			// One connection, so every send after the freeze gets the frozen one.
			poolProperties.setMaxConnections(1);
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(), poolProperties, artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeUnitTest.propertiesService(context, pool);
			final JmsTemplate template = new JmsTemplate(pool);
			template.convertAndSend(queue, "before the freeze");

			// The broker stops answering and a send waits on it.
			proxy.freeze();
			final Future<?> heldSend = sender.submit(() -> template.convertAndSend(queue, "held by the frozen broker"));
			Thread.sleep(2000);
			Assertions.assertFalse(heldSend.isDone(), "the send must wait on the frozen broker");

			// The broker comes back at a new address, sent as the update script sends it.
			newBroker = ArtemisBrokerAddressChangeUnitTest.startBroker(newPort);
			final long changed = System.nanoTime();
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeUnitTest.BEAN_NAME, true, ArtemisBrokerAddressChangeUnitTest.HOST_PROPERTY,
					"127.0.0.1", true);
			propertiesService.setStringProperty(ArtemisBrokerAddressChangeUnitTest.BEAN_NAME, true, ArtemisBrokerAddressChangeUnitTest.PORT_PROPERTY,
					String.valueOf(newPort), true);

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
			Assertions.assertEquals("after the change", ArtemisBrokerAddressChangeUnitTest.receive(newPort, queue));
			pool.stop();
		}
		finally {
			sender.shutdownNow();
			oldBroker.stop();
			if (newBroker != null) {
				newBroker.stop();
			}
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
		final int port = ArtemisBrokerAddressChangeUnitTest.freePort();
		final EmbeddedActiveMQ broker = ArtemisBrokerAddressChangeUnitTest.startBroker(port);
		try (ActiveMQConnectionFactory artemisFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + port + "?" + ArtemisBrokerAddressChangeUnitTest.CLIENT_QUERY);
				GenericApplicationContext context = new GenericApplicationContext()) {
			final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(),
					new JmsPoolConnectionFactoryProperties(), artemisFactory);
			final PropertiesService propertiesService = ArtemisBrokerAddressChangeUnitTest.propertiesService(context, pool);
			try (Connection connection = pool.createConnection()) {
				final RemotingConnection liveConnection = ArtemisBrokerAddressChangeUnitTest.remotingConnection(connection);
				Assertions.assertNotNull(liveConnection);

				propertiesService.setStringProperty(ArtemisBrokerAddressChangeUnitTest.BEAN_NAME, true, ArtemisBrokerAddressChangeUnitTest.HOST_PROPERTY,
						"127.0.0.1", true);
				propertiesService.setStringProperty(ArtemisBrokerAddressChangeUnitTest.BEAN_NAME, true, ArtemisBrokerAddressChangeUnitTest.PORT_PROPERTY,
						String.valueOf(port), true);
				Thread.sleep(1000);

				// A failed connection would have been replaced by a reconnect.
				Assertions.assertSame(liveConnection, ArtemisBrokerAddressChangeUnitTest.remotingConnection(connection));
				new JmsTemplate(pool).convertAndSend("address-change-same", "same address");
			}
			pool.stop();
		}
		finally {
			broker.stop();
		}
	}

	/**
	 * Creates the properties service over a context holding the pooled factory.
	 *
	 * @param  context Context.
	 * @param  pool    Pooled factory.
	 * @return         The properties service.
	 */
	private static PropertiesService propertiesService(
			final GenericApplicationContext context,
			final JmsPoolConnectionFactory pool) {
		context.getBeanFactory().registerSingleton(ArtemisBrokerAddressChangeUnitTest.BEAN_NAME, pool);
		context.refresh();
		final PropertiesService propertiesService = new PropertiesService();
		propertiesService.setApplicationContext(context);
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

	/**
	 * Receives one text message straight from a broker.
	 *
	 * @param  port      Broker port.
	 * @param  queue     Queue.
	 * @return           The text, or null when none arrives.
	 * @throws Exception If it cannot receive.
	 */
	private static String receive(
			final int port,
			final String queue) throws Exception {
		try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port);
				Connection connection = factory.createConnection()) {
			connection.start();
			final Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			final MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
			final Message message = consumer.receive(5000);
			return (message == null ? null : ((TextMessage) message).getText());
		}
	}

	/**
	 * Starts an embedded broker.
	 *
	 * @param  port      Port.
	 * @return           The broker.
	 * @throws Exception If it does not start.
	 */
	private static EmbeddedActiveMQ startBroker(
			final int port) throws Exception {
		final ConfigurationImpl configuration = new ConfigurationImpl();
		configuration.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false);
		configuration.addAcceptorConfiguration("netty", "tcp://127.0.0.1:" + port);
		final EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(configuration);
		broker.start();
		return broker;
	}

	/**
	 * Gets a free port.
	 *
	 * @return           The port.
	 * @throws Exception If none is found.
	 */
	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/**
	 * TCP proxy to a broker that can freeze: its connections stay open and
	 * nothing goes through them, as with a broker process that stopped.
	 */
	private static final class FreezableProxy implements AutoCloseable {

		/**
		 * Listening socket.
		 */
		private final ServerSocket server;

		/**
		 * Broker port.
		 */
		private final int brokerPort;

		/**
		 * Open sockets.
		 */
		private final List<Socket> sockets = new CopyOnWriteArrayList<>();

		/**
		 * Whether data stops going through.
		 */
		private volatile boolean frozen;

		/**
		 * Constructor.
		 *
		 * @param  brokerPort  Broker port.
		 * @throws IOException If it cannot listen.
		 */
		private FreezableProxy(final int brokerPort) throws IOException {
			this.brokerPort = brokerPort;
			this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			Thread.ofPlatform().daemon().name("freezable-proxy").start(this::accept);
		}

		/**
		 * Gets the port to connect to.
		 *
		 * @return The port.
		 */
		private int getPort() {
			return this.server.getLocalPort();
		}

		/**
		 * Stops all data, keeping the connections open.
		 */
		private void freeze() {
			this.frozen = true;
		}

		/**
		 * Accepts connections and pipes each to the broker.
		 */
		private void accept() {
			while (!this.server.isClosed()) {
				try {
					final Socket client = this.server.accept();
					final Socket broker = new Socket(InetAddress.getLoopbackAddress(), this.brokerPort);
					this.sockets.add(client);
					this.sockets.add(broker);
					this.pipe(client, broker);
					this.pipe(broker, client);
				}
				catch (final IOException exception) {
					// Closed.
				}
			}
		}

		/**
		 * Copies data from one socket to another while not frozen.
		 *
		 * @param from Source.
		 * @param to   Destination.
		 */
		private void pipe(
				final Socket from,
				final Socket to) {
			Thread.ofPlatform().daemon().name("freezable-proxy-pipe").start(() -> {
				final byte[] buffer = new byte[8192];
				try (InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream()) {
					int read = input.read(buffer);
					while (read >= 0) {
						while (this.frozen && !from.isClosed()) {
							Thread.sleep(100);
						}
						output.write(buffer, 0, read);
						output.flush();
						read = input.read(buffer);
					}
				}
				catch (final IOException | InterruptedException exception) {
					// Closed.
				}
			});
		}

		/**
		 * Closes the proxy and its connections.
		 */
		@Override
		public void close() throws IOException {
			this.server.close();
			for (final Socket socket : this.sockets) {
				socket.close();
			}
		}

	}

}
