package org.coldis.library.service.jms;

import java.util.ArrayList;
import java.util.List;
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

	/** Thread attributes. */
	private List<String> threadAttributes;

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
	 * Gets the threadAttributes.
	 *
	 * @return The threadAttributes.
	 */
	public List<String> getThreadAttributes() {
		this.threadAttributes = (this.threadAttributes == null ? new ArrayList<>() : this.threadAttributes);
		return this.threadAttributes;
	}

	/**
	 * Sets the threadAttributes.
	 *
	 * @param threadAttributes New threadAttributes.
	 */
	public void setThreadAttributes(
			final List<String> threadAttributes) {
		this.threadAttributes = threadAttributes;
	}

	/**
	 * Gets the maximumAsyncHops.
	 *
	 * @return The maximumAsyncHops.
	 */
	public Long getMaximumAsyncHops() {
		this.maximumAsyncHops = (this.maximumAsyncHops == null ? 53L : this.maximumAsyncHops);
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
