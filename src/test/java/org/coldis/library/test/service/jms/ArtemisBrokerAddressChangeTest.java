package org.coldis.library.test.service.jms;

import java.io.InputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnection;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.service.jms.MeteredJmsPoolConnectionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnection;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

/**
 * Makes sure a broker address change applied at runtime reaches every pooled connection.
 *
 * In production the broker moves whenever its Nomad allocation is replaced. The service is not
 * restarted: a Consul template change script PUTs the new host and port into
 * {@code connectionFactory.serverLocator.initialConnectors[0].params} of the pooled connection
 * factory bean, and every session factory is expected to reconnect there.
 *
 * That only works when every session factory holds that same {@code TransportConfiguration}. A
 * standalone broker sends each new connection a synthetic topology notification, which the client
 * turns into a fresh {@code TransportConfiguration} copy and stores in the locator's topology
 * array. With {@code useTopologyForLoadBalancing} enabled (the Artemis default), every pooled
 * connection after the first is created from that copy, which nothing updates afterwards: those
 * connections retry the old address forever, the pool never notices (the exception listener only
 * fires once the reconnect loop ends) and the health check stays green.
 *
 * The default broker URL query disables topology based selection for that reason. The first test
 * guards the query; the second documents why it is needed, so the flag is not dropped as noise.
 */
public class ArtemisBrokerAddressChangeTest {

	/** Pooled connections opened against the first broker. */
	private static final int CONNECTIONS = 4;

	/** Client timings short enough for the test, applied after the default query so they win. */
	private static final String FAST_RECONNECT_QUERY = "reconnectAttempts=-1;retryInterval=200;retryIntervalMultiplier=1;maxRetryInterval=500;"
			+ "clientFailureCheckPeriod=500;connectionTTL=2000;callTimeout=2000;callFailoverTimeout=1000;";

	/**
	 * Default broker URL query, as services compose it from {@code service.properties}, with
	 * placeholders resolved to their defaults.
	 */
	private static String defaultQuery() throws Exception {
		final Properties properties = new Properties();
		try (InputStream input = ArtemisBrokerAddressChangeTest.class.getResourceAsStream("/service.properties")) {
			properties.load(input);
		}
		final String query = properties.getProperty("spring.artemis.broker-url-query-default");
		Assertions.assertNotNull(query, "spring.artemis.broker-url-query-default must be set in service.properties");
		return query.replaceAll("\\$\\{[^:}]+:([^}]*)\\}", "$1");
	}

	/**
	 * With the default query, every pooled connection follows the broker to its new address.
	 */
	@Test
	public void testEveryPooledConnectionFollowsBrokerAddressChange() throws Exception {
		final int completed = this.reconnectedAfterAddressChange(ArtemisBrokerAddressChangeTest.defaultQuery());
		Assertions.assertEquals(ArtemisBrokerAddressChangeTest.CONNECTIONS, completed,
				"every pooled connection must fail over to the new broker address");
	}

	/**
	 * Without {@code useTopologyForLoadBalancing=false}, only the first pooled connection follows.
	 * This is the failure mode the default query prevents; if Artemis ever stops copying the
	 * transport configuration, this test fails and the flag can be reconsidered.
	 */
	@Test
	public void testTopologySelectionLeavesPooledConnectionsOnOldAddress() throws Exception {
		final String query = ArtemisBrokerAddressChangeTest.defaultQuery().replace("useTopologyForLoadBalancing=false;", "");
		Assertions.assertFalse(query.contains("useTopologyForLoadBalancing"), "flag must be absent for this scenario");
		final int completed = this.reconnectedAfterAddressChange(query);
		Assertions.assertTrue(completed < ArtemisBrokerAddressChangeTest.CONNECTIONS,
				"topology based selection was expected to strand pooled connections, but " + completed + " failed over");
	}

