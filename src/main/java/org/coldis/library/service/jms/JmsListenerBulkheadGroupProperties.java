package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.coldis.library.thread.CpuLimits;

/**
 * Settings of one JMS listener bulkhead group. The top-level bulkhead settings
 * ({@link JmsListenerBulkheadProperties}) configure the group named
 * {@value JmsListenerBulkheadProperties#DEFAULT_GROUP}; other groups are
 * configured under {@code groups.<group>} and inherit nothing from it.
 */
public class JmsListenerBulkheadGroupProperties {

	/**
	 * Default number of deliveries of a message during which the maximum wait
	 * applies.
	 */
	public static final Integer DEFAULT_MAX_WAIT_DELIVERIES = 3;

	/**
	 * Default handlers per CPU of a group without {@code max-in-flight} nor
	 * {@code max-in-flight-cpu-multiplier} (tune toward 2 to 4 for CPU-bound
	 * modules and 8 to 12 for database-, I/O- or broker-heavy ones). 4 since
	 * production tuning ran CPU-bound services at 4 or below.
	 */
	public static final double DEFAULT_MAX_IN_FLIGHT_CPU_MULTIPLIER = 4D;

	/**
	 * Maximum concurrent handler executions of the group. Wins over the
	 * multiplier when positive.
	 */
	private Integer maxInFlight;

	/**
	 * Maximum concurrent handler executions per CPU ({@link CpuLimits}), used
	 * when {@link #maxInFlight} is not set (default
	 * {@value #DEFAULT_MAX_IN_FLIGHT_CPU_MULTIPLIER}; 0 leaves the group not
	 * limited).
	 */
	private Double maxInFlightCpuMultiplier;

	/**
	 * Maximum wait for a permit of a consumer holding a received message. Unset
	 * or zero: no timeout (waits until a permit, the container stop or the
	 * shutdown). Does not apply at the gate before the receive, where no message
	 * waits.
	 */
	private Duration maxWait;

	/**
	 * The maximum wait applies only while the message delivery count is at most
	 * this value; later deliveries wait without timeout.
	 */
	private Integer maxWaitDeliveries;

	/**
	 * Reserved permits, by reservation name.
	 */
	private Map<String, JmsListenerBulkheadReservationProperties> reserved;

	/**
	 * Listener container factory bean names whose listeners use this group (not
	 * used by the default group).
	 */
	private List<String> factories;

	/**
	 * Gets the maxInFlight.
	 *
	 * @return The maxInFlight.
	 */
	public Integer getMaxInFlight() {
		return this.maxInFlight;
	}

	/**
	 * Sets the maxInFlight.
	 *
	 * @param maxInFlight New maxInFlight.
	 */
	public void setMaxInFlight(
			final Integer maxInFlight) {
		this.maxInFlight = maxInFlight;
	}

	/**
	 * Gets the maxInFlightCpuMultiplier.
	 *
	 * @return The maxInFlightCpuMultiplier.
	 */
	public Double getMaxInFlightCpuMultiplier() {
		return this.maxInFlightCpuMultiplier;
	}

	/**
	 * Sets the maxInFlightCpuMultiplier.
	 *
	 * @param maxInFlightCpuMultiplier New maxInFlightCpuMultiplier.
	 */
	public void setMaxInFlightCpuMultiplier(
			final Double maxInFlightCpuMultiplier) {
		this.maxInFlightCpuMultiplier = maxInFlightCpuMultiplier;
	}

	/**
	 * Gets the maxWait.
	 *
	 * @return The maxWait (null when there is no timeout).
	 */
	public Duration getMaxWait() {
		return this.maxWait;
	}

	/**
	 * Sets the maxWait.
	 *
	 * @param maxWait New maxWait.
	 */
	public void setMaxWait(
			final Duration maxWait) {
		this.maxWait = maxWait;
	}

	/**
	 * Gets the maxWaitDeliveries.
	 *
	 * @return The maxWaitDeliveries.
	 */
	public Integer getMaxWaitDeliveries() {
		this.maxWaitDeliveries = (this.maxWaitDeliveries == null ? JmsListenerBulkheadGroupProperties.DEFAULT_MAX_WAIT_DELIVERIES : this.maxWaitDeliveries);
		return this.maxWaitDeliveries;
	}

	/**
	 * Sets the maxWaitDeliveries.
	 *
	 * @param maxWaitDeliveries New maxWaitDeliveries.
	 */
	public void setMaxWaitDeliveries(
			final Integer maxWaitDeliveries) {
		this.maxWaitDeliveries = maxWaitDeliveries;
	}

	/**
	 * Gets the reserved.
	 *
	 * @return The reserved.
	 */
	public Map<String, JmsListenerBulkheadReservationProperties> getReserved() {
		this.reserved = (this.reserved == null ? new LinkedHashMap<>() : this.reserved);
		return this.reserved;
	}

	/**
	 * Sets the reserved.
	 *
	 * @param reserved New reserved.
	 */
	public void setReserved(
			final Map<String, JmsListenerBulkheadReservationProperties> reserved) {
		this.reserved = reserved;
	}

	/**
	 * Gets the factories.
	 *
	 * @return The factories.
	 */
	public List<String> getFactories() {
		this.factories = (this.factories == null ? new ArrayList<>() : this.factories);
		return this.factories;
	}

	/**
	 * Sets the factories.
	 *
	 * @param factories New factories.
	 */
	public void setFactories(
			final List<String> factories) {
		this.factories = factories;
	}

	/**
	 * Default limit per CPU of the group when neither {@code max-in-flight} nor
	 * {@code max-in-flight-cpu-multiplier} is set
	 * ({@value #DEFAULT_MAX_IN_FLIGHT_CPU_MULTIPLIER}).
	 *
	 * @return The default ratio.
	 */
	protected Double getDefaultMaxInFlightCpuMultiplier() {
		return JmsListenerBulkheadGroupProperties.DEFAULT_MAX_IN_FLIGHT_CPU_MULTIPLIER;
	}

	/**
	 * Gets the current limit of the group, read on every call (so a changed
	 * property applies live), resolved by {@link CpuLimits#resolve(Integer, Double, Double)}: the
	 * explicit {@code max-in-flight}, else {@code max-in-flight-cpu-multiplier},
	 * else the default ratio, times the CPUs.
	 *
	 * @return The current limit, or null when the group is not limited.
	 */
	public Integer getActualMaxInFlight() {
		return CpuLimits.getDefault().resolve(this.getMaxInFlight(), this.getMaxInFlightCpuMultiplier(), this.getDefaultMaxInFlightCpuMultiplier());
	}

}
