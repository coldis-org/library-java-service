package org.coldis.library.service.jms;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JMS listener bulkhead properties (see {@code doc/jms-listener-bulkhead.md}).
 * The top-level settings configure the group named {@value #DEFAULT_GROUP};
 * other groups are configured under {@code groups.<group>}. Everything is off
 * unless {@code enabled} is set.
 */
@Component
@ConfigurationProperties(
		prefix = "org.coldis.library.service.jms.listener.bulkhead",
		ignoreUnknownFields = true
)
public class JmsListenerBulkheadProperties extends JmsListenerBulkheadGroupProperties {

	/**
	 * Name of the group configured by the top-level settings.
	 */
	public static final String DEFAULT_GROUP = "default";

	/**
	 * Property prefix.
	 */
	public static final String PREFIX = "org.coldis.library.service.jms.listener.bulkhead";

	/**
	 * Master switch: when false, no group is limited.
	 */
	private Boolean enabled;

	/**
	 * Other groups, by name.
	 */
	private Map<String, JmsListenerBulkheadGroupProperties> groups;

	/**
	 * Gets the enabled.
	 *
	 * @return The enabled.
	 */
	public Boolean getEnabled() {
		this.enabled = (this.enabled == null ? false : this.enabled);
		return this.enabled;
	}

	/**
	 * Sets the enabled.
	 *
	 * @param enabled New enabled.
	 */
	public void setEnabled(
			final Boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Gets the groups.
	 *
	 * @return The groups.
	 */
	public Map<String, JmsListenerBulkheadGroupProperties> getGroups() {
		this.groups = (this.groups == null ? new LinkedHashMap<>() : this.groups);
		return this.groups;
	}

	/**
	 * Sets the groups.
	 *
	 * @param groups New groups.
	 */
	public void setGroups(
			final Map<String, JmsListenerBulkheadGroupProperties> groups) {
		this.groups = groups;
	}

}
