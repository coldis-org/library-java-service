package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.batch.BatchAction;
import org.coldis.library.service.batch.BatchExecutor;
import org.coldis.library.service.batch.BatchPostponedException;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.service.tiering.ColdTieringProperties.BatchProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.repository.HotTierRepository;
import org.coldis.library.service.tiering.repository.PartitionHotTierRepository;
import org.coldis.library.service.tiering.model.ArchiveUnit;
import org.coldis.library.service.tiering.model.LeafPartition;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batches of the cold tiering on the {@link BatchService}, scheduled by the library: for every
 * registered table, the eviction of archived rows past the TTL and the backfill that requests the
 * archive of every row whose current content is not in the cold tier (enqueued to the backfill
 * queue, archived by its listener one row per transaction). One cron per table and batch
 * ({@code -} for none); every instance schedules, the batch service keeps one run active (a start
 * while one is active continues it); each chunk is its own transaction.
 */
@Component(ColdTieringBatchServiceComponent.BEAN_NAME)
@ConditionalOnProperty(
		name = { ColdTieringProperties.ENABLED_PROPERTY, "org.coldis.configuration.service.batch-enabled" },
		matchIfMissing = false
)
public class ColdTieringBatchServiceComponent implements SmartInitializingSingleton {

	/** Bean name the batch service calls back. */
	public static final String BEAN_NAME = "coldTieringBatchServiceComponent";

	/**
	 * Eviction batch item: one chunk of one table.
	 *
	 * @param tableId Table id.
	 * @param number  Chunk number.
	 */
	public record EvictionChunk(String tableId, Long number) implements Serializable {

	}

	/** One partition to export. */
	public record ExportItem(String tableId, String partition) implements Serializable {

	}

	/** One sweep step of a partition. */
	public record SweepChunk(String tableId, String partition, Long number) implements Serializable {

	}

	/** Batch argument naming the table. */
	private static final String TABLE_ARGUMENT = "table";

