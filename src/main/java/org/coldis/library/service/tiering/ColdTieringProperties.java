package org.coldis.library.service.tiering;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.service.tiering.model.Rehydration;
import org.coldis.library.service.tiering.model.TieredTable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cold tiering tunables: global defaults and per-table overrides
 * ({@code org.coldis.library.service.tiering.table.<id>.*}).
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
@ConfigurationProperties(
		prefix = ColdTieringProperties.PREFIX,
		ignoreUnknownFields = true
)
public class ColdTieringProperties {

	/** Master switch. */
	public static final String ENABLED_PROPERTY = ColdTieringPropertyNames.ENABLED;

	/** Property prefix. */
	public static final String PREFIX = ColdTieringPropertyNames.PREFIX;

	/** Disables a schedule. */
	public static final String NO_SCHEDULE = ColdTieringPropertyNames.NO_SCHEDULE;

	/**
	 * One batch's tunables.
	 */
	public static class BatchProperties {

		/** Whether the batch runs. */
		private Boolean enabled;

		/** Cron; {@link ColdTieringProperties#NO_SCHEDULE} for none. */
		private String cron;

		/** Rows per chunk. */
		private Long chunkSize;

		/** Pause between chunks. */
		private Duration delayBetweenChunks;

		/** Longest run. */
		private Duration finishWithin;

		/**
		 * Gets the enabled.
		 *
		 * @return The enabled.
		 */
		public Boolean getEnabled() {
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
		 * Gets the cron.
		 *
		 * @return The cron.
		 */
		public String getCron() {
			return this.cron;
		}

		/**
		 * Sets the cron.
		 *
		 * @param cron New cron.
		 */
		public void setCron(
				final String cron) {
			this.cron = cron;
		}

		/**
		 * Gets the chunk size.
		 *
		 * @return The chunk size.
		 */
		public Long getChunkSize() {
			return this.chunkSize;
		}

		/**
		 * Sets the chunk size.
		 *
		 * @param chunkSize New chunk size.
		 */
		public void setChunkSize(
				final Long chunkSize) {
			this.chunkSize = chunkSize;
		}

		/**
		 * Gets the delay between chunks.
		 *
		 * @return The delay between chunks.
		 */
		public Duration getDelayBetweenChunks() {
			return this.delayBetweenChunks;
		}

		/**
		 * Sets the delay between chunks.
		 *
		 * @param delayBetweenChunks New delay between chunks.
		 */
		public void setDelayBetweenChunks(
				final Duration delayBetweenChunks) {
			this.delayBetweenChunks = delayBetweenChunks;
		}

		/**
		 * Gets the finish within.
		 *
		 * @return The finish within.
		 */
		public Duration getFinishWithin() {
			return this.finishWithin;
		}

		/**
		 * Sets the finish within.
		 *
		 * @param finishWithin New finish within.
		 */
		public void setFinishWithin(
				final Duration finishWithin) {
			this.finishWithin = finishWithin;
		}

		/**
		 * This batch's values over the defaults.
		 *
		 * @param  defaults Defaults.
		 * @return          The resolved batch properties.
		 */
		public BatchProperties over(
				final BatchProperties defaults) {
			final BatchProperties resolved = new BatchProperties();
			resolved.enabled = Objects.requireNonNullElse(this.enabled, Objects.requireNonNullElse(defaults.enabled, false));
			resolved.cron = Objects.requireNonNullElse(this.cron, Objects.requireNonNullElse(defaults.cron, ColdTieringProperties.NO_SCHEDULE));
			resolved.chunkSize = Objects.requireNonNullElse(this.chunkSize, Objects.requireNonNullElse(defaults.chunkSize, 1_000L));
			resolved.delayBetweenChunks = Objects.requireNonNullElse(this.delayBetweenChunks,
					Objects.requireNonNullElse(defaults.delayBetweenChunks, Duration.ofSeconds(1)));
			resolved.finishWithin = Objects.requireNonNullElse(this.finishWithin, Objects.requireNonNullElse(defaults.finishWithin, Duration.ofMinutes(55)));
			return resolved;
		}

	}

	/**
	 * One table's tunables.
	 */
	public static class TableProperties {

		/** Write-through, touches, backfill and eviction. */
		private Boolean enabled;

		/** Hot window. */
		private Duration ttl;

		/** A read only touches when the clock is older than this. */
		private Duration touchThreshold;

		/** Extra time after expiry before eviction. */
		private Duration safetyMargin;

		/** Cold reads. */
		private Boolean coldReadEnabled;

		/** Rehydration (null: the table definition's). */
		private Rehydration rehydration;

		/** Eviction batch. */
		private BatchProperties eviction = new BatchProperties();

		/** Backfill batch. */
		private BatchProperties backfill = new BatchProperties();

