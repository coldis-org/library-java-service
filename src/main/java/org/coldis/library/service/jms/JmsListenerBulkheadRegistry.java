package org.coldis.library.service.jms;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.activemq.artemis.utils.CompositeAddress;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * JMS listener bulkheads of the process, one per limited group (see
 * {@code doc/jms-listener-bulkhead.md}). Validates the properties at startup
 * (only when the bulkhead is enabled), resolves the group and reservation of
 * each listener container, creates each group's bulkhead on first use and
 * registers its metrics.
 * <p>
 * Group of a listener container, first match: the group set on its factory
 * ({@link JmsListenerContainerFactoryBuilder#bulkheadGroup}), the group whose
 * {@code factories} contain its factory bean name, the
 * {@value JmsListenerBulkheadProperties#DEFAULT_GROUP} group. Containers from a
 * factory not built by {@link JmsListenerContainerFactoryBuilder} carry no
 * factory name: they use the default group, without reservations by factory.
 */
@Component
public class JmsListenerBulkheadRegistry {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JmsListenerBulkheadRegistry.class);

	/** Permits in use (tag: group). */
	public static final String IN_FLIGHT_METRIC = "jms.listener.bulkhead.in.flight";

	/** Listeners waiting for a permit (tag: group). */
	public static final String WAITING_METRIC = "jms.listener.bulkhead.waiting";

	/** Current limit (tag: group). */
	public static final String MAX_IN_FLIGHT_METRIC = "jms.listener.bulkhead.max.in.flight";

	/** In-flight handlers of a reservation (tags: group, reservation). */
	public static final String RESERVED_IN_FLIGHT_METRIC = "jms.listener.bulkhead.reserved.in.flight";

	/** Reserved permits (tags: group, reservation). */
	public static final String RESERVED_PERMITS_METRIC = "jms.listener.bulkhead.reserved.permits";

	/** Wait of every granted permit, 0 when immediate (tags: group, destination). */
	public static final String WAIT_METRIC = "jms.listener.bulkhead.wait";

	/**
	 * Waits that ended without a permit (tags: group, destination, reason =
	 * timeout, stopped or interrupted).
	 */
	public static final String WAIT_ABORTED_METRIC = "jms.listener.bulkhead.wait.aborted";

	/**
	 * Destination label of a container without a destination name.
	 */
	private static final String UNKNOWN_DESTINATION = "unknown";

	/**
	 * Properties.
	 */
	private final JmsListenerBulkheadProperties properties;

	/**
	 * Meter registry provider (resolved on first use).
	 */
	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	/**
	 * If the bulkhead is enabled (decided at startup).
	 */
	private final boolean enabled;

	/**
	 * Group of each factory listed in {@code groups.<group>.factories}.
	 */
	private final Map<String, String> factoryGroups;

	/**
	 * Bulkheads by group (also keeps the gauge targets reachable).
	 */
	private final Map<String, JmsListenerBulkhead> bulkheads = new ConcurrentHashMap<>();

	/**
	 * Constructor. Validates the properties when the bulkhead is enabled.
	 *
	 * @param  properties               Properties.
	 * @param  meterRegistryProvider    Meter registry provider.
	 * @throws IllegalArgumentException If the properties are invalid.
	 */
	public JmsListenerBulkheadRegistry(final JmsListenerBulkheadProperties properties, final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		this.properties = Objects.requireNonNull(properties);
		this.meterRegistryProvider = meterRegistryProvider;
		this.enabled = properties.getEnabled();
		final Map<String, String> actualFactoryGroups = new HashMap<>();
		if (this.enabled) {
			JmsListenerBulkheadRegistry.validate(properties, actualFactoryGroups);
			JmsListenerBulkheadRegistry.LOGGER.info("JMS listener bulkhead enabled: default group limit {}, groups {}.", properties.getActualMaxInFlight(),
					properties.getGroups().keySet());
		}
		this.factoryGroups = Map.copyOf(actualFactoryGroups);
	}

	/**
	 * Gets the property prefix of a group.
	 *
	 * @param  group Group.
	 * @return       The property prefix.
	 */
	private static String getGroupPrefix(
			final String group) {
		return (JmsListenerBulkheadProperties.DEFAULT_GROUP.equals(group) ? JmsListenerBulkheadProperties.PREFIX
				: JmsListenerBulkheadProperties.PREFIX + ".groups." + group);
	}

	/**
	 * Fails validation.
	 *
	 * @param  condition                If the property is valid.
	 * @param  message                  Message (naming the property).
	 * @throws IllegalArgumentException If the property is invalid.
	 */
	private static void require(
			final boolean condition,
			final String message) {
		if (!condition) {
			throw new IllegalArgumentException("Invalid JMS listener bulkhead configuration: " + message);
		}
	}

	/**
	 * Validates the reservations of a group: at least one permit each, a
	 * destination or factory in only one reservation, and at least one shared
	 * permit left.
	 *
	 * @param group           Group.
	 * @param groupProperties Group properties.
	 */
	private static void validateReservations(
			final String group,
			final JmsListenerBulkheadGroupProperties groupProperties) {
		final String groupPrefix = JmsListenerBulkheadRegistry.getGroupPrefix(group);
		final Map<String, String> destinationReservations = new HashMap<>();
		final Map<String, String> factoryReservations = new HashMap<>();
		int totalReservedPermits = 0;
		for (final Map.Entry<String, JmsListenerBulkheadReservationProperties> reservation : groupProperties.getReserved().entrySet()) {
			final String reservationPrefix = groupPrefix + ".reserved." + reservation.getKey();
			final Integer permits = reservation.getValue().getPermits();
			JmsListenerBulkheadRegistry.require((permits != null) && (permits >= 1), reservationPrefix + ".permits must be at least 1.");
			totalReservedPermits += permits;
			for (final String destination : reservation.getValue().getDestinations()) {
				final String previousReservation = destinationReservations.put(StringUtils.trim(destination), reservation.getKey());
				JmsListenerBulkheadRegistry.require(previousReservation == null, reservationPrefix + ".destinations: destination '" + destination
						+ "' is also in reservation '" + previousReservation + "'.");
			}
			for (final String factory : reservation.getValue().getFactories()) {
				final String previousReservation = factoryReservations.put(StringUtils.trim(factory), reservation.getKey());
				JmsListenerBulkheadRegistry.require(previousReservation == null,
						reservationPrefix + ".factories: factory '" + factory + "' is also in reservation '" + previousReservation + "'.");
			}
		}
		final Integer maxInFlight = groupProperties.getActualMaxInFlight();
		JmsListenerBulkheadRegistry.require((totalReservedPermits == 0) || ((maxInFlight != null) && (totalReservedPermits < maxInFlight)),
				groupPrefix + ".reserved.*.permits (" + totalReservedPermits + ") must be below " + groupPrefix + ".max-in-flight (" + maxInFlight
						+ "), leaving at least one shared permit.");
	}

	/**
	 * Validates a group.
	 *
	 * @param group           Group.
	 * @param groupProperties Group properties.
	 */
	private static void validateGroup(
			final String group,
			final JmsListenerBulkheadGroupProperties groupProperties) {
		final String groupPrefix = JmsListenerBulkheadRegistry.getGroupPrefix(group);
		JmsListenerBulkheadRegistry.require(groupProperties != null, groupPrefix + " has no settings.");
		JmsListenerBulkheadRegistry.require(groupProperties.getMaxWaitDeliveries() >= 1, groupPrefix + ".max-wait-deliveries must be at least 1.");
		JmsListenerBulkheadRegistry.require((groupProperties.getMaxWait() == null) || !groupProperties.getMaxWait().isNegative(),
				groupPrefix + ".max-wait must not be negative.");
		JmsListenerBulkheadRegistry.validateReservations(group, groupProperties);
	}

	/**
	 * Validates the properties and collects the group of each factory.
	 *
	 * @param properties    Properties.
	 * @param factoryGroups Group of each factory (filled).
	 */
	private static void validate(
			final JmsListenerBulkheadProperties properties,
			final Map<String, String> factoryGroups) {
		JmsListenerBulkheadRegistry.validateGroup(JmsListenerBulkheadProperties.DEFAULT_GROUP, properties);
		JmsListenerBulkheadRegistry.require(properties.getFactories().isEmpty(), JmsListenerBulkheadProperties.PREFIX
				+ ".factories is not used: listeners of factories in no group already use the default group.");
		for (final Map.Entry<String, JmsListenerBulkheadGroupProperties> group : properties.getGroups().entrySet()) {
			JmsListenerBulkheadRegistry.require(!JmsListenerBulkheadProperties.DEFAULT_GROUP.equals(group.getKey()),
					JmsListenerBulkheadProperties.PREFIX + ".groups." + JmsListenerBulkheadProperties.DEFAULT_GROUP
							+ " is not allowed: the top-level settings configure the default group.");
			JmsListenerBulkheadRegistry.validateGroup(group.getKey(), group.getValue());
			for (final String factory : group.getValue().getFactories()) {
				final String previousGroup = factoryGroups.put(StringUtils.trim(factory), group.getKey());
				JmsListenerBulkheadRegistry.require(previousGroup == null, JmsListenerBulkheadRegistry.getGroupPrefix(group.getKey())
						+ ".factories: factory '" + factory + "' is also in group '" + previousGroup + "'.");
			}
		}
	}

	/**
	 * Gets the reason tag value of an aborted wait.
	 *
	 * @param  reason Reason.
	 * @return        The tag value: timeout, stopped or interrupted.
	 */
	static String getReasonTag(
			final JmsListenerBulkheadException.Reason reason) {
		return switch (reason) {
			case WAIT_TIMEOUT -> "timeout";
			case CONTAINER_STOPPED -> "stopped";
			case INTERRUPTED -> "interrupted";
		};
	}

	/**
	 * If the bulkhead is enabled (decided at startup).
	 *
	 * @return If the bulkhead is enabled.
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * Gets the settings of a group.
	 *
	 * @param  group Group.
	 * @return       The settings, or null for an unknown group.
	 */
	private JmsListenerBulkheadGroupProperties getGroupProperties(
			final String group) {
		return (JmsListenerBulkheadProperties.DEFAULT_GROUP.equals(group) ? this.properties : this.properties.getGroups().get(group));
	}

	/**
	 * Gets the group of a listener container: the group set on its factory, the
	 * group listing its factory, or the default group.
	 *
	 * @param  listenerContainer Listener container.
	 * @return                   The group.
	 */
	public String resolveGroup(
			final AbstractMessageListenerContainer listenerContainer) {
		String group = JmsListenerBulkheadProperties.DEFAULT_GROUP;
		if (listenerContainer instanceof final ExtendedMessageListenerContainer extendedListenerContainer) {
			if (StringUtils.isNotBlank(extendedListenerContainer.getBulkheadGroup())) {
				group = extendedListenerContainer.getBulkheadGroup();
			}
			else if (extendedListenerContainer.getFactoryBeanName() != null) {
				group = this.factoryGroups.getOrDefault(extendedListenerContainer.getFactoryBeanName(), group);
			}
		}
		return group;
	}

	/**
	 * Registers the gauges of a new bulkhead, when a meter registry is available.
	 *
	 * @param bulkhead Bulkhead.
	 */
	private void registerGauges(
			final JmsListenerBulkhead bulkhead) {
		final MeterRegistry meterRegistry = (this.meterRegistryProvider == null ? null : this.meterRegistryProvider.getIfAvailable());
		if (meterRegistry != null) {
			Gauge.builder(JmsListenerBulkheadRegistry.IN_FLIGHT_METRIC, bulkhead, JmsListenerBulkhead::getInFlight).tag("group", bulkhead.getGroup())
					.register(meterRegistry);
			Gauge.builder(JmsListenerBulkheadRegistry.WAITING_METRIC, bulkhead, JmsListenerBulkhead::getWaiting).tag("group", bulkhead.getGroup())
					.register(meterRegistry);
			Gauge.builder(JmsListenerBulkheadRegistry.MAX_IN_FLIGHT_METRIC, bulkhead, JmsListenerBulkhead::getMaxInFlight).tag("group", bulkhead.getGroup())
					.register(meterRegistry);
			for (final String reservation : bulkhead.getReservations()) {
				Gauge.builder(JmsListenerBulkheadRegistry.RESERVED_IN_FLIGHT_METRIC, bulkhead, target -> target.getInFlight(reservation))
						.tag("group", bulkhead.getGroup()).tag("reservation", reservation).register(meterRegistry);
				Gauge.builder(JmsListenerBulkheadRegistry.RESERVED_PERMITS_METRIC, bulkhead, target -> target.getReservedPermits(reservation))
						.tag("group", bulkhead.getGroup()).tag("reservation", reservation).register(meterRegistry);
			}
		}
	}

	/**
	 * Creates the bulkhead of a limited group and registers its gauges. Its
	 * limit is read from the group settings on use, so a changed property
	 * applies live; a limit removed at runtime lifts the limit.
	 *
	 * @param  group           Group.
	 * @param  groupProperties Group settings.
	 * @return                 The bulkhead.
	 */
	private JmsListenerBulkhead createBulkhead(
			final String group,
			final JmsListenerBulkheadGroupProperties groupProperties) {
		final Map<String, Integer> reservedPermits = new LinkedHashMap<>();
		groupProperties.getReserved().forEach((
				reservation,
				reservationProperties) -> reservedPermits.put(reservation, reservationProperties.getPermits()));
		final JmsListenerBulkhead bulkhead = new JmsListenerBulkhead(group, () -> {
			final Integer maxInFlight = groupProperties.getActualMaxInFlight();
			return (maxInFlight == null ? Integer.MAX_VALUE : maxInFlight);
		}, reservedPermits);
		this.registerGauges(bulkhead);
		return bulkhead;
	}

	/**
	 * Gets the bulkhead of a group, created on first use. Whether a group is
	 * limited is decided at its first use.
	 *
	 * @param  group Group.
	 * @return       The bulkhead, or null when the bulkhead is disabled, the group
	 *               is unknown or not limited.
	 */
	public JmsListenerBulkhead getBulkhead(
			final String group) {
		JmsListenerBulkhead bulkhead = null;
		final JmsListenerBulkheadGroupProperties groupProperties = (this.enabled ? this.getGroupProperties(group) : null);
		if ((groupProperties != null) && ((groupProperties.getActualMaxInFlight() != null) || this.bulkheads.containsKey(group))) {
			bulkhead = this.bulkheads.computeIfAbsent(group, newGroup -> this.createBulkhead(newGroup, groupProperties));
		}
		return bulkhead;
	}

	/**
	 * Resolves the reservation of a listener: the reservation listing its
	 * destination (or the queue of a fully qualified destination), else the
	 * reservation listing its factory.
	 *
	 * @param  groupProperties Group settings.
	 * @param  destination     Listener destination.
	 * @param  factoryBeanName Factory bean name (may be null).
	 * @return                 The reservation, or null for unreserved.
	 */
	private static String resolveReservation(
			final JmsListenerBulkheadGroupProperties groupProperties,
			final String destination,
			final String factoryBeanName) {
		final String queueName = CompositeAddress.extractQueueName(destination);
		String destinationReservation = null;
		String factoryReservation = null;
		for (final Map.Entry<String, JmsListenerBulkheadReservationProperties> reservation : groupProperties.getReserved().entrySet()) {
			final boolean destinationMatches = reservation.getValue().getDestinations().stream().map(StringUtils::trim)
					.anyMatch(reservedDestination -> reservedDestination.equals(destination) || reservedDestination.equals(queueName));
			final boolean factoryMatches = (factoryBeanName != null)
					&& reservation.getValue().getFactories().stream().map(StringUtils::trim).anyMatch(factoryBeanName::equals);
			destinationReservation = ((destinationReservation == null) && destinationMatches ? reservation.getKey() : destinationReservation);
			factoryReservation = ((factoryReservation == null) && factoryMatches ? reservation.getKey() : factoryReservation);
		}
		return (destinationReservation == null ? factoryReservation : destinationReservation);
	}

	/**
	 * Binds a listener container to its bulkhead.
	 *
	 * @param  listenerContainer Listener container.
	 * @return                   The binding, or null when the listener is not
	 *                           limited (bulkhead disabled, group unknown or not
	 *                           limited).
	 */
	public JmsListenerBulkheadBinding bind(
			final AbstractMessageListenerContainer listenerContainer) {
		JmsListenerBulkheadBinding binding = null;
		if (this.enabled) {
			final String group = this.resolveGroup(listenerContainer);
			final JmsListenerBulkheadGroupProperties groupProperties = this.getGroupProperties(group);
			final JmsListenerBulkhead bulkhead = this.getBulkhead(group);
			final String destination = Objects.toString(listenerContainer.getDestinationName(), JmsListenerBulkheadRegistry.UNKNOWN_DESTINATION);
			if (groupProperties == null) {
				JmsListenerBulkheadRegistry.LOGGER.warn("JMS listener bulkhead group '{}' of the listener of '{}' has no settings: the listener is not limited.",
						group, destination);
			}
			else if (bulkhead != null) {
				final String factoryBeanName = (listenerContainer instanceof final ExtendedMessageListenerContainer extendedListenerContainer
						? extendedListenerContainer.getFactoryBeanName()
						: null);
				binding = new JmsListenerBulkheadBinding(bulkhead, JmsListenerBulkheadRegistry.resolveReservation(groupProperties, destination, factoryBeanName),
						destination, groupProperties, (this.meterRegistryProvider == null ? null : this.meterRegistryProvider.getIfAvailable()));
				JmsListenerBulkheadRegistry.LOGGER.debug("JMS listener of '{}' bound to bulkhead group '{}' (reservation '{}').", destination, group,
						binding.getReservation());
			}
		}
		return binding;
	}

}
