package org.coldis.library.service.transaction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Transaction properties.
 */
@Component
@ConfigurationProperties(prefix = "org.coldis.library.service.transaction")
public class TransactionProperties {

	/**
	 * Shortest timeout.
	 */
	private Integer shortestTimeout;

	/**
	 * Shorter timeout.
	 */
	private Integer shorterTimeout;

	/**
	 * Short timeout.
	 */
	private Integer shortTimeout;

	/**
	 * Regular timeout.
	 */
	private Integer regularTimeout;

	/**
	 * Long timeout.
	 */
	private Integer longTimeout;

	/**
	 * Longer timeout.
	 */
	private Integer longerTimeout;

	/**
	 * Longest timeout.
	 */
	private Integer longestTimeout;

	/**
	 * Minutes timeout.
	 */
	private Integer minutesTimeout;

	/** Hour timeout. */
	private Integer hourTimeout;

	/**
	 * Hours timeout.
	 */
	private Integer hoursTimeout;

	/**
	 * Gets the shortestTimeout.
	 *
	 * @return The shortestTimeout.
	 */
	public Integer getShortestTimeout() {
		return this.shortestTimeout;
	}

	/**
	 * Sets the shortestTimeout.
	 *
	 * @param shortestTimeout New shortestTimeout.
	 */
	public void setShortestTimeout(
			final Integer shortestTimeout) {
		this.shortestTimeout = shortestTimeout;
	}

	/**
	 * Gets the shorterTimeout.
	 *
	 * @return The shorterTimeout.
	 */
	public Integer getShorterTimeout() {
		return this.shorterTimeout;
	}

	/**
	 * Sets the shorterTimeout.
	 *
	 * @param shorterTimeout New shorterTimeout.
	 */
	public void setShorterTimeout(
			final Integer shorterTimeout) {
		this.shorterTimeout = shorterTimeout;
	}

	/**
	 * Gets the shortTimeout.
	 *
	 * @return The shortTimeout.
	 */
	public Integer getShortTimeout() {
		return this.shortTimeout;
	}

	/**
	 * Sets the shortTimeout.
	 *
	 * @param shortTimeout New shortTimeout.
	 */
	public void setShortTimeout(
			final Integer shortTimeout) {
		this.shortTimeout = shortTimeout;
	}

	/**
	 * Gets the regularTimeout.
	 *
	 * @return The regularTimeout.
	 */
	public Integer getRegularTimeout() {
		return this.regularTimeout;
	}

	/**
	 * Sets the regularTimeout.
	 *
	 * @param regularTimeout New regularTimeout.
	 */
	public void setRegularTimeout(
			final Integer regularTimeout) {
		this.regularTimeout = regularTimeout;
	}

	/**
	 * Gets the longTimeout.
	 *
	 * @return The longTimeout.
	 */
	public Integer getLongTimeout() {
		return this.longTimeout;
	}

	/**
	 * Sets the longTimeout.
	 *
	 * @param longTimeout New longTimeout.
	 */
	public void setLongTimeout(
			final Integer longTimeout) {
		this.longTimeout = longTimeout;
	}

	/**
	 * Gets the longerTimeout.
	 *
	 * @return The longerTimeout.
	 */
	public Integer getLongerTimeout() {
		return this.longerTimeout;
	}

	/**
	 * Sets the longerTimeout.
	 *
	 * @param longerTimeout New longerTimeout.
	 */
	public void setLongerTimeout(
			final Integer longerTimeout) {
		this.longerTimeout = longerTimeout;
	}

	/**
	 * Gets the longestTimeout.
	 *
	 * @return The longestTimeout.
	 */
	public Integer getLongestTimeout() {
		return this.longestTimeout;
	}

	/**
	 * Sets the longestTimeout.
	 *
	 * @param longestTimeout New longestTimeout.
	 */
	public void setLongestTimeout(
			final Integer longestTimeout) {
		this.longestTimeout = longestTimeout;
	}

	/**
	 * Gets the minutesTimeout.
	 *
	 * @return The minutesTimeout.
	 */
	public Integer getMinutesTimeout() {
		return this.minutesTimeout;
	}

	/**
	 * Sets the minutesTimeout.
	 *
	 * @param minutesTimeout New minutesTimeout.
	 */
	public void setMinutesTimeout(
			final Integer minutesTimeout) {
		this.minutesTimeout = minutesTimeout;
	}

	/**
	 * Gets the hourTimeout.
	 *
	 * @return The hourTimeout.
	 */
	public Integer getHourTimeout() {
		return this.hourTimeout;
	}

	/**
	 * Sets the hourTimeout.
	 *
	 * @param hourTimeout New hourTimeout.
	 */
	public void setHourTimeout(
			final Integer hourTimeout) {
		this.hourTimeout = hourTimeout;
	}

	/**
	 * Gets the hoursTimeout.
	 *
	 * @return The hoursTimeout.
	 */
	public Integer getHoursTimeout() {
		return this.hoursTimeout;
	}

	/**
	 * Sets the hoursTimeout.
	 *
	 * @param hoursTimeout New hoursTimeout.
	 */
	public void setHoursTimeout(
			final Integer hoursTimeout) {
		this.hoursTimeout = hoursTimeout;
	}

}
