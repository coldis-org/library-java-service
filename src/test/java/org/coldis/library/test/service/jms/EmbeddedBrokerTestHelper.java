package org.coldis.library.test.service.jms;

import java.net.ServerSocket;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Embedded brokers for tests that run without containers.
 */
public final class EmbeddedBrokerTestHelper {

	/**
	 * Netty setting of the library's broker URL. The client shares one event
	 * loop group in the JVM, and once a Spring context of the suite has made it
	 * with this setting, a connection made without it fails ("incompatible event
	 * loop type").
	 */
	public static final String NETTY_QUERY = "useEpoll=false";

	/**
	 * Client settings of the library (service.properties), so the calls on a
	 * frozen broker wait as long as they do in a service.
	 */
	public static final String CLIENT_QUERY = EmbeddedBrokerTestHelper.NETTY_QUERY + ";useTopologyForLoadBalancing=false;callTimeout=20000;"
			+ "callFailoverTimeout=10000;reconnectAttempts=-1;retryInterval=3000;retryIntervalMultiplier=1.5;clientFailureCheckPeriod=20000;"
			+ "connectionTTL=120000;blockOnDurableSend=true";

	/**
	 * No instances.
	 */
	private EmbeddedBrokerTestHelper() {
	}

	/**
	 * Starts an embedded broker. It never blocks producers for a full disk, so
	 * the tests do not depend on the disk of the machine that runs them.
	 *
	 * @param  port      Port.
	 * @return           The broker.
	 * @throws Exception If it does not start.
	 */
	public static EmbeddedActiveMQ startBroker(
			final int port) throws Exception {
		final ConfigurationImpl configuration = new ConfigurationImpl();
		configuration.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false).setMaxDiskUsage(-1);
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
	public static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/**
	 * Sends one text message straight to a broker.
	 *
	 * @param  port      Broker port.
	 * @param  queue     Queue.
	 * @param  text      Text.
	 * @throws Exception If it cannot send.
	 */
	public static void send(
			final int port,
			final String queue,
			final String text) throws Exception {
		try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port + "?" + EmbeddedBrokerTestHelper.NETTY_QUERY);
				Connection connection = factory.createConnection()) {
			final Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			session.createProducer(session.createQueue(queue)).send(session.createTextMessage(text));
		}
	}

	/**
	 * Receives one text message straight from a broker.
	 *
	 * @param  port      Broker port.
	 * @param  queue     Queue.
	 * @return           The text, or null when none arrives.
	 * @throws Exception If it cannot receive.
	 */
	public static String receive(
			final int port,
			final String queue) throws Exception {
		try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port + "?" + EmbeddedBrokerTestHelper.NETTY_QUERY);
				Connection connection = factory.createConnection()) {
			connection.start();
			final Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
			final MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
			final Message message = consumer.receive(5000);
			return (message == null ? null : ((TextMessage) message).getText());
		}
	}

}
