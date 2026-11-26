package org.coldis.library.service.throttle;

import java.time.Duration;

import org.coldis.library.service.throttle.repository.SynchronizedThrottleRepository;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Synchronized throttle properties.
 */
@Component
@ConfigurationProperties(
		prefix = SynchronizedThrottleProperties.PREFIX,
		ignoreUnknownFields = true
)
public class SynchronizedThrottleProperties {

	/**
	 * Properties prefix.
	 */
	public static final String PREFIX = "org.coldis.library.service.throttle";

	/**
	 * Enabled property (the throttle bean is only created when it is true, the
	 * default).
	 */
	public static final String ENABLED_PROPERTY = SynchronizedThrottleProperties.PREFIX + ".enabled";

	/**
	 * Persistence enabled property (the repository bean is only created when it
	 * is true, the default, and a data source exists).
	 */
	public static final String PERSISTENCE_ENABLED_PROPERTY = SynchronizedThrottleProperties.PREFIX + ".persistence-enabled";

	/**
	 * If the synchronized throttle is enabled (its bean is created and its
	 * synchronization runs).
	 */
	private Boolean enabled;

	/**
	 * If runs should be shared through the database (required for the throttle
	 * to hold across instances). When false, the throttle is local to each
	 * instance.
	 */
	private Boolean persistenceEnabled;

	/**
	 * Interval between synchronizations with the shared store (push of local
	 * runs, pull of other instances' runs and deletion of expired rows). Other
	 * instances learn of a run within about this long.
	 */
	private Duration synchronizationInterval;

	/**
	 * Shared store table name.
	 */
	private String tableName;

	/**
	 * If the shared store table should be created automatically.
	 */
	private Boolean createSchema;

	/**
	 * Timeout of each shared store statement (rounded up to whole seconds, zero
	 * for none), so a slow database cannot hold the synchronization for long.
	 */
	private Duration queryTimeout;

	/**
	 * Gets the enabled.
	 *
	 * @return The enabled.
	 */
	public Boolean getEnabled() {
		this.enabled = (this.enabled == null ? true : this.enabled);
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
	 * Gets the synchronizationInterval.
	 *
	 * @return The synchronizationInterval.
	 */
	public Duration getSynchronizationInterval() {
		this.synchronizationInterval = (this.synchronizationInterval == null ? Duration.ofSeconds(5) : this.synchronizationInterval);
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
	 * Gets the tableName.
	 *
	 * @return The tableName.
	 */
	public String getTableName() {
		this.tableName = (this.tableName == null ? "synchronized_throttle" : this.tableName);
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
	 * Gets the queryTimeout.
	 *
	 * @return The queryTimeout.
	 */
	public Duration getQueryTimeout() {
		this.queryTimeout = (this.queryTimeout == null ? SynchronizedThrottleRepository.DEFAULT_QUERY_TIMEOUT : this.queryTimeout);
		return this.queryTimeout;
	}

	/**
	 * Sets the queryTimeout.
	 *
	 * @param queryTimeout New queryTimeout.
	 */
	public void setQueryTimeout(
			final Duration queryTimeout) {
		this.queryTimeout = queryTimeout;
	}

}
