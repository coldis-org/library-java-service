package org.coldis.library.service.jms;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves the live connections of an Artemis connection factory to its broker
 * address once that address changes.
 *
 * <p>
 * A connection reads the address from the locator's initial connectors only
 * when it connects or reconnects. A broker that goes away is noticed and its
 * connections reconnect to the new address, but a broker that stops answering
 * with its connections still open leaves every call on them waiting for the
 * call timeout, again and again: pooled-jms keeps handing those connections
 * out, and creates new ones one at a time under a lock, so the threads that
 * send pile up behind it. Failing the connections releases the waiting calls
 * at once and makes the client reconnect them, to the new address, the way it
 * does when it notices a lost broker by itself. Listener connections come from
 * the same locator and move with them.
 * </p>
 */
public final class ArtemisBrokerAddressChange {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ArtemisBrokerAddressChange.class);

	/**
	 * No instances.
	 */
	private ArtemisBrokerAddressChange() {
	}

	/**
	 * Gets the Artemis connection factory of a bean: the bean itself, or the one
	 * a pooled factory wraps.
	 *
	 * @param  bean Bean.
	 * @return      The Artemis connection factory, or null when the bean has none.
	 */
	public static ActiveMQConnectionFactory getConnectionFactory(
			final Object bean) {
		final Object connectionFactory = (bean instanceof final JmsPoolConnectionFactory pooledFactory ? pooledFactory.getConnectionFactory() : bean);
		return (connectionFactory instanceof final ActiveMQConnectionFactory artemisFactory ? artemisFactory : null);
	}

	/**
	 * Gets the broker address of a connection factory: the parameters of its
	 * initial connectors, in a form that compares equal while they are the same
	 * (a port given as text equals the same port as a number).
	 *
	 * @param  connectionFactory Artemis connection factory (may be null).
	 * @return                   The broker address, or null without a factory.
	 */
	public static String getAddress(
			final ActiveMQConnectionFactory connectionFactory) {
		String address = null;
		if (connectionFactory != null) {
			final TransportConfiguration[] connectors = connectionFactory.getServerLocator().getStaticTransportConfigurations();
			final List<String> connectorsParameters = new ArrayList<>();
			if (connectors != null) {
				for (final TransportConfiguration connector : connectors) {
					connectorsParameters.add(connector == null ? null : Objects.toString(new TreeMap<>(connector.getParams())));
				}
			}
			address = connectorsParameters.toString();
		}
		return address;
	}

	/**
	 * Fails every live connection of a connection factory, each in a thread of
	 * its own, since the client reconnects in the thread that fails the
	 * connection and keeps trying while the new address does not answer.
	 *
	 * @param  beanName          Name of the bean the factory belongs to (for the
	 *                               log).
	 * @param  connectionFactory Artemis connection factory.
	 * @return                   How many connections were failed.
	 */
	public static Integer failConnections(
			final String beanName,
			final ActiveMQConnectionFactory connectionFactory) {
		final List<ClientSessionFactoryInternal> sessionFactories = new ArrayList<>();
		if (connectionFactory.getServerLocator() instanceof final ServerLocatorImpl serverLocator) {
			final Set<ClientSessionFactoryInternal> liveSessionFactories = serverLocator.getFactories();
			// The locator guards this set with its own monitor.
			synchronized (liveSessionFactories) {
				sessionFactories.addAll(liveSessionFactories);
			}
		}
		final ActiveMQNotConnectedException cause = new ActiveMQNotConnectedException("The broker address of bean " + beanName + " changed.");
		Integer failedConnections = 0;
		for (final ClientSessionFactoryInternal sessionFactory : sessionFactories) {
			RemotingConnection connection = null;
			try {
				connection = sessionFactory.getConnection();
			}
			// A factory closed in the meantime has nothing to fail.
			catch (final IllegalStateException exception) {
				connection = null;
			}
			if (connection != null) {
				final RemotingConnection failedConnection = connection;
				Thread.ofVirtual().name("artemis-address-change-" + beanName).start(() -> failedConnection.fail(cause));
				failedConnections++;
			}
		}
		ArtemisBrokerAddressChange.LOGGER.info("The broker address of bean " + beanName + " changed: failing its " + failedConnections
				+ " live connections, so they reconnect to it.");
		return failedConnections;
	}

}
