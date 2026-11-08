package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.batch.BatchAction;
import org.coldis.library.service.batch.BatchExecutor;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.service.tiering.ColdTieringProperties.BatchProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.repository.HotTierRepository;
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
 * registered table, the eviction of archived rows past the TTL and the backfill that archives
 * every row whose current content is not in the cold tier. One cron per table and batch
 * ({@code -} for none); every instance schedules, the batch service keeps one run active; each
 * chunk is its own transaction.
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

	/** Properties. */
	@Autowired
	private ColdTieringProperties properties;

	/** Hot tier. */
	@Autowired
	private HotTierRepository hotTierRepository;

	/** Tiering component (archive). */
	@Autowired
	private ColdTieringServiceComponent tieringServiceComponent;

	/** Task scheduler (present when scheduling is enabled). */
	@Autowired
	private ObjectProvider<TaskScheduler> taskScheduler;

	/** Crons scheduled, by batch key suffix. */
	private final Map<String, String> scheduled = new LinkedHashMap<>();

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
	 * Eviction GET: the next chunk while evictable rows remain; empty ends the run.
	 *
	 * @param  lastChunk Last chunk, null on the first call.
	 * @param  size      Items per call (1).
	 * @param  arguments Batch arguments (table).
	 * @return           The next chunk, or empty.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			readOnly = true
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
	 *
	 * @param  lastKey   Last key archived (cursor), null on the first call.
	 * @param  size      Keys per call.
	 * @param  arguments Batch arguments (table).
	 * @return           The next keys.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			readOnly = true
	)
	public List<TieredRowKey> findNextKeysToArchive(
			final TieredRowKey lastKey,
			final Long size,
			final Map<String, String> arguments) {
		final TieredTable table = this.registry.getTable(arguments.get(ColdTieringBatchServiceComponent.TABLE_ARGUMENT));
		return this.hotTierRepository.findNextUnarchivedKeys(table, this.registry.getColumnTypes(table), lastKey, size);
	}

	/**
	 * Backfill EXECUTE: archives one row (idempotent).
	 *
	 * @param key Row key.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public void archiveKey(
			final TieredRowKey key) {
		this.tieringServiceComponent.archive(key);
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
		final BatchProperties batchProperties = batch == TieringBatch.EVICTION ? tableProperties.getEviction() : tableProperties.getBackfill();
		final Boolean isStarted = tableProperties.getEnabled() && batchProperties.getEnabled();
		if (isStarted) {
			final Class<?> itemType = batch == TieringBatch.EVICTION ? EvictionChunk.class : TieredRowKey.class;
			final Long size = batch == TieringBatch.EVICTION ? 1L : batchProperties.getChunkSize();
			final Map<BatchAction, String> delegates = batch == TieringBatch.EVICTION ? Map.of(BatchAction.GET, "findNextEvictionChunk", BatchAction.EXECUTE, "evictChunk")
					: Map.of(BatchAction.GET, "findNextKeysToArchive", BatchAction.EXECUTE, "archiveKey");
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
		final TaskScheduler scheduler = this.taskScheduler.getIfAvailable();
		if (scheduler == null) {
			ColdTieringBatchServiceComponent.LOGGER.warn("[COLD_TIERING] no task scheduler: batches run only through run()");
		}
		else if (!ColdTieringProperties.NO_SCHEDULE.equals(cron)) {
			task = scheduler.schedule(() -> this.runScheduled(table, batch), new CronTrigger(cron));
			this.scheduled.put(batch.keySuffix(table.getId()), cron);
			ColdTieringBatchServiceComponent.LOGGER.info("[COLD_TIERING] table={} batch={} scheduled: {}", table.getId(), batch, cron);
		}
		return task;
	}

	/**
	 * The crons scheduled, by batch key suffix.
	 *
	 * @return The schedules.
	 */
	public Map<String, String> getScheduled() {
		return Map.copyOf(this.scheduled);
	}

	/**
	 * Schedules every table's batches.
	 */
	@Override
	public void afterSingletonsInstantiated() {
		for (final TieredTable table : this.registry.getTables()) {
			final TableProperties tableProperties = this.properties.of(table);
			this.schedule(table, TieringBatch.EVICTION, tableProperties.getEviction().getCron());
			this.schedule(table, TieringBatch.BACKFILL, tableProperties.getBackfill().getCron());
		}
	}

}
