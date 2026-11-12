package org.coldis.library.service.tiering;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.service.tiering.model.Rehydration;
import org.coldis.library.service.tiering.model.ShardStrategy;
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

		/** Default most messages a batch's queue may hold for a step to enqueue more. */
		public static final Long DEFAULT_MAX_PENDING = 10_000L;

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
		 * Most messages the batch's queue may hold for a step to enqueue more (backfill:
		 * {@code cold-tiering/backfill}; export: {@code cold-tiering/export}): above it the step
		 * enqueues nothing and the run waits for its next step.
		 */
		private Long maxPending;

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
		 * Gets the most messages the batch's queue may hold for a step to enqueue more.
		 *
		 * @return The max pending.
		 */
		public Long getMaxPending() {
			return this.maxPending;
		}

		/**
		 * Sets the most messages the batch's queue may hold for a step to enqueue more.
		 *
		 * @param maxPending New max pending.
		 */
		public void setMaxPending(
				final Long maxPending) {
			this.maxPending = maxPending;
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
			resolved.finishWithin = Objects.requireNonNullElse(this.finishWithin, Objects.requireNonNullElse(defaults.finishWithin, Duration.ofDays(1)));
			resolved.maxPending = Objects.requireNonNullElse(this.maxPending, Objects.requireNonNullElse(defaults.maxPending, BatchProperties.DEFAULT_MAX_PENDING));
			return resolved;
		}

	}

	/**
	 * Export batch properties (partition tiering).
	 */
	public static class ExportProperties extends BatchProperties {

		/** Quiet period for partitions without a loaded signal. */
		private Duration quietPeriod;

		/** Partitions never exported (glob on the name). */
		private String excludePartitions;

		/** Shard strategy. */
		private ShardStrategy shardStrategy;

		/** Equi-depth target shards. */
		private Integer shards;

		/** Prefix digits. */
		private Integer shardDigits;

		/** Block size (compressed, bytes). */
		private Integer blockSize;

		/** Rows per data page. */
		private Integer pageSize;

		/** Keys per boundaries page. */
		private Integer keyPageSize;

		/** Upload part size. */
		private Integer partSize;

		/** One row in N read back after the shard completes. */
		private Integer verifySampleEvery;

		public Duration getQuietPeriod() {
			return this.quietPeriod;
		}

		public void setQuietPeriod(
				final Duration quietPeriod) {
			this.quietPeriod = quietPeriod;
		}

		public String getExcludePartitions() {
			return this.excludePartitions;
		}

		public void setExcludePartitions(
				final String excludePartitions) {
			this.excludePartitions = excludePartitions;
		}

		public ShardStrategy getShardStrategy() {
			return this.shardStrategy;
		}

		public void setShardStrategy(
				final ShardStrategy shardStrategy) {
			this.shardStrategy = shardStrategy;
		}

		public Integer getShards() {
			return this.shards;
		}

		public void setShards(
				final Integer shards) {
			this.shards = shards;
		}

		public Integer getShardDigits() {
			return this.shardDigits;
		}

		public void setShardDigits(
				final Integer shardDigits) {
			this.shardDigits = shardDigits;
		}

		public Integer getBlockSize() {
			return this.blockSize;
		}

		public void setBlockSize(
				final Integer blockSize) {
			this.blockSize = blockSize;
		}

		public Integer getPageSize() {
			return this.pageSize;
		}

		public void setPageSize(
				final Integer pageSize) {
			this.pageSize = pageSize;
		}

		public Integer getKeyPageSize() {
			return this.keyPageSize;
		}

		public void setKeyPageSize(
				final Integer keyPageSize) {
			this.keyPageSize = keyPageSize;
		}

		public Integer getPartSize() {
			return this.partSize;
		}

		public void setPartSize(
				final Integer partSize) {
			this.partSize = partSize;
		}

		public Integer getVerifySampleEvery() {
			return this.verifySampleEvery;
		}

		public void setVerifySampleEvery(
				final Integer verifySampleEvery) {
			this.verifySampleEvery = verifySampleEvery;
		}

		/**
		 * Resolves against defaults.
		 *
		 * @param  defaults Defaults.
		 * @return          Resolved.
		 */
		public ExportProperties over(
				final ExportProperties defaults) {
			final ExportProperties resolved = new ExportProperties();
			final BatchProperties batch = super.over(defaults);
			resolved.setEnabled(batch.getEnabled());
			resolved.setCron(this.getCron() == null ? Objects.requireNonNullElse(defaults.getCron(), "0 15 * * * *") : this.getCron());
			resolved.setChunkSize(batch.getChunkSize());
			resolved.setDelayBetweenChunks(batch.getDelayBetweenChunks());
			resolved.setFinishWithin(this.getFinishWithin() == null ? Objects.requireNonNullElse(defaults.getFinishWithin(), Duration.ofDays(2)) : this.getFinishWithin());
			resolved.setMaxPending(batch.getMaxPending());
			resolved.quietPeriod = Objects.requireNonNullElse(this.quietPeriod, Objects.requireNonNullElse(defaults.quietPeriod, Duration.ofDays(2)));
			resolved.excludePartitions = Objects.requireNonNullElse(this.excludePartitions, Objects.requireNonNullElse(defaults.excludePartitions, "*_legacy"));
			resolved.shardStrategy = Objects.requireNonNullElse(this.shardStrategy, Objects.requireNonNullElse(defaults.shardStrategy, ShardStrategy.EQUI_DEPTH));
			resolved.shards = Objects.requireNonNullElse(this.shards, Objects.requireNonNullElse(defaults.shards, 1000));
			resolved.shardDigits = Objects.requireNonNullElse(this.shardDigits, Objects.requireNonNullElse(defaults.shardDigits, 3));
			resolved.blockSize = Objects.requireNonNullElse(this.blockSize, Objects.requireNonNullElse(defaults.blockSize, 1024 * 1024));
			resolved.pageSize = Objects.requireNonNullElse(this.pageSize, Objects.requireNonNullElse(defaults.pageSize, 2000));
			resolved.keyPageSize = Objects.requireNonNullElse(this.keyPageSize, Objects.requireNonNullElse(defaults.keyPageSize, 1_000_000));
			resolved.partSize = Objects.requireNonNullElse(this.partSize, Objects.requireNonNullElse(defaults.partSize, 16 * 1024 * 1024));
			resolved.verifySampleEvery = Objects.requireNonNullElse(this.verifySampleEvery, Objects.requireNonNullElse(defaults.verifySampleEvery, 50_000));
			return resolved;
		}

	}

	/**
	 * Partition sweep properties (partition tiering): {@code chunkSize} is the heap blocks per
	 * chunk.
	 */
	public static class SweepProperties extends BatchProperties {

		/** Hold after load. */
		private Duration holdAfterLoad;

		public Duration getHoldAfterLoad() {
			return this.holdAfterLoad;
		}

		public void setHoldAfterLoad(
				final Duration holdAfterLoad) {
			this.holdAfterLoad = holdAfterLoad;
		}

		/**
		 * Resolves against defaults.
		 *
		 * @param  defaults Defaults.
		 * @return          Resolved.
		 */
		public SweepProperties over(
				final SweepProperties defaults) {
			final SweepProperties resolved = new SweepProperties();
			final BatchProperties batch = super.over(defaults);
			resolved.setEnabled(batch.getEnabled());
			resolved.setCron(this.getCron() == null ? Objects.requireNonNullElse(defaults.getCron(), "0 0 22 * * *") : this.getCron());
			resolved.setChunkSize(this.getChunkSize() == null ? Objects.requireNonNullElse(defaults.getChunkSize(), 1024L) : this.getChunkSize());
			resolved.setDelayBetweenChunks(
					this.getDelayBetweenChunks() == null ? Objects.requireNonNullElse(defaults.getDelayBetweenChunks(), Duration.ofSeconds(1)) : this.getDelayBetweenChunks());
			resolved.setFinishWithin(this.getFinishWithin() == null ? Objects.requireNonNullElse(defaults.getFinishWithin(), Duration.ofDays(3)) : this.getFinishWithin());
			resolved.setMaxPending(batch.getMaxPending());
			resolved.holdAfterLoad = Objects.requireNonNullElse(this.holdAfterLoad, Objects.requireNonNullElse(defaults.holdAfterLoad, Duration.ofDays(5)));
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

		/** Export (partition tiering). */
		private ExportProperties export = new ExportProperties();

		/** Object key prefix of the table (the global prefix when unset). */
		private String prefix;

		public String getPrefix() {
			return this.prefix;
		}

		public void setPrefix(
				final String prefix) {
			this.prefix = prefix;
		}

		/** Partition sweep (partition tiering). */
		private SweepProperties partitionSweep = new SweepProperties();

		public ExportProperties getExport() {
			return this.export;
		}

		public void setExport(
				final ExportProperties export) {
			this.export = export;
		}

		public SweepProperties getPartitionSweep() {
			return this.partitionSweep;
		}

		public void setPartitionSweep(
				final SweepProperties partitionSweep) {
			this.partitionSweep = partitionSweep;
		}

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
			resolved.export = this.export.over(defaults.export);
			resolved.prefix = this.prefix == null ? defaults.prefix : this.prefix;
			resolved.partitionSweep = this.partitionSweep.over(defaults.partitionSweep);
			return resolved;
		}

	}

	/** Storage: {@code s3}, {@code folder} or {@code memory}. */
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
		if (resolved.prefix == null) {
			resolved.prefix = this.prefix;
		}
		return resolved;
	}

}
