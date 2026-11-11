package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import org.coldis.library.service.tiering.model.ArchiveUnit;
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
import org.coldis.library.service.tiering.model.TieredRowKey;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partition tiering policy: the consumer's signals, the export (boundaries pass, shard listener,
 * manifest and verification, one generation per export), the sweep, and the cold lookups of
 * partition-tiered tables ({@link ColdLookupStrategy}). No transaction spans an S3 call but the
 * manifest write that finishes a partition, which runs inside the record's final transaction so
 * the record never names a manifest that does not exist.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class PartitionTieringServiceComponent implements ColdLookupStrategy {

	/** A readable partition: catalog entry and record. */
	public record ReadablePartition(LeafPartition partition, PartitionArchive archive) {

	}

	/** Export queue. */
	public static final String EXPORT_QUEUE = "cold-tiering/export";

	/** Generation format. */
	private static final DateTimeFormatter GENERATION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

	/** Digit-only text. */
	private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

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
	 * Partition of a table holding a moment.
	 *
	 * @param  table  Table.
	 * @param  moment Moment (partition column value).
	 * @return        Partition.
	 */
	private LeafPartition partitionOf(
			final PartitionTieredTable table,
			final LocalDateTime moment) {
		return this.partitionHotTierRepository.leafPartitions(table).stream().filter(partition -> !partition.isOpenEnded() && partition.contains(moment)).findFirst()
				.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.partition.missing", new Object[] { table.getId() })));
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
		Boolean isMatched = false;
		for (final String pattern : glob.split(",")) {
			if (!pattern.isBlank() && Pattern.compile("^" + Pattern.quote(pattern.trim()).replace("*", "\\E.*\\Q") + "$").matcher(name).matches()) {
				isMatched = true;
			}
		}
		return isMatched;
	}

	/**
	 * Canonical segment of a moment on the last key column (null for null).
	 *
	 * @param  table  Table.
	 * @param  moment Moment.
	 * @return        Segment.
	 */
	private static String segmentOfMoment(
			final TieredTable table,
			final LocalDateTime moment) {
		final String lastType = table.getColumnTypes().get(table.getLastKeyColumn());
		return moment == null ? null : ColumnValues.toSegment(lastType, ColumnValues.ofMoment(lastType, moment));
	}

	/**
	 * Partition column value of a row key as a moment.
	 *
	 * @param  table Table.
	 * @param  key   Key.
	 * @return       Moment.
	 */
	private static LocalDateTime momentOf(
			final PartitionTieredTable table,
			final TieredRowKey key) {
		final String type = table.getColumnTypes().get(table.getPartitionColumn());
		return (LocalDateTime) ColumnValues.toJavaValue(ColumnValues.parse(type, key.values().get(table.getPartitionColumn())), LocalDateTime.class);
	}

	/**
	 * Requires a shard key value whose SQL order is its canonical segment order: digit-only text
	 * or a non-negative integer (so no collation can reorder the shards).
	 *
	 * @param table Table.
	 * @param value Formatted shard key value.
	 */
	private static void requireOrderableShardKey(
			final PartitionTieredTable table,
			final String value) {
		final String type = table.getColumnTypes().get(table.getShardKeyColumn());
		final Boolean isOrderable = ColumnValues.INTEGER_TYPES.contains(type) ? !value.startsWith("-") : PartitionTieringServiceComponent.DIGITS.matcher(value).matches();
		if (!isOrderable) {
			throw new IntegrationException(new SimpleMessage("coldTiering.export.shard.key.invalid", new Object[] { table.getId(), table.getShardKeyColumn() }));
		}
	}

	/**
	 * Signals a partition loaded (the consumer, when a delivery is complete). Starts the hold and
	 * makes the partition exportable; on an already exported partition it means a reload (the
	 * previous export stays readable until the new one is verified).
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
	 * Confirms the re-export of a swept partition (an operator): the next export replaces the
	 * archive, orphaning the rows only the previous generation holds.
	 *
	 * @param tableId Table id.
	 * @param moment  A moment inside the partition.
	 */
	public void confirmReexport(
			final String tableId,
			final LocalDateTime moment) {
		final PartitionTieredTable table = this.tableOf(tableId);
		final LeafPartition partition = this.partitionOf(table, moment);
		this.archiveRepository.confirmReexport(tableId, partition.name(), DateTimeHelper.getCurrentLocalDateTime());
		PartitionTieringServiceComponent.LOGGER.warn("[COLD_TIERING_EXPORT] table={} partition={} re-export confirmed by an operator", tableId, partition.name());
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
	 * Readable partitions (a manifest exists), oldest first.
	 *
	 * @param  table Table.
	 * @return       Partitions.
	 */
	public List<ReadablePartition> readablePartitions(
			final PartitionTieredTable table) {
		final Map<String, PartitionArchive> archives = new LinkedHashMap<>();
		for (final PartitionArchive archive : this.archiveRepository.findAll(table.getId())) {
			if (archive.isReadable()) {
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
	 * Readable partitions intersecting a moment range, oldest first.
	 *
	 * @param  table Table.
	 * @param  after Exclusive lower moment, or null.
	 * @param  upTo  Inclusive upper moment, or null.
	 * @return       Partitions.
	 */
	private List<ReadablePartition> partitionsBetween(
			final PartitionTieredTable table,
			final LocalDateTime after,
			final LocalDateTime upTo) {
		final List<ReadablePartition> partitions = new ArrayList<>();
		for (final ReadablePartition readable : this.readablePartitions(table)) {
			final Boolean startsInTime = (upTo == null) || (readable.partition().from() == null) || !readable.partition().from().isAfter(upTo);
			final Boolean endsInTime = (after == null) || (readable.partition().to() == null) || readable.partition().to().isAfter(after);
			if (startsInTime && endsInTime) {
				partitions.add(readable);
			}
		}
		return partitions;
	}

	/**
	 * Cold rows of a readable partition whose key starts with the given values (shard key first).
	 *
	 * @param  table    Table.
	 * @param  readable Partition.
	 * @param  prefix   Formatted key values, by column (a prefix of the key columns).
	 * @return          Rows.
	 */
	public List<ColdRow> readMatching(
			final PartitionTieredTable table,
			final ReadablePartition readable,
			final Map<String, String> prefix) {
		return this.partitionTieredRepository.readMatching(table, readable.partition().name(), readable.archive().manifestKey(),
				ShardedBlockReader.segmentsOf(table, prefix));
	}

	/**
	 * Cold rows of a partition by name (tests and tooling).
	 *
	 * @param  table     Table.
	 * @param  partition Partition name.
	 * @param  prefix    Formatted key values.
	 * @return           Rows.
	 */
	public List<ColdRow> readMatching(
			final PartitionTieredTable table,
			final String partition,
			final Map<String, String> prefix) {
		return this.readablePartitions(table).stream().filter(readable -> readable.partition().name().equals(partition)).findFirst()
				.map(readable -> this.readMatching(table, readable, prefix)).orElse(List.of());
	}

	/**
	 * Cold rows of a key prefix over partitions, in the given order, bounded by a segment range
	 * on the last key column; stops at the first partition with a match when {@code firstOnly}.
	 *
	 * @param  table      Table.
	 * @param  partitions Partitions to probe, in probe order.
	 * @param  prefix     Formatted key prefix.
	 * @param  after      Exclusive lower segment, or null.
	 * @param  upTo       Inclusive upper segment, or null.
	 * @param  isFirstPartitionOnly Stop at the first partition with a match.
	 * @return            Matching rows.
	 */
	private List<ColdRow> coldRows(
			final PartitionTieredTable table,
			final List<ReadablePartition> partitions,
			final Map<String, String> prefix,
			final String after,
			final String upTo,
			final Boolean isFirstPartitionOnly) {
		final List<ColdRow> rows = new ArrayList<>();
		for (final ReadablePartition readable : partitions) {
			if (!isFirstPartitionOnly || rows.isEmpty()) {
				for (final ColdRow row : this.readMatching(table, readable, prefix)) {
					final String segment = ColumnValues.lastSegmentOf(table, row.key());
					if (((after == null) || (segment.compareTo(after) > 0)) && ((upTo == null) || (segment.compareTo(upTo) <= 0))) {
						rows.add(row);
					}
				}
			}
		}
		return rows;
	}

	/**
	 * @see ColdLookupStrategy#unit()
	 */
	@Override
	public ArchiveUnit unit() {
		return ArchiveUnit.PARTITION;
	}

	/**
	 * @see ColdLookupStrategy#findCold(TieredTable, TieredRowKey)
	 */
	@Override
	public Optional<ColdRow> findCold(
			final TieredTable table,
			final TieredRowKey key) {
		final PartitionTieredTable partitioned = this.tableOf(table.getId());
		final LocalDateTime moment = PartitionTieringServiceComponent.momentOf(partitioned, key);
		return this.readablePartitions(partitioned).stream().filter(readable -> readable.partition().contains(moment)).findFirst()
				.flatMap(readable -> this.readMatching(partitioned, readable, key.values()).stream().filter(row -> row.key().equals(key)).findFirst());
	}

	/**
	 * @see ColdLookupStrategy#isArchived(TieredTable, TieredRowKey)
	 */
	@Override
	public Boolean isArchived(
			final TieredTable table,
			final TieredRowKey key) {
		final PartitionTieredTable partitioned = this.tableOf(table.getId());
		final LocalDateTime moment = PartitionTieringServiceComponent.momentOf(partitioned, key);
		return this.readablePartitions(partitioned).stream().anyMatch(readable -> readable.partition().contains(moment));
	}

	/**
	 * @see ColdLookupStrategy#findNewestCold(TieredTable, Map, LocalDateTime, Optional)
	 */
	@Override
	public Optional<ColdRow> findNewestCold(
			final TieredTable table,
			final Map<String, String> prefix,
			final LocalDateTime asOf,
			final Optional<TieredRowKey> hotCandidate) {
		final PartitionTieredTable partitioned = this.tableOf(table.getId());
		final LocalDateTime hotMoment = hotCandidate.map(key -> PartitionTieringServiceComponent.momentOf(partitioned, key)).orElse(null);
		final List<ReadablePartition> partitions = new ArrayList<>(this.partitionsBetween(partitioned, hotMoment, asOf));
		partitions.sort(Comparator.comparing((ReadablePartition readable) -> readable.partition().from(), Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
		final String after = hotCandidate.map(key -> ColumnValues.lastSegmentOf(table, key)).orElse(null);
		return this.coldRows(partitioned, partitions, prefix, after, PartitionTieringServiceComponent.segmentOfMoment(table, asOf), true).stream()
				.max(Comparator.comparing(row -> ColumnValues.lastSegmentOf(table, row.key())));
	}

	/**
	 * @see ColdLookupStrategy#findFirstCold(TieredTable, Map, LocalDateTime, LocalDateTime, Optional)
	 */
	@Override
	public Optional<ColdRow> findFirstCold(
			final TieredTable table,
			final Map<String, String> prefix,
			final LocalDateTime after,
			final LocalDateTime upTo,
			final Optional<TieredRowKey> hotCandidate) {
		final PartitionTieredTable partitioned = this.tableOf(table.getId());
		final LocalDateTime hotMoment = hotCandidate.map(key -> PartitionTieringServiceComponent.momentOf(partitioned, key)).orElse(upTo);
		final String ceiling = hotCandidate.map(key -> ColumnValues.lastSegmentOf(table, key))
				.orElse(PartitionTieringServiceComponent.segmentOfMoment(table, upTo));
		return this.coldRows(partitioned, this.partitionsBetween(partitioned, after, hotMoment), prefix, PartitionTieringServiceComponent.segmentOfMoment(table, after),
				ceiling, true).stream().filter(row -> hotCandidate.isEmpty() || (ColumnValues.lastSegmentOf(table, row.key()).compareTo(ceiling) < 0))
				.min(Comparator.comparing(row -> ColumnValues.lastSegmentOf(table, row.key())));
	}

	/**
	 * @see ColdLookupStrategy#findTimelineCold(TieredTable, Map, LocalDateTime)
	 */
	@Override
	public List<ColdRow> findTimelineCold(
			final TieredTable table,
			final Map<String, String> prefix,
			final LocalDateTime asOf) {
		final PartitionTieredTable partitioned = this.tableOf(table.getId());
		return this.coldRows(partitioned, this.partitionsBetween(partitioned, null, asOf), prefix, null, PartitionTieringServiceComponent.segmentOfMoment(table, asOf),
				false);
	}

	/**
	 * Next partition to export: excluded and open-ended ones aside, a partition whose record is
	 * {@code LOADED} with the consumer's signal, or ({@code LOADED} without one, {@code STALE} or
	 * no record) past the quiet period with rows and a range already closed. A partition needing
	 * attention or being exported is never a candidate.
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
		final List<LeafPartition> partitions = this.partitionHotTierRepository.leafPartitions(table);
		int position = 0;
		while (candidate.isEmpty() && (position < partitions.size())) {
			final LeafPartition partition = partitions.get(position);
			final PartitionArchive archive = archives.get(partition.name());
			final PartitionArchiveState state = archive == null ? null : archive.state();
			if (!partition.isOpenEnded() && !PartitionTieringServiceComponent.isExcluded(partition.name(), export.getExcludePartitions())
					&& ((state == null) || (state == PartitionArchiveState.LOADED) || (state == PartitionArchiveState.STALE))) {
				final Boolean isSignalled = (state == PartitionArchiveState.LOADED) && (archive.loadedAt() != null);
				final Boolean isQuiet = isSignalled || (partition.to().isBefore(now) && this.partitionHotTierRepository.hasRows(partition.name())
						&& !this.partitionHotTierRepository.hasWatermarkAfter(table, partition.name(), now.minus(export.getQuietPeriod())));
				if (isQuiet) {
					candidate = Optional.of(partition);
				}
			}
			position++;
		}
		return candidate;
	}

	/**
	 * Boundaries of a partition: equi-depth from a key-only pass (target rows per shard from the
	 * loader's row count, the planner estimate or an exact count), or fixed prefixes.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  archive   Record, when any.
	 * @return           Boundaries.
	 */
	private List<String> computeBoundaries(
			final PartitionTieredTable table,
			final LeafPartition partition,
			final PartitionArchive archive) {
		final ExportProperties export = this.properties.of(table).getExport();
		List<String> boundaries;
		if (export.getShardStrategy() == ShardStrategy.PREFIX) {
			boundaries = EquiDepthShardCutter.prefixBoundaries(export.getShardDigits());
		}
		else {
			Long estimate = (archive == null) || (archive.loadedRows() == null) || (archive.loadedRows() <= 0) ? this.partitionHotTierRepository.estimateRows(partition.name())
					: archive.loadedRows();
			if (estimate <= 0) {
				estimate = this.partitionHotTierRepository.countInRange(table, partition.name(), "", null);
			}
			final EquiDepthShardCutter cutter = new EquiDepthShardCutter(Math.max(1L, estimate / export.getShards()));
			final String shardKeyType = table.getColumnTypes().get(table.getShardKeyColumn());
			Map<String, String> lastKey = null;
			List<Map<String, String>> page = this.partitionHotTierRepository.nextKeys(table, partition.name(), null, export.getKeyPageSize());
			while (!page.isEmpty()) {
				for (final Map<String, String> key : page) {
					PartitionTieringServiceComponent.requireOrderableShardKey(table, key.get(table.getShardKeyColumn()));
					cutter.offer(ColumnValues.toSegment(shardKeyType, key.get(table.getShardKeyColumn())));
				}
				lastKey = page.get(page.size() - 1);
				page = page.size() < export.getKeyPageSize() ? List.of() : this.partitionHotTierRepository.nextKeys(table, partition.name(), lastKey, export.getKeyPageSize());
			}
			boundaries = cutter.getBoundaries();
		}
		return boundaries;
	}

	/**
	 * Starts a partition's export under a new generation: boundaries, record to
	 * {@code EXPORTING} (the previous manifest stays the readable one), one shard request per
	 * shard on the export queue, sent after the commit. A partition whose sweep started is not
	 * exported (rows only the archive holds would be orphaned by a partial re-export) unless an
	 * operator confirmed it ({@link #confirmReexport}): it is marked {@code NEEDS_ATTENTION}.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @return           Shards enqueued (0 when refused).
	 */
	public Integer startExport(
			final PartitionTieredTable table,
			final LeafPartition partition) {
		final LocalDateTime startedAt = DateTimeHelper.getCurrentLocalDateTime();
		final PartitionArchive archive = this.archiveRepository.find(table.getId(), partition.name()).orElse(null);
		Integer enqueued = 0;
		if ((archive != null) && archive.isSweepStarted() && !archive.isReexportConfirmed()) {
			this.archiveRepository.markState(table.getId(), partition.name(), PartitionArchiveState.NEEDS_ATTENTION, startedAt);
			PartitionTieringServiceComponent.LOGGER.error(
					"[COLD_TIERING_EXPORT] table={} partition={} swept before: a re-export would orphan the rows only the archive holds; refused until confirmReexport, needs attention",
					table.getId(), partition.name());
		}
		else {
			final List<String> bounds = this.computeBoundaries(table, partition, archive);
			final String generation = startedAt.format(PartitionTieringServiceComponent.GENERATION_FORMAT);
			this.archiveRepository.startExport(table.getId(), partition.name(), partition.rangeFrom(), partition.rangeTo(), startedAt, generation, bounds);
			for (int shard = 0; shard < bounds.size(); shard++) {
				final ShardExportRequest request = new ShardExportRequest(table.getId(), partition.name(), generation, shard, bounds.get(shard),
						(shard + 1) < bounds.size() ? bounds.get(shard + 1) : null, startedAt.toString());
				this.jmsTemplateHelper.send(this.jmsTemplate,
						new JmsMessage<Serializable>().withDestination(PartitionTieringServiceComponent.EXPORT_QUEUE).withMessage(request).withAfterCommit(true));
			}
			enqueued = bounds.size();
			PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} generation={} shards={} enqueued", table.getId(),
					partition.name(), generation, enqueued);
		}
		return enqueued;
	}

	/**
	 * Verifies a written shard: the index range count equals the rows written, and every sampled
	 * row reads back byte for byte through the cold path. Public so an operator (or a test) can
	 * re-run a verification against the hot table; it changes nothing.
	 *
	 * @param table   Table.
	 * @param request Request.
	 * @param index   Index just written.
	 * @param samples Sampled rows (key segments to line).
	 */
	public void verifyShard(
			final PartitionTieredTable table,
			final ShardExportRequest request,
			final ShardIndex index,
			final Map<List<String>, String> samples) {
		final Long counted = this.partitionHotTierRepository.countInRange(table, request.partition(), request.from(), request.to());
		if (!counted.equals(index.rows())) {
			throw new IntegrationException(new SimpleMessage("coldTiering.export.count.mismatch", new Object[] { table.getId(), request.shard(), counted, index.rows() }));
		}
		for (final Map.Entry<List<String>, String> sample : samples.entrySet()) {
			final Boolean isSampleFound = this.partitionTieredRepository.readMatching(table, request.partition(), index, sample.getKey()).stream()
					.anyMatch(row -> row.rowJson().equals(sample.getValue()));
			if (!isSampleFound) {
				throw new IntegrationException(new SimpleMessage("coldTiering.export.sample.mismatch", new Object[] { table.getId(), request.shard() }));
			}
		}
	}

	/**
	 * Finishes a generation once every shard is done: manifest under the generation, the record's
	 * final state in one update ({@code VERIFIED}, or {@code STALE} when rows changed under the
	 * export) with the manifest pointer switched last.
	 *
	 * @param table   Table.
	 * @param archive Record.
	 */
	private void finishPartition(
			final PartitionTieredTable table,
			final PartitionArchive archive) {
		final List<ShardSummary> shards = new ArrayList<>(archive.shards());
		shards.sort(Comparator.comparing(ShardSummary::shard));
		final Long rows = shards.stream().mapToLong(ShardSummary::rows).sum();
		final Long sizeBytes = shards.stream().mapToLong(ShardSummary::sizeBytes).sum();
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		final ExportProperties export = this.properties.of(table).getExport();
		final Map<String, Object> strategy = export.getShardStrategy() == ShardStrategy.PREFIX ? Map.of("type", "prefix", "digits", export.getShardDigits())
				: Map.of("type", "equi-depth", "target", export.getShards());
		final String manifestKey = this.partitionTieredRepository.writeManifest(table,
				new PartitionManifest(PartitionManifest.FORMAT_VERSION, table.getId(), archive.partitionName(), archive.generation(), archive.rangeFrom(),
						archive.rangeTo(), table.getKeyColumns(), strategy, archive.boundaries(), shards, rows, archive.exportStartedAt().toString(), now.toString()));
		final Boolean hasChanged = this.partitionHotTierRepository.hasWatermarkAfter(table, archive.partitionName(), archive.exportStartedAt());
		this.archiveRepository.finishExport(table.getId(), archive.partitionName(), hasChanged ? PartitionArchiveState.STALE : PartitionArchiveState.VERIFIED, manifestKey,
				rows, sizeBytes, now);
		if (hasChanged) {
			PartitionTieringServiceComponent.LOGGER.warn("[COLD_TIERING_EXPORT] table={} partition={} generation={} rows changed during the export: STALE", table.getId(),
					archive.partitionName(), archive.generation());
		}
		else {
			PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} generation={} rows={} bytes={} verified", table.getId(),
					archive.partitionName(), archive.generation(), rows, sizeBytes);
		}
	}

	/**
	 * Records a shard done and finishes the partition when it was the last one, in one
	 * transaction. A shard already recorded (a redelivery) changes nothing but still runs the
	 * finish check, so a partition never stays exporting with every shard written.
	 *
	 * @param table   Table.
	 * @param request Request.
	 * @param summary Shard summary.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public void recordShard(
			final PartitionTieredTable table,
			final ShardExportRequest request,
			final ShardSummary summary) {
		final PartitionArchive seen = this.archiveRepository.find(table.getId(), request.partition()).orElse(null);
		if ((seen == null) || (seen.generation() == null) || (seen.generation().compareTo(request.generation()) < 0)) {
			// The record has not seen this generation yet (the request overtook its commit): redelivered.
			throw new IntegrationException(new SimpleMessage("coldTiering.export.generation.unseen", new Object[] { table.getId(), request.partition() }));
		}
		final Integer done = this.archiveRepository.completeShard(table.getId(), request.partition(), request.generation(), summary,
				DateTimeHelper.getCurrentLocalDateTime());
		final PartitionArchive archive = this.archiveRepository.find(table.getId(), request.partition()).orElseThrow();
		PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_EXPORT] table={} partition={} shard={} rows={} blocks={} bytes={} done={}", table.getId(),
				request.partition(), request.shard(), summary.rows(), summary.blocks(), summary.sizeBytes(), done == null ? archive.shardsDone() : done);
		if ((archive.state() == PartitionArchiveState.EXPORTING) && request.generation().equals(archive.generation()) && archive.shardsDone().equals(archive.shardCount())) {
			this.finishPartition(table, archive);
		}
	}

	/**
	 * Exports one shard (the export queue listener): keyset pages in key order into blocks and
	 * parts, the index, verification, then the record. A shard already written for this
	 * generation is only recorded again.
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
		final Optional<ShardIndex> existing = this.partitionTieredRepository.loadIndex(table, request.partition(), request.generation(), request.shard());
		if (existing.isPresent() && request.exportStartedAt().equals(existing.get().exportStartedAt())) {
			PartitionTieringServiceComponent.LOGGER.debug("[COLD_TIERING_EXPORT] table={} partition={} shard={} already written", table.getId(), request.partition(),
					request.shard());
			this.recordShard(table, request, new ShardSummary(request.shard(), existing.get().rows(), existing.get().blocks().size(), existing.get().sizeBytes()));
		}
		else {
			final ColdUpload upload = this.partitionTieredRepository.beginBlocks(table, request.partition(), request.generation(), request.shard(),
					request.exportStartedAt());
			final ShardedBlockWriter writer = new ShardedBlockWriter(this.blockCodec, upload, export.getBlockSize(), export.getPartSize(),
					this.partitionTieredRepository.minimumPartSize());
			final Map<List<String>, String> samples = new LinkedHashMap<>();
			List<BlockEntry> blocks;
			try {
				Map<String, String> lastKey = null;
				List<ExportRow> page = this.partitionHotTierRepository.nextPage(table, request.partition(), request.from(), request.to(), null, export.getPageSize());
				while (!page.isEmpty()) {
					for (final ExportRow row : page) {
						PartitionTieringServiceComponent.requireOrderableShardKey(table, row.key().get(table.getShardKeyColumn()));
						final List<String> segments = ShardedBlockReader.segmentsOf(table, row.key());
						writer.add(segments, row.rowJson());
						if (((writer.getRows() - 1) % export.getVerifySampleEvery()) == 0) {
							samples.put(segments, row.rowJson());
						}
					}
					lastKey = page.get(page.size() - 1).key();
					page = page.size() < export.getPageSize() ? List.of()
							: this.partitionHotTierRepository.nextPage(table, request.partition(), request.from(), request.to(), lastKey, export.getPageSize());
				}
				blocks = writer.finish();
				final ShardIndex index = new ShardIndex(ShardIndex.FORMAT_VERSION, table.getId(), request.partition(), request.generation(), request.shard(),
						request.exportStartedAt(), this.blockCodec.name(), table.getKeyColumns(), writer.getRows(), writer.getSizeBytes(), blocks);
				this.verifyShard(table, request, index, samples);
				this.partitionTieredRepository.writeIndex(table, index);
			}
			catch (final RuntimeException exception) {
				try {
					upload.abort();
				}
				catch (final RuntimeException abortException) {
					exception.addSuppressed(abortException);
				}
				throw exception;
			}
			this.recordShard(table, request, new ShardSummary(request.shard(), writer.getRows(), blocks.size(), writer.getSizeBytes()));
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
		return this.archiveRepository.findAll(table.getId()).stream().filter(archive -> archive.state().isSweepable() && (archive.sweptAt() == null)
				&& (archive.holdStart() != null) && archive.holdStart().plus(wait).isBefore(now)).findFirst();
	}

	/**
	 * Sweeps the next block range of a partition and saves the cursor.
	 *
	 * @param  table           Table.
	 * @param  tableProperties Properties.
	 * @param  current         Record.
	 * @param  now             Moment.
	 * @return                 Whether blocks remain.
	 */
	private Boolean sweepNextChunk(
			final PartitionTieredTable table,
			final TableProperties tableProperties,
			final PartitionArchive current,
			final LocalDateTime now) {
		Boolean hasRemainingBlocks = false;
		final Long blocks = current.sweepBlocks() == null ? this.partitionHotTierRepository.blockCount(current.partitionName()) : current.sweepBlocks();
		final Long cursor = current.sweepCursor() == null ? 0L : current.sweepCursor();
		if (cursor < blocks) {
			final Long next = Math.min(blocks, cursor + tableProperties.getPartitionSweep().getChunkSize());
			final SweepResult result = this.partitionHotTierRepository.sweepChunk(table, current.partitionName(), cursor, next,
					PartitionTieringServiceComponent.expiredBefore(tableProperties), current.exportStartedAt());
			final Long deleted = (current.sweepDeleted() == null ? 0L : current.sweepDeleted()) + result.deleted();
			final Long kept = (current.sweepKept() == null ? 0L : current.sweepKept()) + result.kept();
			hasRemainingBlocks = next < blocks;
			this.archiveRepository.saveSweep(table.getId(), current.partitionName(), blocks, next, deleted, kept, hasRemainingBlocks ? null : now, now);
			PartitionTieringServiceComponent.LOGGER.info("[COLD_TIERING_SWEEP] table={} partition={} blocks={}-{}/{} deleted={} kept={} skipped={}", table.getId(),
					current.partitionName(), cursor, next, blocks, result.deleted(), result.kept(), result.skipped());
		}
		else {
			this.archiveRepository.saveSweep(table.getId(), current.partitionName(), blocks, cursor, current.sweepDeleted(), current.sweepKept(), now, now);
		}
		return hasRemainingBlocks;
	}

	/**
	 * Sweeps the next chunk of heap blocks of a partition, in heap order, and saves the cursor;
	 * records the sweep finished when the cursor passes the last block. The first chunk re-counts
	 * the partition against the manifest: a difference means rows the archive does not cover,
	 * and the partition goes {@code STALE} (committed, nothing deleted) instead of being swept.
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
		if (!current.state().isSweepable()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.sweep.state.invalid", new Object[] { table.getId(), archive.partitionName() }));
		}
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		Boolean hasRemainingBlocks = false;
		final Long covered = current.sweepCursor() == null ? this.partitionHotTierRepository.countInRange(table, current.partitionName(), "", null) : current.rowCount();
		if (!covered.equals(current.rowCount())) {
			this.archiveRepository.markState(table.getId(), current.partitionName(), PartitionArchiveState.STALE, now);
			PartitionTieringServiceComponent.LOGGER.error("[COLD_TIERING_SWEEP] table={} partition={} holds {} rows against {} archived: not swept, STALE", table.getId(),
					current.partitionName(), covered, current.rowCount());
		}
		else {
			hasRemainingBlocks = this.sweepNextChunk(table, tableProperties, current, now);
		}
		return hasRemainingBlocks;
	}

}