	/**
	 * Opens pooled connections against a broker, moves the broker, applies the address change the
	 * way the change script does, and returns how many connections completed the failover (reported
	 * it to the pool).
	 */
	private int reconnectedAfterAddressChange(
			final String query) throws Exception {
		final int oldPort = ArtemisBrokerAddressChangeTest.freePort();
		final int newPort = ArtemisBrokerAddressChangeTest.freePort();
		EmbeddedActiveMQ broker = ArtemisBrokerAddressChangeTest.startBroker(oldPort);

		final ActiveMQConnectionFactory nativeFactory = new ActiveMQConnectionFactory(
				"tcp://127.0.0.1:" + oldPort + "?" + query + ArtemisBrokerAddressChangeTest.FAST_RECONNECT_QUERY);
		final JmsPoolConnectionFactoryProperties poolProperties = new JmsPoolConnectionFactoryProperties();
		poolProperties.setMaxConnections(ArtemisBrokerAddressChangeTest.CONNECTIONS);
		final MeteredJmsPoolConnectionFactory pool = MeteredJmsPoolConnectionFactory.create(new SimpleMeterRegistry(), poolProperties,
				nativeFactory);

		final List<Connection> connections = new ArrayList<>();
		final List<AtomicInteger> received = new ArrayList<>();
		final AtomicInteger failuresNotified = new AtomicInteger();
		try {
			for (int i = 0; i < ArtemisBrokerAddressChangeTest.CONNECTIONS; i++) {
				final Connection connection = pool.createConnection();
				connection.setExceptionListener(exception -> failuresNotified.incrementAndGet());
				final Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
				final MessageConsumer consumer = session.createConsumer(session.createQueue("address-change-" + i));
				final AtomicInteger counter = new AtomicInteger();
				consumer.setMessageListener(message -> counter.incrementAndGet());
				connection.start();
				connections.add(connection);
				received.add(counter);
				// Lets this connection's topology notification arrive before the next one is created.
				Thread.sleep(200);
			}
			Assertions.assertEquals(ArtemisBrokerAddressChangeTest.CONNECTIONS, this.distinctSessionFactories(connections),
					"the pool must have opened one native connection per pooled connection");
			Assertions.assertEquals(ArtemisBrokerAddressChangeTest.CONNECTIONS, this.connectedTo(connections, oldPort),
					"every pooled connection must start connected to the first broker");

			// Sanity: consumers receive through the first broker.
			this.sendOneToEach(oldPort, query);
			Thread.sleep(2000);
			int before = 0;
			for (final AtomicInteger counter : received) {
				before += (counter.get() > 0 ? 1 : 0);
			}
			Assertions.assertEquals(ArtemisBrokerAddressChangeTest.CONNECTIONS, before, "every consumer must receive through the first broker");

			// Moves the broker.
			broker.stop();
			Thread.sleep(3000);
			broker = ArtemisBrokerAddressChangeTest.startBroker(newPort);

			// Applies the address change exactly as java_spring_properties_artemis does.
			ReflectionHelper.setAttribute(pool, true, "connectionFactory.serverLocator.initialConnectors.0.params.host", "127.0.0.1");
			ReflectionHelper.setAttribute(pool, true, "connectionFactory.serverLocator.initialConnectors.0.params.port", String.valueOf(newPort));

			// Waits for the failovers to settle. A successful failover is reported to the pool, which
			// then discards the connection; the application (the listener container, in a service)
			// recreates its consumers from the pool. A stranded connection never reports anything,
			// which is what leaves a service deaf with a green health check.
			final long deadline = System.currentTimeMillis() + 15000;
			int previous = -1;
			long lastChange = System.currentTimeMillis();
			while ((System.currentTimeMillis() < deadline) && (failuresNotified.get() < ArtemisBrokerAddressChangeTest.CONNECTIONS)) {
				final int current = failuresNotified.get();
				if (current != previous) {
					previous = current;
					lastChange = System.currentTimeMillis();
				}
				else if ((System.currentTimeMillis() - lastChange) > 5000) {
					break;
				}
				Thread.sleep(500);
			}
			final int notified = failuresNotified.get();

			// A fresh pooled connection must reach the new broker. The probe is bounded because a pool
			// holding stranded connections hands one out, and creating a session on it blocks on the
			// failover lock for as long as the reconnect loop runs: the thread hang seen in production.
			final boolean usable = this.freshPooledConnectionWorks(pool, 5000);
			Assertions.assertEquals(notified == ArtemisBrokerAddressChangeTest.CONNECTIONS, usable,
					"the pool must be usable exactly when every connection failed over (usable=" + usable + ")");

			return notified;
		}
		finally {
			// Closing the locator first stops the reconnect loops of stranded connections, which would
			// otherwise block every close below until their call timeouts expire.
			nativeFactory.close();
			for (final Connection connection : connections) {
				try {
					connection.close();
				}
				catch (final Exception exception) {
					// Stranded connections cannot close cleanly.
				}
			}
			pool.stop();
			broker.stop();
		}
	}

