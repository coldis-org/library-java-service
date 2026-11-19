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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reconnects the live connections of an Artemis connection factory to its
 * broker address once that address changes: it fails them, and the client
 * reconnects them to the new address.
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
 * the same locator and reconnect with them.
 * </p>
 * <p>
 * The reconnection runs apart from the update, after a short delay that each
 * further change of the same factory restarts, so a host and a port sent one
 * after the other reconnect the connections once, to both. It runs only while
 * the address is the one the last local update left, so an update from another
 * host in the meantime cancels it. A session factory that has no connection (it
 * is reconnecting) is left alone: it reads the new address on its next
 * attempt.
 * </p>
 * <p>
 * When the service has a meter registry, each scheduled reconnection is counted
 * ({@link #RECONNECTIONS}) by bean and outcome: reconnected, skipped (the
 * address changed again) or failed.
 * </p>
 * <p>
 * The live session factories come from the Artemis client's own server
 * locator ({@code ServerLocatorImpl.getFactories()}), which is not public API
 * (checked against the 2.44 client). A client without it logs a warning or an
 * error at each reconnection, and its connections reconnect only once it finds
 * the broker gone.
 * </p>
 */
@Component
public class ArtemisReconnectOnAddressChange implements DisposableBean {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ArtemisReconnectOnAddressChange.class);

	/**
	 * Property prefix.
	 */
	public static final String PROPERTY_PREFIX = "org.coldis.library.service.jms.reconnect-on-address-change";

	/**
	 * Counter: scheduled reconnections, by bean and outcome.
	 */
	public static final String RECONNECTIONS = "jms.reconnect.on.address.change";

	/**
	 * Whether the live connections reconnect.
	 */
	private final Boolean isEnabled;

	/**
	 * Delay between the last change of a factory and the reconnection.
	 */
	private final Duration delay;

	/**
	 * Runs the reconnections, apart from the updates.
	 */
	private final ScheduledExecutorService scheduler;

	/**
	 * Reconnection scheduled for each factory.
	 */
	private final Map<ActiveMQConnectionFactory, ScheduledFuture<?>> scheduledReconnects = new ConcurrentHashMap<>();

	/**
	 * Meter registry, or null when the service has none.
	 */
	private MeterRegistry meterRegistry;

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
	 * @param isEnabled Whether the live connections reconnect.
	 * @param delay     Delay between the last change of a factory and the
	 *                      reconnection.
	 */
	public ArtemisReconnectOnAddressChange(
			@Value("${" + ArtemisReconnectOnAddressChange.PROPERTY_PREFIX + ".enabled:true}") final Boolean isEnabled,
			@Value("${" + ArtemisReconnectOnAddressChange.PROPERTY_PREFIX + ".delay:1s}") final Duration delay) {
		if ((delay == null) || delay.isNegative()) {
			throw new IllegalArgumentException(ArtemisReconnectOnAddressChange.PROPERTY_PREFIX + ".delay must be set and not negative, not " + delay + ".");
		}
		this.isEnabled = isEnabled;
		this.delay = delay;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("artemis-reconnect-on-address-change").factory());
	}

	/**
	 * Sets the meter registry the reconnections are counted in.
	 *
	 * @param meterRegistry Meter registry.
	 */
	@Autowired(required = false)
	public void setMeterRegistry(
			final MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Counts a scheduled reconnection, when there is a registry.
	 *
	 * @param beanName Name of the bean the factory belongs to.
	 * @param outcome  Outcome: reconnected, skipped or failed.
	 */
	private void count(
			final String beanName,
			final String outcome) {
		final MeterRegistry registry = this.meterRegistry;
		if (registry != null) {
			Counter.builder(ArtemisReconnectOnAddressChange.RECONNECTIONS)
					.description("Reconnections of the live connections of an Artemis factory after a change of its broker address.").tag("bean", beanName)
					.tag("outcome", outcome).register(registry).increment();
		}
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
	 * @param  beanName          Name of the bean the factory belongs to (for the
	 *                               log).
	 * @param  connectionFactory Artemis connection factory.
	 * @return                   Whether its live connections could be read.
	 */
	private Boolean reconnectLiveConnections(
			final String beanName,
			final ActiveMQConnectionFactory connectionFactory) {
		final List<ClientSessionFactoryInternal> sessionFactories = new ArrayList<>();
		Boolean isRead = false;
		if (connectionFactory.getServerLocator() instanceof final ServerLocatorImpl serverLocator) {
			isRead = true;
			final Set<ClientSessionFactoryInternal> liveSessionFactories = serverLocator.getFactories();
			// The locator guards this set with its own monitor.
			synchronized (liveSessionFactories) {
				sessionFactories.addAll(liveSessionFactories);
			}
			ArtemisReconnectOnAddressChange.LOGGER.info("The broker address of bean " + beanName + " changed: failing the live connections of its "
					+ sessionFactories.size() + " session factories, so they reconnect to it.");
		}
		else {
			ArtemisReconnectOnAddressChange.LOGGER.warn("The broker address of bean " + beanName + " changed, but its server locator is not the Artemis client's own: "
					+ "its live connections reconnect when the client finds the broker gone.");
		}
		final ActiveMQNotConnectedException cause = new ActiveMQNotConnectedException("The broker address of bean " + beanName + " changed.");
		for (final ClientSessionFactoryInternal sessionFactory : sessionFactories) {
			Thread.ofVirtual().name("artemis-reconnect-on-address-change-" + beanName).start(() -> {
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
					ArtemisReconnectOnAddressChange.LOGGER.error("Could not reconnect a live connection of bean " + beanName + " to its new broker address.",
							exception);
				}
			});
		}
		return isRead;
	}

	/**
	 * Reconnects the live connections of a factory whose broker address changed
	 * since it was noted, after the delay, while the address stays the one this
	 * update left. An update that came from another host changes the address but
	 * reconnects nothing: its connections reconnect when the client finds the
	 * broker gone.
	 *
	 * @param beanName      Name of the bean the factory belongs to (for the log
	 *                          and the counter).
	 * @param snapshot      Address noted before the update (may be null).
	 * @param isLocalUpdate Whether the update came from the service's own host
	 *                          or container.
	 */
	public void reconnectIfChanged(
			final String beanName,
			final Snapshot snapshot,
			final Boolean isLocalUpdate) {
		if ((snapshot != null) && snapshot.isChanged()) {
			if (Boolean.TRUE.equals(this.isEnabled) && Boolean.TRUE.equals(isLocalUpdate)) {
				final Snapshot localAddress = new Snapshot(snapshot.connectionFactory);
				this.scheduledReconnects.compute(snapshot.connectionFactory, (connectionFactory, scheduledReconnect) -> {
					if (scheduledReconnect != null) {
						scheduledReconnect.cancel(false);
					}
					return this.scheduler.schedule(() -> {
						String outcome = "failed";
						try {
							if (localAddress.isChanged()) {
								outcome = "skipped";
								ArtemisReconnectOnAddressChange.LOGGER.warn("The broker address of bean " + beanName + " changed again, by an update that did not "
										+ "come from the service's own host: its live connections do not reconnect.");
							}
							else if (this.reconnectLiveConnections(beanName, connectionFactory)) {
								outcome = "reconnected";
							}
						}
						catch (final RuntimeException | LinkageError exception) {
							ArtemisReconnectOnAddressChange.LOGGER.error("Could not reconnect the live connections of bean " + beanName + " to its new broker address.",
									exception);
						}
						this.count(beanName, outcome);
					}, this.delay.toMillis(), TimeUnit.MILLISECONDS);
				});
			}
			else {
				ArtemisReconnectOnAddressChange.LOGGER.info("The broker address of bean " + beanName + " changed. Its live connections reconnect when the client "
						+ "finds the broker gone: " + (Boolean.TRUE.equals(this.isEnabled) ? "the update did not come from the service's own host."
								: "reconnecting them is off (" + ArtemisReconnectOnAddressChange.PROPERTY_PREFIX + ".enabled)."));
			}
		}
	}

	/**
	 * Whether a reconnection is scheduled or running. The connections it fails
	 * reconnect on threads of their own, after it.
	 *
	 * @return Whether a reconnection is pending.
	 */
	public Boolean isReconnectPending() {
		return this.scheduledReconnects.values().stream().anyMatch(scheduledReconnect -> !scheduledReconnect.isDone());
	}

	/**
	 * Stops the reconnections.
	 *
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	@Override
	public void destroy() {
		this.scheduler.shutdownNow();
	}

}
