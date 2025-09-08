package org.coldis.library.service.jms;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/**
 * JMS configuration properties.
 */
@Component
@ConfigurationProperties(
		prefix = "org.coldis.library.service.jms",
		ignoreUnknownFields = true
)
public class JmsConverterProperties {

	/**
	 * Maximum async hops.
	 */
	private Long maximumAsyncHops;

	/**
	 * Maximum async hops.
	 */
	private Map<String, Boolean> maximumAsyncHopsIgnoredFor;

	/**
	 * If the original type should precede the DTO type when trying to convert
	 * message.
	 */
	private Boolean originalTypePrecedence;

	/**
	 * Gets the maximumAsyncHops.
	 *
	 * @return The maximumAsyncHops.
	 */
	public Long getMaximumAsyncHops() {
		this.maximumAsyncHops = (this.maximumAsyncHops == null ? 50L : this.maximumAsyncHops);
		return this.maximumAsyncHops;
	}

	/**
	 * Sets the maximumAsyncHops.
	 *
	 * @param maximumAsyncHops New maximumAsyncHops.
	 */
	public void setMaximumAsyncHops(
			final Long maximumAsyncHops) {
		this.maximumAsyncHops = maximumAsyncHops;
	}

	/**
	 * Gets the maximumAsyncHopsIgnoredFor.
	 *
	 * @return The maximumAsyncHopsIgnoredFor.
	 */
	public Map<String, Boolean> getMaximumAsyncHopsIgnoredFor() {
		return this.maximumAsyncHopsIgnoredFor;
	}

	/**
	 * Gets the maximumAsyncHopsIgnoredFor.
	 *
	 * @param  key The key to get the value for.
	 * @return     The maximumAsyncHopsIgnoredFor.
	 */
	public Boolean getMaximumAsyncHopsIgnoredFor(
			final String key) {
		return (this.maximumAsyncHopsIgnoredFor == null ? false : this.maximumAsyncHopsIgnoredFor.getOrDefault(key, false));
	}

	/**
	 * Sets the maximumAsyncHopsIgnoredFor.
	 *
	 * @param maximumAsyncHopsIgnoredFor New maximumAsyncHopsIgnoredFor.
	 */
	public void setMaximumAsyncHopsIgnoredFor(
			final Map<String, Boolean> maximumAsyncHopsIgnoredFor) {
		this.maximumAsyncHopsIgnoredFor = maximumAsyncHopsIgnoredFor;
	}

	/**
	 * Gets the originalTypePrecedence.
	 *
	 * @return The originalTypePrecedence.
	 */
	public Boolean getOriginalTypePrecedence() {
		this.originalTypePrecedence = (this.originalTypePrecedence == null ? true : this.originalTypePrecedence);
		return this.originalTypePrecedence;
	}

	/**
	 * Sets the originalTypePrecedence.
	 *
	 * @param originalTypePrecedence New originalTypePrecedence.
	 */
	public void setOriginalTypePrecedence(
			final Boolean originalTypePrecedence) {
		this.originalTypePrecedence = originalTypePrecedence;
	}

}
