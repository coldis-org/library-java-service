package org.coldis.library.service.jms;

import java.util.ArrayList;
import java.util.List;

/**
 * Reserved permits of a JMS listener bulkhead group: permits only the listeners
 * of the given destinations or container factories may use. Reserved permits
 * are strict: an idle reserved permit is not lent to other listeners.
 */
public class JmsListenerBulkheadReservationProperties {

	/**
	 * Reserved permits (at least 1).
	 */
	private Integer permits;

	/**
	 * Listener destination names (as in {@code @JmsListener(destination)},
	 * resolved). A destination match wins over a factory match.
	 */
	private List<String> destinations;

	/**
	 * Listener container factory bean names.
	 */
	private List<String> factories;

	/**
	 * Gets the permits.
	 *
	 * @return The permits.
	 */
	public Integer getPermits() {
		return this.permits;
	}

	/**
	 * Sets the permits.
	 *
	 * @param permits New permits.
	 */
	public void setPermits(
			final Integer permits) {
		this.permits = permits;
	}

	/**
	 * Gets the destinations.
	 *
	 * @return The destinations.
	 */
	public List<String> getDestinations() {
		this.destinations = (this.destinations == null ? new ArrayList<>() : this.destinations);
		return this.destinations;
	}

	/**
	 * Sets the destinations.
	 *
	 * @param destinations New destinations.
	 */
	public void setDestinations(
			final List<String> destinations) {
		this.destinations = destinations;
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

}
