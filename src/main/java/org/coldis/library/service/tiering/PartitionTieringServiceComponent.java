package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.tiering.ColdTieringProperties.ExportProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.SweepProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.model.BlockCodec;
import org.coldis.library.service.tiering.model.BlockEntry;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColdUpload;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.EquiDepthShardCutter;
import org.coldis.library.service.tiering.model.LeafPartition;
import org.coldis.library.service.tiering.model.PartitionArchive;
import org.coldis.library.service.tiering.model.PartitionArchiveState;
import org.coldis.library.service.tiering.model.PartitionManifest;
import org.coldis.library.service.tiering.model.PartitionManifest.ShardSummary;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.ShardExportRequest;
import org.coldis.library.service.tiering.model.ShardIndex;
import org.coldis.library.service.tiering.model.ShardStrategy;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.repository.PartitionArchiveRepository;
import org.coldis.library.service.tiering.repository.PartitionHotTierRepository;
import org.coldis.library.service.tiering.repository.PartitionHotTierRepository.ExportRow;
import org.coldis.library.service.tiering.repository.PartitionHotTierRepository.SweepResult;
import org.coldis.library.service.tiering.repository.PartitionTieredRepository;
import org.coldis.library.service.tiering.repository.ShardedBlockReader;
import org.coldis.library.service.tiering.repository.ShardedBlockWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * Partition tiering policy: the consumer's signals, the export (boundaries pass, shard
 * listener, manifest and verification), the sweep, and the cold lookups
 * {@link ColdTieringServiceComponent} composes into tiered reads. No transaction spans an S3
 * call: pages and record updates are short statements of their own.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class PartitionTieringServiceComponent {

	/** Export queue. */
	public static final String EXPORT_QUEUE = "cold-tiering/export";

	/** A readable partition: catalog entry and record. */
	public record ReadablePartition(LeafPartition partition, PartitionArchive archive) {

	}

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(PartitionTieringServiceComponent.class);

	/** Registry. */
	@Autowired
	private TieredTableRegistry registry;

	/** Properties. */
	@Autowired
	private ColdTieringProperties properties;

	/** Partition hot tier. */
	@Autowired
	private PartitionHotTierRepository partitionHotTierRepository;

	/** Archive records. */
	@Autowired
	private PartitionArchiveRepository archiveRepository;

	/** Cold objects. */
	@Autowired
	private PartitionTieredRepository partitionTieredRepository;

	/** Active block codec (new exports). */
	@Autowired
	private BlockCodec blockCodec;

	/** JMS. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** JMS helper. */
	@Autowired
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Partition of a table holding a moment.
	 *
	 * @param  table  Table.
	 * @param  moment Moment (partition column value).
	 * @return        Partition.
	 */
	private LeafPartition partitionOf(
			final PartitionTieredTable table,
			final LocalDateTime moment) {
		return this.partitionHotTierRepository.leafPartitions(table).stream().filter(partition -> !partition.openEnded() && partition.contains(moment)).findFirst()
				.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.partition.missing", new Object[] { table.getId() })));
	}

	/**
	 * Partition table by id.
	 *
	 * @param  tableId Table id.
	 * @return         Table.
	 */
	private PartitionTieredTable tableOf(
			final String tableId) {
		final TieredTable table = this.registry.getTable(tableId);
		if (!(table instanceof PartitionTieredTable)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.unit.invalid", new Object[] { tableId }));
		}
		return (PartitionTieredTable) table;
	}

	/**
	 * Whether a partition name matches the exclusion glob.
	 *
	 * @param  name Name.
	 * @param  glob Glob ({@code *} wildcard, comma separated).
	 * @return      Excluded.
	 */
	private static Boolean isExcluded(
			final String name,
			final String glob) {
		Boolean excluded = false;
		for (final String pattern : glob.split(",")) {
			if (!pattern.isBlank() && Pattern.compile("^" + Pattern.quote(pattern.trim()).replace("*", "\\E.*\\Q") + "$").matcher(name).matches()) {
				excluded = true;
			}
		}
		return excluded;
	}

	/**
	 * Signals a partition loaded (the consumer, when a delivery is complete). Starts the hold and
	 * makes the partition exportable; on an already exported partition it means a reload.
	 *
	 * @param tableId  Table id.
	 * @param moment   A moment inside the partition (the delivery's partition column value).
	 * @param rowCount Rows loaded, when known.
	 */
	public void partitionLoaded(
			final String tableId,
			final LocalDateTime moment,
			final Long rowCount) {
		final PartitionTieredTable table = this.tableOf(tableId);
		final LeafPartition partition = this.partitionOf(table, moment);
		this.archiveRepository.markLoaded(tableId, partition.name(), partition.rangeFrom(), partition.rangeTo(), DateTimeHelper.getCurrentLocalDateTime(), rowCount);
		PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} loaded", tableId, partition.name());
	}

	/**
	 * Marks an archived partition stale (rows are about to be rewritten).
	 *
	 * @param tableId Table id.
	 * @param moment  A moment inside the partition.
	 */
	public void markStale(
			final String tableId,
			final LocalDateTime moment) {
		final PartitionTieredTable table = this.tableOf(tableId);
		final LeafPartition partition = this.partitionOf(table, moment);
		this.archiveRepository.markState(tableId, partition.name(), PartitionArchiveState.STALE, DateTimeHelper.getCurrentLocalDateTime());
	}

	/**
	 * Archive record of a partition.
	 *
	 * @param  tableId   Table id.
	 * @param  partition Partition.
	 * @return           Record.
	 */
	public Optional<PartitionArchive> archiveOf(
			final String tableId,
			final String partition) {
		return this.archiveRepository.find(tableId, partition);
	}

	/**
	 * Readable partitions (verified or stale, with a manifest), oldest first.
	 *
	 * @param  table Table.
	 * @return       Partitions.
	 */
	public List<ReadablePartition> readablePartitions(
			final PartitionTieredTable table) {
		final Map<String, PartitionArchive> archives = new LinkedHashMap<>();
		for (final PartitionArchive archive : this.archiveRepository.findAll(table.getId())) {
			if (archive.state().isReadable() && (archive.manifestKey() != null)) {
				archives.put(archive.partitionName(), archive);
			}
		}
		final List<ReadablePartition> readable = new ArrayList<>();
		for (final LeafPartition partition : this.partitionHotTierRepository.leafPartitions(table)) {
			if (archives.containsKey(partition.name())) {
				readable.add(new ReadablePartition(partition, archives.get(partition.name())));
			}
		}
		readable.sort(Comparator.comparing(entry -> entry.partition().from(), Comparator.nullsFirst(Comparator.naturalOrder())));
		return readable;
	}

	/**
	 * Cold rows of a partition whose key starts with the given values (shard key first).
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  prefix    Formatted key values, by column (a prefix of the key columns).
	 * @return           Rows.
	 */
	public List<ColdRow> readMatching(
			final PartitionTieredTable table,
			final String partition,
			final Map<String, String> prefix) {
		return this.partitionTieredRepository.readMatching(table, partition, ShardedBlockReader.segmentsOf(table, prefix));
	}

	/**
	 * Cold rows of the partition holding a moment, when it is readable.
	 *
	 * @param  table  Table.
	 * @param  moment Partition column value.
	 * @param  prefix Formatted key values.
	 * @return        Rows.
	 */
	public List<ColdRow> readMatchingAt(
			final PartitionTieredTable table,
			final LocalDateTime moment,
			final Map<String, String> prefix) {
		return this.readablePartitions(table).stream().filter(readable -> readable.partition().contains(moment)).findFirst()
				.map(readable -> this.readMatching(table, readable.partition().name(), prefix)).orElse(List.of());
	}

	/**
	 * Next partition to export: excluded and open-ended ones aside, a partition whose record is
	 * {@code LOADED} with the consumer's signal, or ({@code LOADED} without one, {@code STALE} or
	 * no record) past the quiet period with rows and a range already closed.
	 *
	 * @param  table Table.
	 * @return       Partition.
	 */
	public Optional<LeafPartition> findExportCandidate(
			final PartitionTieredTable table) {
		final ExportProperties export = this.properties.of(table).getExport();
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		final Map<String, PartitionArchive> archives = new LinkedHashMap<>();
		this.archiveRepository.findAll(table.getId()).forEach(archive -> archives.put(archive.partitionName(), archive));
		Optional<LeafPartition> candidate = Optional.empty();
		for (final LeafPartition partition : this.partitionHotTierRepository.leafPartitions(table)) {
			final PartitionArchive archive = archives.get(partition.name());
			final PartitionArchiveState state = archive == null ? null : archive.state();
			if (candidate.isEmpty() && !partition.openEnded() && !PartitionTieringServiceComponent.isExcluded(partition.name(), export.getExcludePartitions())
					&& ((state == null) || (state == PartitionArchiveState.LOADED) || (state == PartitionArchiveState.STALE))) {
				final Boolean signalled = (state == PartitionArchiveState.LOADED) && (archive.loadedAt() != null);
				final Boolean quiet = partition.to().isBefore(now) && this.partitionHotTierRepository.hasRows(partition.name())
						&& !this.partitionHotTierRepository.hasWatermarkAfter(table, partition.name(), now.minus(export.getQuietPeriod()));
				if (signalled || quiet) {
					candidate = Optional.of(partition);
				}
			}
		}
		return candidate;
	}

	/**
	 * Boundaries of a partition: equi-depth from a key-only pass (target rows per shard from the
	 * record's row count, the planner estimate or an exact count), or fixed prefixes.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  archive   Record, when any.
	 * @return           Boundaries and the rows counted (null for prefixes).
	 */
	private Map.Entry<List<String>, Long> computeBoundaries(
			final PartitionTieredTable table,
			final LeafPartition partition,
			final PartitionArchive archive) {
		final ExportProperties export = this.properties.of(table).getExport();
		Map.Entry<List<String>, Long> boundaries;
		if (export.getShardStrategy() == ShardStrategy.PREFIX) {
			boundaries = Map.entry(EquiDepthShardCutter.prefixBoundaries(export.getShardDigits()), -1L);
		}
		else {
			Long estimate = (archive == null) || (archive.rowCount() == null) || (archive.rowCount() <= 0) ? this.partitionHotTierRepository.estimateRows(partition.name())
					: archive.rowCount();
			if (estimate <= 0) {
				estimate = this.partitionHotTierRepository.countInRange(table, partition.name(), "", null);
			}
			final EquiDepthShardCutter cutter = new EquiDepthShardCutter(Math.max(1L, estimate / export.getShards()));
			final String shardKeyType = table.getColumnTypes().get(table.getShardKeyColumn());
			Map<String, String> lastKey = null;
			List<Map<String, String>> page = this.partitionHotTierRepository.nextKeys(table, partition.name(), null, export.getKeyPageSize());
			while (!page.isEmpty()) {
				for (final Map<String, String> key : page) {
					cutter.offer(ColumnValues.toSegment(shardKeyType, key.get(table.getShardKeyColumn())));
				}
				lastKey = page.get(page.size() - 1);
				page = page.size() < export.getKeyPageSize() ? List.of() : this.partitionHotTierRepository.nextKeys(table, partition.name(), lastKey, export.getKeyPageSize());
			}
			boundaries = Map.entry(cutter.getBoundaries(), cutter.getRows());
		}
		return boundaries;
	}

	/**
	 * Starts a partition's export: boundaries, record to {@code EXPORTING}, one shard request per
	 * shard on the export queue.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @return           Shards enqueued.
	 */
	public Integer startExport(
			final PartitionTieredTable table,
			final LeafPartition partition) {
		final LocalDateTime startedAt = DateTimeHelper.getCurrentLocalDateTime();
		final Map.Entry<List<String>, Long> boundaries = this.computeBoundaries(table, partition, this.archiveRepository.find(table.getId(), partition.name()).orElse(null));
		this.archiveRepository.startExport(table.getId(), partition.name(), partition.rangeFrom(), partition.rangeTo(), startedAt, boundaries.getKey(),
				boundaries.getValue() < 0 ? null : boundaries.getValue());
		this.partitionTieredRepository.forget(table, partition.name());
		final List<String> bounds = boundaries.getKey();
		for (int shard = 0; shard < bounds.size(); shard++) {
			final ShardExportRequest request = new ShardExportRequest(table.getId(), partition.name(), shard, bounds.get(shard),
					shard + 1 < bounds.size() ? bounds.get(shard + 1) : null, startedAt.toString());
			this.jmsTemplateHelper.send(this.jmsTemplate,
					new JmsMessage<Serializable>().withDestination(PartitionTieringServiceComponent.EXPORT_QUEUE).withMessage(request).withAfterCommit(false));
		}
		PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} shards={} rows={} enqueued", table.getId(), partition.name(),
				bounds.size(), boundaries.getValue());
		return bounds.size();
	}

	/**
	 * Verifies a written shard: the index range count equals the rows written, and every sampled
	 * row reads back byte for byte through the cold path.
	 *
	 * @param table   Table.
	 * @param request Request.
	 * @param index   Index just written.
	 * @param samples Sampled rows (key segments → line).
	 */
	private void verifyShard(
			final PartitionTieredTable table,
			final ShardExportRequest request,
			final ShardIndex index,
			final Map<List<String>, String> samples) {
		final Long counted = this.partitionHotTierRepository.countInRange(table, request.partition(), request.from(), request.to());
		if (!counted.equals(index.rows())) {
			throw new IntegrationException(new SimpleMessage("coldTiering.export.count.mismatch", new Object[] { table.getId(), request.shard(), counted, index.rows() }));
		}
		for (final Map.Entry<List<String>, String> sample : samples.entrySet()) {
			final Boolean found = this.partitionTieredRepository.readMatching(table, request.partition(), index, sample.getKey()).stream()
					.anyMatch(row -> row.rowJson().equals(sample.getValue()));
			if (!found) {
				throw new IntegrationException(new SimpleMessage("coldTiering.export.sample.mismatch", new Object[] { table.getId(), request.shard() }));
			}
		}
	}

	/**
	 * Finishes a partition once every shard is done: manifest, final checks, {@code VERIFIED} (or
	 * {@code STALE} when rows changed under the export).
	 *
	 * @param table     Table.
	 * @param partition Partition.
	 */
	private void finishPartition(
			final PartitionTieredTable table,
			final String partition) {
		final PartitionArchive archive = this.archiveRepository.find(table.getId(), partition).orElseThrow();
		final List<ShardSummary> shards = new ArrayList<>(archive.shards());
		shards.sort(Comparator.comparing(ShardSummary::shard));
		final Long rows = shards.stream().mapToLong(ShardSummary::rows).sum();
		final Long sizeBytes = shards.stream().mapToLong(ShardSummary::sizeBytes).sum();
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		if ((archive.rowCount() != null) && !archive.rowCount().equals(rows)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.export.total.mismatch", new Object[] { table.getId(), partition, archive.rowCount(), rows }));
		}
		final ExportProperties export = this.properties.of(table).getExport();
		final Map<String, Object> strategy = export.getShardStrategy() == ShardStrategy.PREFIX ? Map.of("type", "prefix", "digits", export.getShardDigits())
				: Map.of("type", "equi-depth", "target", export.getShards());
		final String manifestKey = this.partitionTieredRepository.writeManifest(table,
				new PartitionManifest(PartitionManifest.FORMAT_VERSION, table.getId(), partition, archive.rangeFrom(), archive.rangeTo(), table.getKeyColumns(), strategy,
						archive.boundaries(), shards, rows, archive.exportStartedAt().toString(), now.toString()));
		if (this.partitionHotTierRepository.hasWatermarkAfter(table, partition, archive.exportStartedAt())) {
			this.archiveRepository.markVerified(table.getId(), partition, manifestKey, rows, sizeBytes, now);
			this.archiveRepository.markState(table.getId(), partition, PartitionArchiveState.STALE, now);
			PartitionTieringServiceComponent.LOGGER.warn("[COLD_TIERING_EXPORT] table={} partition={} rows changed during the export: STALE", table.getId(), partition);
		}
		else {
			this.archiveRepository.markVerified(table.getId(), partition, manifestKey, rows, sizeBytes, now);
			PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} rows={} bytes={} verified", table.getId(), partition, rows,
					sizeBytes);
		}
	}

	/**
	 * Exports one shard (the export queue listener): keyset pages in key order into blocks and
	 * parts, the index, verification, the record's progress; the last shard finishes the
	 * partition. A shard already written for this export is skipped.
	 *
	 * @param request Request.
	 */
	@JmsListener(
			destination = PartitionTieringServiceComponent.EXPORT_QUEUE,
			concurrency = "${" + ColdTieringProperties.PREFIX + ".export.concurrency:1}",
			containerFactory = "${org.coldis.library.service.batch.container-factory:jmsListenerContainerFactory}"
	)
	public void exportShard(
			final ShardExportRequest request) {
		final PartitionTieredTable table = this.tableOf(request.tableId());
		final ExportProperties export = this.properties.of(table).getExport();
		final Optional<ShardIndex> existing = this.partitionTieredRepository.loadIndex(table, request.partition(), request.shard());
		if (existing.isPresent() && request.exportStartedAt().equals(existing.get().exportStartedAt())) {
			PartitionTieringServiceComponent.LOGGER.debug("[COLD_TIERING_EXPORT] table={} partition={} shard={} already written", table.getId(), request.partition(),
					request.shard());
		}
		else {
			final ColdUpload upload = this.partitionTieredRepository.beginBlocks(table, request.partition(), request.shard(), request.exportStartedAt());
			final ShardedBlockWriter writer = new ShardedBlockWriter(this.blockCodec, upload, export.getBlockSize(), export.getPartSize(),
					this.partitionTieredRepository.minimumPartSize());
			final Map<List<String>, String> samples = new LinkedHashMap<>();
			try {
				Map<String, String> lastKey = null;
				List<ExportRow> page = this.partitionHotTierRepository.nextPage(table, request.partition(), request.from(), request.to(), null, export.getPageSize());
				while (!page.isEmpty()) {
					for (final ExportRow row : page) {
						final List<String> segments = ShardedBlockReader.segmentsOf(table, row.key());
						writer.add(segments, row.rowJson());
						if ((writer.getRows() % export.getVerifySampleEvery()) == 1) {
							samples.put(segments, row.rowJson());
						}
					}
					lastKey = page.get(page.size() - 1).key();
					page = page.size() < export.getPageSize() ? List.of()
							: this.partitionHotTierRepository.nextPage(table, request.partition(), request.from(), request.to(), lastKey, export.getPageSize());
				}
				final List<BlockEntry> blocks = writer.finish();
				final ShardIndex index = new ShardIndex(ShardIndex.FORMAT_VERSION, table.getId(), request.partition(), request.shard(), request.exportStartedAt(),
						this.blockCodec.name(), table.getKeyColumns(), writer.getRows(), writer.getSizeBytes(), blocks);
				this.verifyShard(table, request, index, samples);
				this.partitionTieredRepository.writeIndex(table, index);
				final Integer done = this.archiveRepository.completeShard(table.getId(), request.partition(),
						new ShardSummary(request.shard(), writer.getRows(), blocks.size(), writer.getSizeBytes()), DateTimeHelper.getCurrentLocalDateTime());
				PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} shard={} rows={} blocks={} bytes={} done={}", table.getId(),
						request.partition(), request.shard(), writer.getRows(), blocks.size(), writer.getSizeBytes(), done);
				if ((done != null) && done.equals(this.archiveRepository.find(table.getId(), request.partition()).map(PartitionArchive::shardCount).orElse(-1))) {
					this.finishPartition(table, request.partition());
				}
			}
			catch (final RuntimeException exception) {
				upload.abort();
				throw exception;
			}
		}
	}

	/**
	 * Clock cutoff before which a row is expired.
	 *
	 * @param  tableProperties Properties.
	 * @return                 Cutoff.
	 */
	private static LocalDateTime expiredBefore(
			final TableProperties tableProperties) {
		return DateTimeHelper.getCurrentLocalDateTime().minus(tableProperties.getTtl()).minus(tableProperties.getSafetyMargin());
	}

	/**
	 * Next partition to sweep: verified, not swept, past its hold and its TTL.
	 *
	 * @param  table Table.
	 * @return       Record.
	 */
	public Optional<PartitionArchive> findSweepCandidate(
			final PartitionTieredTable table) {
		final TableProperties tableProperties = this.properties.of(table);
		final SweepProperties sweep = tableProperties.getPartitionSweep();
		final Duration wait = sweep.getHoldAfterLoad().compareTo(tableProperties.getTtl().plus(tableProperties.getSafetyMargin())) > 0 ? sweep.getHoldAfterLoad()
				: tableProperties.getTtl().plus(tableProperties.getSafetyMargin());
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		return this.archiveRepository.findAll(table.getId()).stream().filter(archive -> (archive.state() == PartitionArchiveState.VERIFIED)
				&& (archive.sweptAt() == null) && (archive.holdStart() != null) && archive.holdStart().plus(wait).isBefore(now)).findFirst();
	}

	/**
	 * Sweeps the next chunk of heap blocks of a partition, in heap order, and saves the cursor;
	 * records the sweep finished when the cursor passes the last block.
	 *
	 * @param  table   Table.
	 * @param  archive Record.
	 * @return         Whether blocks remain.
	 */
	public Boolean sweepChunk(
			final PartitionTieredTable table,
			final PartitionArchive archive) {
		final TableProperties tableProperties = this.properties.of(table);
		final PartitionArchive current = this.archiveRepository.find(table.getId(), archive.partitionName()).orElseThrow();
		if (current.state() != PartitionArchiveState.VERIFIED) {
			throw new IntegrationException(new SimpleMessage("coldTiering.sweep.state.invalid", new Object[] { table.getId(), archive.partitionName() }));
		}
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		final Long blocks = current.sweepBlocks() == null ? this.partitionHotTierRepository.blockCount(current.partitionName()) : current.sweepBlocks();
		final Long cursor = current.sweepCursor() == null ? 0L : current.sweepCursor();
		Boolean remaining = cursor < blocks;
		if (remaining) {
			final Long next = Math.min(blocks, cursor + tableProperties.getPartitionSweep().getChunkSize());
			final SweepResult result = this.partitionHotTierRepository.sweepChunk(table, current.partitionName(), cursor, next,
					PartitionTieringServiceComponent.expiredBefore(tableProperties), current.exportedAt());
			final Long deleted = (current.sweepDeleted() == null ? 0L : current.sweepDeleted()) + result.deleted();
			final Long kept = (current.sweepKept() == null ? 0L : current.sweepKept()) + result.kept();
			remaining = next < blocks;
			this.archiveRepository.saveSweep(table.getId(), current.partitionName(), blocks, next, deleted, kept, remaining ? null : now, now);
			PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_SWEEP] table={} partition={} blocks={}-{}/{} deleted={} kept={}", table.getId(),
					current.partitionName(), cursor, next, blocks, result.deleted(), result.kept());
		}
		else {
			this.archiveRepository.saveSweep(table.getId(), current.partitionName(), blocks, cursor, current.sweepDeleted(), current.sweepKept(), now, now);
		}
		return remaining;
	}

}
