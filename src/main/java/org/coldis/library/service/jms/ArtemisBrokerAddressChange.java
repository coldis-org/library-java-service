package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * <p>
 * The move runs apart from the update, after a short delay that each further
 * change of the same factory restarts, so a host and a port sent one after the
 * other move the connections once, to both. A session factory that has no
 * connection (it is reconnecting) is left alone: it reads the new address on
 * its next attempt.
 * </p>
 * <p>
 * The live session factories come from the Artemis client's own server
 * locator ({@code ServerLocatorImpl.getFactories()}), which is not public API
 * (checked against the 2.44 client). A client without it logs a warning or an
 * error at each move, and its connections move only once it finds the broker
 * gone.
 * </p>
 */
@Component
public class ArtemisBrokerAddressChange implements DisposableBean {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ArtemisBrokerAddressChange.class);

	/**
	 * Property prefix.
	 */
	public static final String PROPERTY_PREFIX = "org.coldis.library.service.jms.broker-address-change";

	/**
	 * Whether the live connections move.
	 */
	private final Boolean isEnabled;

	/**
	 * Delay between the last change of a factory and the move.
	 */
	private final Duration delay;

	/**
	 * Runs the moves, apart from the updates.
	 */
	private final ScheduledExecutorService scheduler;

	/**
	 * Move scheduled for each factory.
	 */
	private final Map<ActiveMQConnectionFactory, ScheduledFuture<?>> scheduledMoves = new ConcurrentHashMap<>();

	/**
	 * Broker address of an Artemis connection factory, noted before an update.
	 * It only tells whether the address changed since; the address itself (whose
	 * parameters may carry credentials) stays inside.
	 */
	public static final class Snapshot {

		/**
		 * Artemis connection factory.
		 */
		private final ActiveMQConnectionFactory connectionFactory;

		/**
		 * Address when noted.
		 */
		private final List<String> address;

		/**
		 * Constructor.
		 *
		 * @param connectionFactory Artemis connection factory.
		 */
		private Snapshot(final ActiveMQConnectionFactory connectionFactory) {
			this.connectionFactory = connectionFactory;
			this.address = Snapshot.getAddress(connectionFactory);
		}

		/**
		 * Gets the broker address of a connection factory: the parameters of its
		 * initial connectors, in a form that compares equal while they are the
		 * same (a port given as text equals the same port as a number).
		 *
		 * @param  connectionFactory Artemis connection factory.
		 * @return                   The broker address.
		 */
		private static List<String> getAddress(
				final ActiveMQConnectionFactory connectionFactory) {
			final TransportConfiguration[] connectors = connectionFactory.getServerLocator().getStaticTransportConfigurations();
			return (connectors == null) ? List.of()
					: Arrays.stream(connectors).map(connector -> (connector == null) ? null : Objects.toString(new TreeMap<>(connector.getParams()))).toList();
		}

		/**
		 * Whether the broker address changed since it was noted.
		 *
		 * @return Whether it changed.
		 */
		public Boolean isChanged() {
			return !Objects.equals(this.address, Snapshot.getAddress(this.connectionFactory));
		}

	}

	/**
	 * Constructor.
	 *
	 * @param isEnabled Whether the live connections move.
	 * @param delay     Delay between the last change of a factory and the move.
	 */
	public ArtemisBrokerAddressChange(
			@Value("${" + ArtemisBrokerAddressChange.PROPERTY_PREFIX + ".enabled:true}") final Boolean isEnabled,
			@Value("${" + ArtemisBrokerAddressChange.PROPERTY_PREFIX + ".delay:1s}") final Duration delay) {
		this.isEnabled = isEnabled;
		this.delay = delay;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("artemis-broker-address-change").factory());
	}

	/**
	 * Notes the broker address of a bean before an update.
	 *
	 * @param  bean Bean: an Artemis connection factory, or a pooled factory over
	 *                  one.
	 * @return      The address, or null when the bean has no Artemis factory.
	 */
	public Snapshot snapshot(
			final Object bean) {
		final Object connectionFactory = (bean instanceof final JmsPoolConnectionFactory pooledFactory ? pooledFactory.getConnectionFactory() : bean);
		return (connectionFactory instanceof final ActiveMQConnectionFactory artemisFactory ? new Snapshot(artemisFactory) : null);
	}

	/**
	 * Fails every live connection of a connection factory, each in a thread of
	 * its own, since the client reconnects in the thread that fails the
	 * connection and keeps trying while the new address does not answer.
	 *
	 * @param beanName          Name of the bean the factory belongs to (for the
	 *                              log).
	 * @param connectionFactory Artemis connection factory.
	 */
	private void moveConnections(
			final String beanName,
			final ActiveMQConnectionFactory connectionFactory) {
		final List<ClientSessionFactoryInternal> sessionFactories = new ArrayList<>();
		if (connectionFactory.getServerLocator() instanceof final ServerLocatorImpl serverLocator) {
			final Set<ClientSessionFactoryInternal> liveSessionFactories = serverLocator.getFactories();
			// The locator guards this set with its own monitor.
			synchronized (liveSessionFactories) {
				sessionFactories.addAll(liveSessionFactories);
			}
			ArtemisBrokerAddressChange.LOGGER.info("The broker address of bean " + beanName + " changed: failing the live connections of its "
					+ sessionFactories.size() + " session factories, so they reconnect to it.");
		}
		else {
			ArtemisBrokerAddressChange.LOGGER.warn("The broker address of bean " + beanName + " changed, but its server locator is not the Artemis client's own: "
					+ "its live connections move when the client finds the broker gone.");
		}
		final ActiveMQNotConnectedException cause = new ActiveMQNotConnectedException("The broker address of bean " + beanName + " changed.");
		for (final ClientSessionFactoryInternal sessionFactory : sessionFactories) {
			Thread.ofVirtual().name("artemis-broker-address-change-" + beanName).start(() -> {
				try {
					// Getting the connection of a factory that has none opens one, so a factory that is
					// reconnecting is left to read the new address on its next attempt.
					final RemotingConnection connection = (sessionFactory.numConnections() > 0) ? sessionFactory.getConnection() : null;
					if (connection != null) {
						connection.fail(cause);
					}
				}
				// A factory closed in the meantime has nothing to fail.
				catch (final IllegalStateException exception) {
				}
				catch (final RuntimeException | LinkageError exception) {
					ArtemisBrokerAddressChange.LOGGER.error("Could not move a live connection of bean " + beanName + " to its new broker address.", exception);
				}
			});
		}
	}

	/**
	 * Moves the live connections of a factory whose broker address changed
	 * since it was noted, after the delay. An update that came from another host
	 * changes the address but moves nothing: its connections move when the
	 * client finds the broker gone.
	 *
	 * @param beanName      Name of the bean the factory belongs to (for the
	 *                          log).
	 * @param snapshot      Address noted before the update (may be null).
	 * @param isLocalUpdate Whether the update came from the service's own host
	 *                          or container.
	 */
	public void moveConnectionsIfChanged(
			final String beanName,
			final Snapshot snapshot,
			final Boolean isLocalUpdate) {
		if ((snapshot != null) && snapshot.isChanged()) {
			if (Boolean.TRUE.equals(this.isEnabled) && Boolean.TRUE.equals(isLocalUpdate)) {
				this.scheduledMoves.compute(snapshot.connectionFactory, (connectionFactory, scheduledMove) -> {
					if (scheduledMove != null) {
						scheduledMove.cancel(false);
					}
					return this.scheduler.schedule(() -> {
						try {
							this.moveConnections(beanName, connectionFactory);
						}
						catch (final RuntimeException | LinkageError exception) {
							ArtemisBrokerAddressChange.LOGGER.error("Could not move the live connections of bean " + beanName + " to its new broker address.",
									exception);
						}
					}, this.delay.toMillis(), TimeUnit.MILLISECONDS);
				});
			}
			else {
				ArtemisBrokerAddressChange.LOGGER.info("The broker address of bean " + beanName + " changed. Its live connections move when the client finds "
						+ "the broker gone: " + (Boolean.TRUE.equals(this.isEnabled) ? "the update did not come from the service's own host." : "moving them is off ("
								+ ArtemisBrokerAddressChange.PROPERTY_PREFIX + ".enabled)."));
			}
		}
	}

	/**
	 * Whether a move is scheduled or running. The connections a move fails
	 * reconnect on threads of their own, after it.
	 *
	 * @return Whether a move is pending.
	 */
	public Boolean isMoving() {
		return this.scheduledMoves.values().stream().anyMatch(scheduledMove -> !scheduledMove.isDone());
	}

	/**
	 * Stops the moves.
	 *
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	@Override
	public void destroy() {
		this.scheduler.shutdownNow();
	}

}
