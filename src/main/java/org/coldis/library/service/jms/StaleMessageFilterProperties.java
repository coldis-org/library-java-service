package org.coldis.library.service.jms;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stale message filter properties.
 */
@Component
@ConfigurationProperties(
		prefix = "org.coldis.library.service.jms.stale-filter",
		ignoreUnknownFields = true
)
public class StaleMessageFilterProperties {

	/**
	 * If the stale message filter is enabled.
	 */
	private Boolean enabled;

	/**
	 * How long processing records are kept (in memory and in the shared store).
	 * The window only bounds the map and table sizes: expired records simply stop
	 * dropping older same-key messages (fail-open), so it should comfortably
	 * exceed the worst-case time a message can sit in a queue.
	 */
	private Duration window;

	/**
	 * Interval between synchronizations with the shared store (push of local
	 * processing records and pull of records from other instances).
	 */
	private Duration synchronizationInterval;

	/**
	 * Leniency margin for clock skew (between producers and consumers) and
	 * read-replica lag. Messages are only dropped when a same-key processing
	 * started at least this much after the message was posted.
	 */
	private Duration clockSkewMargin;

	/**
	 * If processing records should be shared through the database (required for
	 * cross-instance staleness detection).
	 */
	private Boolean persistenceEnabled;

	/**
	 * If the shared store table should be created automatically.
	 */
	private Boolean createSchema;

	/**
	 * Shared store table name.
	 */
	private String tableName;

	/**
	 * Initial capacity for the in-memory processing records map.
	 */
	private Integer initialCapacity;

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
	 * Gets the window.
	 *
	 * @return The window.
	 */
	public Duration getWindow() {
		this.window = (this.window == null ? Duration.ofHours(1) : this.window);
		return this.window;
	}

	/**
	 * Sets the window.
	 *
	 * @param window New window.
	 */
	public void setWindow(
			final Duration window) {
		this.window = window;
	}

	/**
	 * Gets the synchronizationInterval.
	 *
	 * @return The synchronizationInterval.
	 */
	public Duration getSynchronizationInterval() {
		this.synchronizationInterval = (this.synchronizationInterval == null ? Duration.ofSeconds(10) : this.synchronizationInterval);
		return this.synchronizationInterval;
	}

	/**
	 * Sets the synchronizationInterval.
	 *
	 * @param synchronizationInterval New synchronizationInterval.
	 */
	public void setSynchronizationInterval(
			final Duration synchronizationInterval) {
		this.synchronizationInterval = synchronizationInterval;
	}

	/**
	 * Gets the clockSkewMargin.
	 *
	 * @return The clockSkewMargin.
	 */
	public Duration getClockSkewMargin() {
		this.clockSkewMargin = (this.clockSkewMargin == null ? Duration.ofSeconds(1) : this.clockSkewMargin);
		return this.clockSkewMargin;
	}

	/**
	 * Sets the clockSkewMargin.
	 *
	 * @param clockSkewMargin New clockSkewMargin.
	 */
	public void setClockSkewMargin(
			final Duration clockSkewMargin) {
		this.clockSkewMargin = clockSkewMargin;
	}

	/**
	 * Gets the persistenceEnabled.
	 *
	 * @return The persistenceEnabled.
	 */
	public Boolean getPersistenceEnabled() {
		this.persistenceEnabled = (this.persistenceEnabled == null ? true : this.persistenceEnabled);
		return this.persistenceEnabled;
	}

	/**
	 * Sets the persistenceEnabled.
	 *
	 * @param persistenceEnabled New persistenceEnabled.
	 */
	public void setPersistenceEnabled(
			final Boolean persistenceEnabled) {
		this.persistenceEnabled = persistenceEnabled;
	}

	/**
	 * Gets the createSchema.
	 *
	 * @return The createSchema.
	 */
	public Boolean getCreateSchema() {
		this.createSchema = (this.createSchema == null ? true : this.createSchema);
		return this.createSchema;
	}

	/**
	 * Sets the createSchema.
	 *
	 * @param createSchema New createSchema.
	 */
	public void setCreateSchema(
			final Boolean createSchema) {
		this.createSchema = createSchema;
	}

	/**
	 * Gets the tableName.
	 *
	 * @return The tableName.
	 */
	public String getTableName() {
		this.tableName = (this.tableName == null ? "stale_message_filter" : this.tableName);
		return this.tableName;
	}

	/**
	 * Sets the tableName.
	 *
	 * @param tableName New tableName.
	 */
	public void setTableName(
			final String tableName) {
		this.tableName = tableName;
	}

	/**
	 * Gets the initialCapacity.
	 *
	 * @return The initialCapacity.
	 */
	public Integer getInitialCapacity() {
		this.initialCapacity = (this.initialCapacity == null ? 4096 : this.initialCapacity);
		return this.initialCapacity;
	}

	/**
	 * Sets the initialCapacity.
	 *
	 * @param initialCapacity New initialCapacity.
	 */
	public void setInitialCapacity(
			final Integer initialCapacity) {
		this.initialCapacity = initialCapacity;
	}

}