	/** How long a finished batch record is kept. */
	private static final Duration CLEANS_WITHIN = Duration.ofDays(30);

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ColdTieringBatchServiceComponent.class);

	/** Batch service. */
	@Autowired
	private BatchService batchService;

	/** Registry. */
	@Autowired
	private TieredTableRegistry registry;

	/**
	 * Partition tiering.
	 */
	@Autowired
	private PartitionTieringServiceComponent partitionTieringServiceComponent;

	/** Properties. */
	@Autowired
	private ColdTieringProperties properties;

	/** Hot tier. */
	@Autowired
	private HotTierRepository hotTierRepository;

	/**
	 * Partition hot tier repository.
	 */
	@Autowired
	private PartitionHotTierRepository partitionHotTierRepository;

	/** Tiering component (archive). */
	@Autowired
	private ColdTieringServiceComponent tieringServiceComponent;

	/** Task scheduler (present when scheduling is enabled). */
	@Autowired
	private ObjectProvider<TaskScheduler> taskScheduler;

	/** Crons scheduled, by batch key suffix. */
	private final Map<String, String> scheduled = new LinkedHashMap<>();

	/** Scheduled tasks, by batch key suffix. */
	private final Map<String, ScheduledFuture<?>> tasks = new LinkedHashMap<>();

	/**
	 * The moment before which a table's rows are evictable.
	 *
	 * @param  tableProperties Table properties.
	 * @return                 Now minus the TTL and the safety margin.
	 */
	private static LocalDateTime evictableBefore(
			final TableProperties tableProperties) {
		return DateTimeHelper.getCurrentLocalDateTime().minus(tableProperties.getTtl()).minus(tableProperties.getSafetyMargin());
	}

	/**
	 * Whether a batch's backlog (requests pending on its queue) is above its max pending, so the
	 * step must enqueue nothing. Fails safe: a backlog that cannot be read counts as full.
	 *
	 * @param  backlog    Requests pending, empty when unreadable.
	 * @param  maxPending Max pending.
	 * @return            Whether the backlog is full.
	 */
	private static Boolean isBacklogFull(
			final Optional<Long> backlog,
			final Long maxPending) {
		return backlog.map(pending -> pending > maxPending).orElse(true);
	}

	/**
	 * Logs a step's backlog decision with the depth, never a key.
	 *
	 * @param table      Table.
	 * @param batch      Batch.
	 * @param backlog    Requests pending, empty when unreadable.
	 * @param maxPending Max pending.
	 * @param isFull     Whether the backlog is full (the step is skipped).
	 */
	private static void logBacklog(
			final TieredTable table,
			final TieringBatch batch,
			final Optional<Long> backlog,
			final Long maxPending,
			final Boolean isFull) {
		if (backlog.isEmpty()) {
			ColdTieringBatchServiceComponent.LOGGER.warn("[COLD_TIERING_{}] table={} depth unreadable: skipped (fail safe), the run waits for its next step", batch,
					table.getId());
		}
		else if (isFull) {
			ColdTieringBatchServiceComponent.LOGGER.info("[COLD_TIERING_{}] table={} depth={} maxPending={} skipped (backlog full)", batch, table.getId(), backlog.get(),
					maxPending);
		}
		else {
			ColdTieringBatchServiceComponent.LOGGER.info("[COLD_TIERING_{}] table={} depth={} maxPending={}", batch, table.getId(), backlog.get(), maxPending);
		}
	}

	/**
	 * Eviction GET: the next chunk while evictable rows remain; empty ends the run.
	 *
	 * @param  lastChunk Last chunk, null on the first call.
	 * @param  size      Items per call (1).
	 * @param  arguments Batch arguments (table).
	 * @return           The next chunk, or empty.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED
	)
	public List<EvictionChunk> findNextEvictionChunk(
			final EvictionChunk lastChunk,
			final Long size,
			final Map<String, String> arguments) {
		final TieredTable table = this.registry.getTable(arguments.get(ColdTieringBatchServiceComponent.TABLE_ARGUMENT));
		final LocalDateTime before = ColdTieringBatchServiceComponent.evictableBefore(this.properties.of(table));
		final Boolean hasEvictable = this.hotTierRepository.existsEvictable(table, before);
		ColdTieringBatchServiceComponent.LOGGER.debug("[COLD_TIERING_EVICT] table={} before={} evictable={}", table.getId(), before, hasEvictable);
		return hasEvictable ? List.of(new EvictionChunk(table.getId(), lastChunk == null ? 1L : lastChunk.number() + 1)) : List.of();
	}

	/**
	 * Eviction EXECUTE: deletes one chunk of archived rows past the TTL.
	 *
	 * @param chunk Chunk.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public void evictChunk(
			final EvictionChunk chunk) {
		final TieredTable table = this.registry.getTable(chunk.tableId());
		final TableProperties tableProperties = this.properties.of(table);
		final Integer deleted = this.hotTierRepository.evictExpired(table, ColdTieringBatchServiceComponent.evictableBefore(tableProperties),
				tableProperties.getEviction().getChunkSize());
		ColdTieringBatchServiceComponent.LOGGER.info("[COLD_TIERING_EVICT] table={} chunk={} deleted={}", table.getId(), chunk.number(), deleted);
	}

	/**
	 * Backfill GET: the next unarchived keys after the cursor, in key order; empty ends the run.
	 * While the backfill queue holds more than the table's {@code backfill.max-pending} (or its
	 * depth cannot be read) nothing is selected and the run waits for its next step with its
	 * cursor in place.
	 *
	 * @param  lastKey   Last key enqueued (cursor: a key enqueued in this run is never selected
	 *                       again), null on the first call.
	 * @param  size      Keys per call.
	 * @param  arguments Batch arguments (table).
	 * @return           The next keys.
	 * @throws BatchPostponedException While the backlog is full (the run waits).
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			readOnly = true,
			noRollbackFor = BatchPostponedException.class
	)
	public List<TieredRowKey> findNextKeysToArchive(
			final TieredRowKey lastKey,
			final Long size,
			final Map<String, String> arguments) {
		final TieredTable table = this.registry.getTable(arguments.get(ColdTieringBatchServiceComponent.TABLE_ARGUMENT));
		final Optional<Long> backlog = this.tieringServiceComponent.findBackfillBacklog();
		final Long maxPending = this.properties.of(table).getBackfill().getMaxPending();
		final Boolean isFull = ColdTieringBatchServiceComponent.isBacklogFull(backlog, maxPending);
		ColdTieringBatchServiceComponent.logBacklog(table, TieringBatch.BACKFILL, backlog, maxPending, isFull);
		if (isFull) {
			throw new BatchPostponedException("backfill backlog full or unreadable");
		}
		return this.hotTierRepository.findNextUnarchivedKeys(table, lastKey, size);
	}

	/**
	 * Backfill EXECUTE: enqueues one key to the backfill queue; its listener archives the row in its
	 * own transaction (idempotent, so a key enqueued twice is harmless).
	 *
	 * @param key Row key.
	 */
	public void enqueueKey(
			final TieredRowKey key) {
		this.tieringServiceComponent.requestArchive(key);
	}

	/**
	 * Backfill EXECUTE of runs persisted by earlier versions (their batch record names this
	 * delegate until the next start replaces it): enqueues, as {@link #enqueueKey(TieredRowKey)}.
	 *
	 * @param      key Row key.
	 * @deprecated     Use {@link #enqueueKey(TieredRowKey)}.
	 */
	@Deprecated
	public void archiveKey(
			final TieredRowKey key) {
		this.enqueueKey(key);
	}

	/**
	 * GET of the export batch: the next partition to export, one per step. While the export queue
	 * holds more than the table's {@code export.max-pending} shard requests (or its depth cannot be
	 * read) no export starts and the run waits for its next step.
	 *
	 * @param  lastItem  Last item.
	 * @param  size      Ignored.
	 * @param  arguments Arguments.
	 * @return           Item; empty when no partition is due.
	 * @throws BatchPostponedException While a partition is due and the backlog is full (the run
	 *                                     waits).
	 */
	public List<ExportItem> findNextExport(
			final ExportItem lastItem,
			final Long size,
			final Map<String, String> arguments) {
		final PartitionTieredTable table = (PartitionTieredTable) this.registry.getTable(arguments.get(ColdTieringBatchServiceComponent.TABLE_ARGUMENT));
		final List<ExportItem> items = this.partitionTieringServiceComponent.findExportCandidate(table)
				.map(partition -> List.of(new ExportItem(table.getId(), partition.name()))).orElse(List.of());
		if (!items.isEmpty()) {
			final Optional<Long> backlog = this.partitionTieringServiceComponent.findExportBacklog();
			final Long maxPending = this.properties.of(table).getExport().getMaxPending();
			final Boolean isFull = ColdTieringBatchServiceComponent.isBacklogFull(backlog, maxPending);
			ColdTieringBatchServiceComponent.logBacklog(table, TieringBatch.EXPORT, backlog, maxPending, isFull);
			if (isFull) {
				throw new BatchPostponedException("export backlog full or unreadable");
			}
		}
		return items;
	}

	/**
	 * EXECUTE of the export batch: boundaries pass and shard requests, the requests leaving after
	 * the record's commit.
	 *
	 * @param item Item.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public void startExport(
			final ExportItem item) {
		final PartitionTieredTable table = (PartitionTieredTable) this.registry.getTable(item.tableId());
		final LeafPartition partition = this.partitionHotTierRepository.leafPartitions(table).stream().filter(leaf -> leaf.name().equals(item.partition())).findFirst()
				.orElseThrow();
		this.partitionTieringServiceComponent.startExport(table, partition);
	}

	/**
	 * GET of the sweep batch: the next chunk of the partition being swept, or the first of the
	 * next partition past its hold.
	 *
	 * @param  lastChunk Last chunk.
	 * @param  size      Ignored.
	 * @param  arguments Arguments.
	 * @return           Chunk.
	 */
	public List<SweepChunk> findNextSweepChunk(
			final SweepChunk lastChunk,
			final Long size,
			final Map<String, String> arguments) {
		final PartitionTieredTable table = (PartitionTieredTable) this.registry.getTable(arguments.get(ColdTieringBatchServiceComponent.TABLE_ARGUMENT));
		return this.partitionTieringServiceComponent.findSweepCandidate(table)
				.map(archive -> List.of(new SweepChunk(table.getId(), archive.partitionName(), lastChunk == null ? 1L : lastChunk.number() + 1))).orElse(List.of());
	}

	/**
	 * EXECUTE of the sweep batch: one chunk of heap blocks.
	 *
	 * @param chunk Chunk.
	 */
	public void sweepChunk(
			final SweepChunk chunk) {
		final PartitionTieredTable table = (PartitionTieredTable) this.registry.getTable(chunk.tableId());
		this.partitionTieringServiceComponent.sweepChunk(table, this.partitionTieringServiceComponent.archiveOf(table.getId(), chunk.partition()).orElseThrow());
	}

	/**
	 * Batch properties of a table's batch.
	 *
	 * @param  tableProperties Table properties.
	 * @param  batch           Batch.
	 * @return                 Properties.
	 */
	private static BatchProperties batchPropertiesOf(
			final TableProperties tableProperties,
			final TieringBatch batch) {
		return switch (batch) {
			case EVICTION -> tableProperties.getEviction();
			case BACKFILL -> tableProperties.getBackfill();
			case EXPORT -> tableProperties.getExport();
			case PARTITION_SWEEP -> tableProperties.getPartitionSweep();
		};
	}

	/**
	 * Starts (or continues) a batch of a table.
	 *
	 * @param  table Table.
	 * @param  batch Batch.
	 * @return       Whether it was started (the table and the batch are enabled).
	 * @throws BusinessException If the batch cannot start.
	 */
	public Boolean run(
			final TieredTable table,
			final TieringBatch batch) throws BusinessException {
		final TableProperties tableProperties = this.properties.of(table);
		final BatchProperties batchProperties = ColdTieringBatchServiceComponent.batchPropertiesOf(tableProperties, batch);
		final Boolean isPartition = table.getArchiveUnit() == ArchiveUnit.PARTITION;
		final Boolean isApplicable = switch (batch) {
			case EVICTION -> true;
			case BACKFILL -> !isPartition;
			case EXPORT, PARTITION_SWEEP -> isPartition;
		};
		final Boolean isStarted = isApplicable && tableProperties.getEnabled() && batchProperties.getEnabled();
		if (isStarted) {
			final Class<?> itemType = switch (batch) {
				case EVICTION -> EvictionChunk.class;
				case BACKFILL -> TieredRowKey.class;
				case EXPORT -> ExportItem.class;
				case PARTITION_SWEEP -> SweepChunk.class;
			};
			final Long size = batch == TieringBatch.BACKFILL ? batchProperties.getChunkSize() : 1L;
			final Map<BatchAction, String> delegates = switch (batch) {
				case EVICTION -> Map.of(BatchAction.GET, "findNextEvictionChunk", BatchAction.EXECUTE, "evictChunk");
				case BACKFILL -> Map.of(BatchAction.GET, "findNextKeysToArchive", BatchAction.EXECUTE, "enqueueKey");
				case EXPORT -> Map.of(BatchAction.GET, "findNextExport", BatchAction.EXECUTE, "startExport");
				case PARTITION_SWEEP -> Map.of(BatchAction.GET, "findNextSweepChunk", BatchAction.EXECUTE, "sweepChunk");
			};
			final BatchExecutor<Object> executor = BatchExecutor.withFixedRate(itemType.getName(), batch.keySuffix(table.getId()), size,
					batchProperties.getDelayBetweenChunks(), batchProperties.getFinishWithin(), ColdTieringBatchServiceComponent.BEAN_NAME, new HashMap<>(delegates),
					new HashMap<>(Map.of(ColdTieringBatchServiceComponent.TABLE_ARGUMENT, table.getId())));
			executor.setCleansWithin(ColdTieringBatchServiceComponent.CLEANS_WITHIN);
			// Messages name the table and the counts only: never a key (business ids).
			executor.setMessagesTemplates(new HashMap<>(Map.of(BatchAction.START, "Cold tiering " + batch + " of " + table.getId() + " started.", BatchAction.FINISH,
					"Cold tiering " + batch + " of " + table.getId() + " finished in ${duration} minutes.")));
			this.batchService.start(executor, false, false);
		}
		return isStarted;
	}

	/**
	 * Starts a batch of a table by id.
	 *
	 * @param  tableId Table id.
	 * @param  batch   Batch.
	 * @return         Whether it was started.
	 * @throws BusinessException If the batch cannot start.
	 */
	public Boolean run(
			final String tableId,
			final TieringBatch batch) throws BusinessException {
		return this.run(this.registry.getTable(tableId), batch);
	}

	/**
	 * A scheduled run: errors are logged, never thrown into the scheduler.
	 *
	 * @param table Table.
	 * @param batch Batch.
	 */
	private void runScheduled(
			final TieredTable table,
			final TieringBatch batch) {
		try {
			this.run(table, batch);
		}
		catch (final Exception exception) {
			ColdTieringBatchServiceComponent.LOGGER.error("[COLD_TIERING] table={} batch={} could not start: {}", table.getId(), batch,
					exception.getLocalizedMessage());
			ColdTieringBatchServiceComponent.LOGGER.debug("[COLD_TIERING] batch could not start.", exception);
		}
	}

	/**
	 * Cancels the schedule of one batch of one table, if any.
	 *
	 * @param table Table.
	 * @param batch Batch.
	 */
	public void unschedule(
			final TieredTable table,
			final TieringBatch batch) {
		synchronized (this.tasks) {
			final ScheduledFuture<?> task = this.tasks.remove(batch.keySuffix(table.getId()));
			this.scheduled.remove(batch.keySuffix(table.getId()));
			if (task != null) {
				task.cancel(false);
			}
		}
	}

	/**
	 * Schedules one batch of one table when its cron is set.
	 *
	 * @param  table Table.
	 * @param  batch Batch.
	 * @param  cron  Cron; {@link ColdTieringProperties#NO_SCHEDULE} for none.
	 * @return       The scheduled task, or null when there is no cron or no scheduler.
	 */
	public ScheduledFuture<?> schedule(
			final TieredTable table,
			final TieringBatch batch,
			final String cron) {
		ScheduledFuture<?> task = null;
		this.unschedule(table, batch);
		final TaskScheduler scheduler = this.taskScheduler.getIfAvailable();
		if (scheduler == null) {
			ColdTieringBatchServiceComponent.LOGGER.warn("[COLD_TIERING] no task scheduler: batches run only through run()");
		}
		else if (!ColdTieringProperties.NO_SCHEDULE.equals(cron)) {
			task = scheduler.schedule(() -> this.runScheduled(table, batch), new CronTrigger(cron));
			synchronized (this.tasks) {
				this.scheduled.put(batch.keySuffix(table.getId()), cron);
				this.tasks.put(batch.keySuffix(table.getId()), task);
			}
			ColdTieringBatchServiceComponent.LOGGER.info("[COLD_TIERING] table={} batch={} scheduled: {}", table.getId(), batch, cron);
		}
		return task;
	}

	/**
	 * Cancels every schedule.
	 */
	public void cancelAll() {
		synchronized (this.tasks) {
			for (final ScheduledFuture<?> task : this.tasks.values()) {
				task.cancel(false);
			}
			this.tasks.clear();
			this.scheduled.clear();
		}
	}

	/**
	 * The crons scheduled, by batch key suffix.
	 *
	 * @return The schedules.
	 */
	public Map<String, String> getScheduled() {
		synchronized (this.tasks) {
			return Map.copyOf(this.scheduled);
		}
	}

	/**
	 * Schedules every table's batches.
	 */
	@Override
	public void afterSingletonsInstantiated() {
		for (final TieredTable table : this.registry.getTables()) {
			final TableProperties tableProperties = this.properties.of(table);
			this.schedule(table, TieringBatch.EVICTION, tableProperties.getEviction().getCron());
			if (table.getArchiveUnit() == ArchiveUnit.PARTITION) {
				this.schedule(table, TieringBatch.EXPORT, tableProperties.getExport().getCron());
				this.schedule(table, TieringBatch.PARTITION_SWEEP, tableProperties.getPartitionSweep().getCron());
			}
			else {
				this.schedule(table, TieringBatch.BACKFILL, tableProperties.getBackfill().getCron());
			}
		}
	}

}