	/**
	 * Whether a pooled connection obtained now can send and receive, within the given time. The
	 * probe runs on a daemon thread because it can block indefinitely on a stranded connection.
	 */
	private boolean freshPooledConnectionWorks(
			final MeteredJmsPoolConnectionFactory pool,
			final long timeoutMillis) throws Exception {
		final AtomicBoolean worked = new AtomicBoolean();
		final Thread probe = new Thread(() -> {
			try (Connection fresh = pool.createConnection()) {
				final Session session = fresh.createSession(false, Session.AUTO_ACKNOWLEDGE);
				final MessageConsumer consumer = session.createConsumer(session.createQueue("address-change-fresh"));
				fresh.start();
				final MessageProducer producer = session.createProducer(session.createQueue("address-change-fresh"));
				producer.send(session.createTextMessage("after move"));
				worked.set(consumer.receive(timeoutMillis) != null);
			}
			catch (final Exception exception) {
				// Not usable.
			}
		}, "fresh-pooled-connection-probe");
		probe.setDaemon(true);
		probe.start();
		probe.join(timeoutMillis * 2);
		return worked.get();
	}

	/** Sends one message to each consumer queue through the broker on the given port. */
	private void sendOneToEach(
			final int port,
			final String query) throws Exception {
		try (ActiveMQConnectionFactory producerFactory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port + "?" + query);
				Connection producerConnection = producerFactory.createConnection()) {
			final Session session = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			for (int i = 0; i < ArtemisBrokerAddressChangeTest.CONNECTIONS; i++) {
				final MessageProducer producer = session.createProducer(session.createQueue("address-change-" + i));
				producer.send(session.createTextMessage("probe"));
			}
		}
	}

	/** Native session factory behind a pooled connection, or null once the pool invalidated it. */
	private ClientSessionFactoryInternal sessionFactory(
			final Connection connection) {
		try {
			final ActiveMQConnection nativeConnection = (ActiveMQConnection) ((JmsPoolConnection) connection).getConnection();
			return (nativeConnection == null ? null : (ClientSessionFactoryInternal) nativeConnection.getSessionFactory());
		}
		catch (final JMSException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** Number of distinct native session factories behind the pooled connections. */
	private int distinctSessionFactories(
			final List<Connection> connections) {
		final List<Object> factories = new ArrayList<>();
		for (final Connection connection : connections) {
			final Object factory = this.sessionFactory(connection);
			if (!factories.contains(factory)) {
				factories.add(factory);
			}
		}
		return factories.size();
	}

	/** Number of pooled connections currently connected to the given port. */
	private int connectedTo(
			final List<Connection> connections,
			final int port) {
		int count = 0;
		for (final Connection connection : connections) {
			final ClientSessionFactoryInternal factory = this.sessionFactory(connection);
			final RemotingConnection remoting = (factory == null ? null : factory.getConnection());
			if ((remoting != null) && (remoting.getTransportConnection() != null)
					&& String.valueOf(remoting.getTransportConnection().getRemoteAddress()).endsWith(":" + port)) {
				count++;
			}
		}
		return count;
	}

	/** Starts a standalone, non persistent broker on the given port. */
	private static EmbeddedActiveMQ startBroker(
			final int port) throws Exception {
		final ConfigurationImpl configuration = new ConfigurationImpl();
		configuration.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false);
		configuration.addAcceptorConfiguration("netty", "tcp://127.0.0.1:" + port);
		final EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(configuration);
		broker.start();
		return broker;
	}

	/** A free local TCP port. */
	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