		/**
		 * Gets the enabled.
		 *
		 * @return The enabled.
		 */
		public Boolean getEnabled() {
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
		 * Gets the TTL.
		 *
		 * @return The TTL.
		 */
		public Duration getTtl() {
			return this.ttl;
		}

		/**
		 * Sets the TTL.
		 *
		 * @param ttl New TTL.
		 */
		public void setTtl(
				final Duration ttl) {
			this.ttl = ttl;
		}

		/**
		 * Gets the touch threshold.
		 *
		 * @return The touch threshold.
		 */
		public Duration getTouchThreshold() {
			return this.touchThreshold;
		}

		/**
		 * Sets the touch threshold.
		 *
		 * @param touchThreshold New touch threshold.
		 */
		public void setTouchThreshold(
				final Duration touchThreshold) {
			this.touchThreshold = touchThreshold;
		}

		/**
		 * Gets the safety margin.
		 *
		 * @return The safety margin.
		 */
		public Duration getSafetyMargin() {
			return this.safetyMargin;
		}

		/**
		 * Sets the safety margin.
		 *
		 * @param safetyMargin New safety margin.
		 */
		public void setSafetyMargin(
				final Duration safetyMargin) {
			this.safetyMargin = safetyMargin;
		}

		/**
		 * Gets the cold read enabled.
		 *
		 * @return The cold read enabled.
		 */
		public Boolean getColdReadEnabled() {
			return this.coldReadEnabled;
		}

		/**
		 * Sets the cold read enabled.
		 *
		 * @param coldReadEnabled New cold read enabled.
		 */
		public void setColdReadEnabled(
				final Boolean coldReadEnabled) {
			this.coldReadEnabled = coldReadEnabled;
		}

		/**
		 * Gets the rehydration.
		 *
		 * @return The rehydration.
		 */
		public Rehydration getRehydration() {
			return this.rehydration;
		}

		/**
		 * Sets the rehydration.
		 *
		 * @param rehydration New rehydration.
		 */
		public void setRehydration(
				final Rehydration rehydration) {
			this.rehydration = rehydration;
		}

		/**
		 * Gets the eviction.
		 *
		 * @return The eviction.
		 */
		public BatchProperties getEviction() {
			return this.eviction;
		}

		/**
		 * Sets the eviction.
		 *
		 * @param eviction New eviction.
		 */
		public void setEviction(
				final BatchProperties eviction) {
			this.eviction = eviction;
		}

		/**
		 * Gets the backfill.
		 *
		 * @return The backfill.
		 */
		public BatchProperties getBackfill() {
			return this.backfill;
		}

		/**
		 * Sets the backfill.
		 *
		 * @param backfill New backfill.
		 */
		public void setBackfill(
				final BatchProperties backfill) {
			this.backfill = backfill;
		}

		/**
		 * This table's values over the defaults.
		 *
		 * @param  defaults Defaults.
		 * @return          The resolved table properties.
		 */
		public TableProperties over(
				final TableProperties defaults) {
			final TableProperties resolved = new TableProperties();
			resolved.enabled = Objects.requireNonNullElse(this.enabled, Objects.requireNonNullElse(defaults.enabled, false));
			resolved.ttl = Objects.requireNonNullElse(this.ttl, Objects.requireNonNullElse(defaults.ttl, Duration.ofDays(15)));
			resolved.touchThreshold = Objects.requireNonNullElse(this.touchThreshold, Objects.requireNonNullElse(defaults.touchThreshold, Duration.ofDays(1)));
			resolved.safetyMargin = Objects.requireNonNullElse(this.safetyMargin, Objects.requireNonNullElse(defaults.safetyMargin, Duration.ofDays(2)));
			resolved.coldReadEnabled = Objects.requireNonNullElse(this.coldReadEnabled, Objects.requireNonNullElse(defaults.coldReadEnabled, false));
			resolved.rehydration = this.rehydration == null ? defaults.rehydration : this.rehydration;
			resolved.eviction = this.eviction.over(defaults.eviction);
			resolved.backfill = this.backfill.over(defaults.backfill);
			return resolved;
		}

	}

	/** Storage: {@code s3} or {@code memory}. */
	private String storage = "s3";

	/** Whether the schema is created at startup (tests). */
	private Boolean createSchema = false;

	/** Key prefix in the bucket. */
	private String prefix = "cold-tiering/";

	/** Defaults every table inherits. */
	private TableProperties defaults = new TableProperties();

	/** Per-table overrides, by table id. */
	private Map<String, TableProperties> table = new HashMap<>();

	/**
	 * Gets the storage.
	 *
	 * @return The storage.
	 */
	public String getStorage() {
		return this.storage;
	}

	/**
	 * Sets the storage.
	 *
	 * @param storage New storage.
	 */
	public void setStorage(
			final String storage) {
		this.storage = storage;
	}

	/**
	 * Gets the create schema.
	 *
	 * @return The create schema.
	 */
	public Boolean getCreateSchema() {
		return this.createSchema;
	}

	/**
	 * Sets the create schema.
	 *
	 * @param createSchema New create schema.
	 */
	public void setCreateSchema(
			final Boolean createSchema) {
		this.createSchema = createSchema;
	}

	/**
	 * Gets the prefix.
	 *
	 * @return The prefix.
	 */
	public String getPrefix() {
		return this.prefix;
	}

	/**
	 * Sets the prefix.
	 *
	 * @param prefix New prefix.
	 */
	public void setPrefix(
			final String prefix) {
		this.prefix = prefix;
	}

	/**
	 * Gets the defaults.
	 *
	 * @return The defaults.
	 */
	public TableProperties getDefaults() {
		return this.defaults;
	}

	/**
	 * Sets the defaults.
	 *
	 * @param defaults New defaults.
	 */
	public void setDefaults(
			final TableProperties defaults) {
		this.defaults = defaults;
	}

	/**
	 * Gets the table overrides.
	 *
	 * @return The table overrides.
	 */
	public Map<String, TableProperties> getTable() {
		return this.table;
	}

	/**
	 * Sets the table overrides.
	 *
	 * @param table New table overrides.
	 */
	public void setTable(
			final Map<String, TableProperties> table) {
		this.table = table;
	}

	/**
	 * The resolved properties of a table.
	 *
	 * @param  tieredTable Table.
	 * @return             Its properties over the defaults and the definition.
	 */
	public TableProperties of(
			final TieredTable tieredTable) {
		final TableProperties resolved = this.table.getOrDefault(tieredTable.getId(), new TableProperties()).over(this.defaults);
		if (resolved.rehydration == null) {
			resolved.rehydration = tieredTable.getRehydration();
		}
		return resolved;
	}

}
