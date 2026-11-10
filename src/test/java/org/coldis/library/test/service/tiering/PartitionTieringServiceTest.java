package org.coldis.library.test.service.tiering;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.service.tiering.ColdTieringBatchServiceComponent;
import org.coldis.library.service.tiering.ColdTieringServiceComponent;
import org.coldis.library.service.tiering.PartitionTieringServiceComponent;
import org.coldis.library.service.tiering.TieredHit;
import org.coldis.library.service.tiering.TieringBatch;
import org.coldis.library.service.tiering.model.LeafPartition;
import org.coldis.library.service.tiering.model.PartitionArchive;
import org.coldis.library.service.tiering.model.PartitionArchiveState;
import org.coldis.library.service.tiering.model.PartitionManifest;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.ShardExportRequest;
import org.coldis.library.service.tiering.repository.FolderColdStorageRepository;
import org.coldis.library.service.tiering.repository.PartitionArchiveRepository;
import org.coldis.library.service.tiering.repository.PartitionTieredRepository;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partition tiering on a self-created partitioned table and the folder storage: export (equi-depth
 * shards, blocks, indexes, manifest, verification), cold reads through the cached index and
 * range reads, rehydration into the month's partition, the hold, the sweep, the row-delete
 * eviction of warm rows, stale partitions, corrupt blocks and refused formats.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class PartitionTieringServiceTest extends ContainerTestHelper {

	/** January partition. */
	private static final String JANUARY = ColdTieringTestConfiguration.MONTHLY_TABLE_NAME + "_2026_01";

	/** A January moment. */
	private static final LocalDateTime JANUARY_MOMENT = LocalDateTime.of(2026, 1, 10, 0, 0);

	/** A February moment. */
	private static final LocalDateTime FEBRUARY_MOMENT = LocalDateTime.of(2026, 2, 10, 0, 0);

	/** Owners with a January row (skewed prefixes). */
	private static final List<String> JANUARY_OWNERS = new ArrayList<>();

	static {
		for (int index = 1; index <= 40; index++) {
			PartitionTieringServiceTest.JANUARY_OWNERS.add(String.format("a%03d", index));
		}
		for (int index = 1; index <= 12; index++) {
			PartitionTieringServiceTest.JANUARY_OWNERS.add(String.format("z%03d", index));
		}
	}

	@Autowired
	private ColdTieringServiceComponent tieringServiceComponent;

	@Autowired
	private PartitionTieringServiceComponent partitionTieringServiceComponent;

	@Autowired
	private ColdTieringBatchServiceComponent batchServiceComponent;

	@Autowired
	private PartitionTieredRepository partitionTieredRepository;

	@Autowired
	private PartitionArchiveRepository archiveRepository;

	@Autowired
	private FolderColdStorageRepository coldStorage;

	@Autowired
	private TieredMonthlySnapshotRepository repository;

	@Autowired
	private KeyValueServiceComponent keyValueService;

	@Autowired
	private PartitionTieredTable table;

	@BeforeEach
	public void beforeEach() {
		TestHelper.cleanClock();
		this.batchServiceComponent.cancelAll();
		this.clean();
		this.coldStorage.reset();
		this.partitionTieredRepository.forgetAll();
	}

	@AfterEach
	public void afterEach() {
		TestHelper.cleanClock();
		this.batchServiceComponent.cancelAll();
	}

	@Transactional
	private void clean() {
		this.truncateTables(ColdTieringTestConfiguration.MONTHLY_TABLE_NAME);
		this.archiveRepository.deleteAll(ColdTieringTestConfiguration.MONTHLY_TABLE);
		for (final TieringBatch batch : TieringBatch.values()) {
			this.keyValueService.getRepository().deleteById(BatchService.BATCH_KEY_PREFIX + batch.keySuffix(ColdTieringTestConfiguration.MONTHLY_TABLE));
		}
	}

	@Transactional
	private void seed(
			final String owner,
			final LocalDateTime cachedAt) {
		this.repository.save(new TieredMonthlySnapshot(owner, cachedAt, new HashMap<>(Map.of("marker", owner + "@" + cachedAt.getMonthValue()))));
	}

	private void seedMonths() {
		for (final String owner : PartitionTieringServiceTest.JANUARY_OWNERS) {
			this.seed(owner, PartitionTieringServiceTest.JANUARY_MOMENT);
		}
		// Owners a001..a005 have a February row too; a099 only February.
		for (int index = 1; index <= 5; index++) {
			this.seed(String.format("a%03d", index), PartitionTieringServiceTest.FEBRUARY_MOMENT);
		}
		this.seed("a099", PartitionTieringServiceTest.FEBRUARY_MOMENT);
	}

	private List<TieredMonthlySnapshot> januaryRows() {
		return this.repository.findByCachedAtGreaterThanEqualAndCachedAtLessThan(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0));
	}

	private Optional<PartitionArchive> archive() {
		return this.archiveRepository.find(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY);
	}

	private PartitionArchive exportJanuary() throws Exception {
		this.partitionTieringServiceComponent.partitionLoaded(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY_MOMENT, null);
		Assertions.assertEquals(PartitionArchiveState.LOADED, this.archive().orElseThrow().state());
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.EXPORT));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::state).orElse(null), state -> state == PartitionArchiveState.VERIFIED,
				TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		return this.archive().orElseThrow();
	}

	private Path pathOf(
			final String key) {
		return this.coldStorage.getRoot().resolve(key);
	}

	@Test
	@DisplayName("Export: equi-depth shards, manifest last, verified; hot reads cost no range read")
	public void testExport() throws Exception {
		this.seedMonths();
		final PartitionArchive archive = this.exportJanuary();
		Assertions.assertEquals(52L, archive.rowCount());
		Assertions.assertTrue((archive.shardCount() >= 6) && (archive.shardCount() <= 10), "shards: " + archive.shardCount());
		Assertions.assertEquals(archive.shardCount(), archive.shardsDone());
		Assertions.assertNotNull(archive.verifiedAt());
		final PartitionManifest manifest = this.partitionTieredRepository.manifest(this.table, PartitionTieringServiceTest.JANUARY).orElseThrow();
		Assertions.assertEquals(archive.shardCount(), manifest.boundaries().size());
		Assertions.assertEquals("", manifest.boundaries().get(0));
		for (int shard = 1; shard < manifest.boundaries().size(); shard++) {
			Assertions.assertTrue(manifest.boundaries().get(shard - 1).compareTo(manifest.boundaries().get(shard)) < 0);
		}
		Assertions.assertEquals(52L, manifest.shards().stream().mapToLong(PartitionManifest.ShardSummary::rows).sum());
		// Every shard holds about the same rows (equi-depth over a skewed key).
		for (final PartitionManifest.ShardSummary shard : manifest.shards()) {
			Assertions.assertTrue((shard.rows() >= 4) && (shard.rows() <= 12), "shard rows: " + shard.rows());
			Assertions.assertTrue(Files.isRegularFile(this.pathOf(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, shard.shard()))));
			Assertions.assertTrue(Files.isRegularFile(this.pathOf(this.partitionTieredRepository.indexKey(this.table, PartitionTieringServiceTest.JANUARY, shard.shard()))));
		}
		// The table's own prefix applies to the manifest, the indexes and the blocks.
		Assertions.assertEquals("cold-tiering-full/tiered-monthly-snapshot/" + PartitionTieringServiceTest.JANUARY + "/manifest.json.gz", archive.manifestKey());
		Assertions.assertTrue(Files.isRegularFile(this.pathOf(archive.manifestKey())));
		Assertions.assertTrue(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, 0).startsWith("cold-tiering-full/tiered-monthly-snapshot/"));
		Assertions.assertFalse(Files.isDirectory(this.coldStorage.getRoot().resolve("cold-tiering/tiered-monthly-snapshot")));
		// The verification sampled rows back through range reads; hot reads cost none.
		final Long rangeReads = this.partitionTieredRepository.getRangeReads();
		Assertions.assertTrue(rangeReads > 0);
		Assertions.assertTrue(this.tieringServiceComponent
				.findByIdTiered(TieredMonthlySnapshot.class, new TieredMonthlySnapshotKey("a010", PartitionTieringServiceTest.JANUARY_MOMENT)).isPresent());
		Assertions.assertEquals(rangeReads, this.partitionTieredRepository.getRangeReads());
		// A redelivered shard request writes nothing.
		final Path blocks = this.pathOf(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, 0));
		final byte[] before = Files.readAllBytes(blocks);
		this.partitionTieringServiceComponent.exportShard(new ShardExportRequest(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY, 0, "",
				manifest.boundaries().size() > 1 ? manifest.boundaries().get(1) : null,
				this.partitionTieredRepository.loadIndex(this.table, PartitionTieringServiceTest.JANUARY, 0).orElseThrow().exportStartedAt()));
		Assertions.assertArrayEquals(before, Files.readAllBytes(blocks));
		// Nothing to sweep inside the hold.
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
	}

	@Test
	@DisplayName("Sweep after the hold keeps warm rows; cold reads rehydrate into the month; warm rows expire by row delete")
	public void testSweepAndColdReads() throws Exception {
		this.seedMonths();
		this.exportJanuary();
		// Touch a010 before the sweep so it is kept.
		TestHelper.moveClockBy(Duration.ofMinutes(90));
		final TieredMonthlySnapshotKey keptKey = new TieredMonthlySnapshotKey("a010", PartitionTieringServiceTest.JANUARY_MOMENT);
		this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, keptKey).orElseThrow();
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.repository.findById(keptKey).orElseThrow().getLastAccessedAt(), clock -> clock != null,
				TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		TestHelper.moveClockBy(Duration.ofMinutes(30));
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isPresent());
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.PARTITION_SWEEP));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::sweptAt).orElse(null), sweptAt -> sweptAt != null,
				TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		final PartitionArchive swept = this.archive().orElseThrow();
		Assertions.assertEquals(51L, swept.sweepDeleted());
		Assertions.assertEquals(1L, swept.sweepKept());
		final List<TieredMonthlySnapshot> remaining = this.januaryRows();
		Assertions.assertEquals(1, remaining.size());
		Assertions.assertEquals("a010", remaining.get(0).getOwnerId());
		Assertions.assertEquals(remaining.get(0).getUpdatedAt(), remaining.get(0).getArchivedAt());
		Assertions.assertEquals(7, this.repository.findAll().size(), "February rows untouched");
		// A cold read rehydrates the row into the January partition with its original columns.
		final Long rangeReads = this.partitionTieredRepository.getRangeReads();
		final TieredMonthlySnapshotKey coldKey = new TieredMonthlySnapshotKey("z007", PartitionTieringServiceTest.JANUARY_MOMENT);
		final TieredMonthlySnapshot rehydrated = this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, coldKey).orElseThrow();
		Assertions.assertEquals("z007@1", rehydrated.getData().get("marker"));
		Assertions.assertEquals(rehydrated.getUpdatedAt(), rehydrated.getArchivedAt());
		Assertions.assertNotNull(rehydrated.getLastAccessedAt());
		Assertions.assertTrue(this.partitionTieredRepository.getRangeReads() > rangeReads);
		Assertions.assertEquals(2, this.januaryRows().size());
		// A key in a gap or outside costs no block read and is a miss.
		final Long afterRehydration = this.partitionTieredRepository.getRangeReads();
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, new TieredMonthlySnapshotKey("zzzz", PartitionTieringServiceTest.JANUARY_MOMENT))
				.isEmpty());
		Assertions.assertEquals(afterRehydration, this.partitionTieredRepository.getRangeReads());
		// Newest: a hot February row wins over the cold January one without a range read.
		final Optional<TieredHit<TieredMonthlySnapshot>> newest = this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "a001"), null);
		Assertions.assertEquals(PartitionTieringServiceTest.FEBRUARY_MOMENT, newest.orElseThrow().hot().orElseThrow().getCachedAt());
		Assertions.assertEquals(afterRehydration, this.partitionTieredRepository.getRangeReads());
		// Newest as of January: the cold row, rehydrated.
		final Optional<TieredHit<TieredMonthlySnapshot>> asOfJanuary = this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class,
				Map.of("owner_id", "a001"), LocalDateTime.of(2026, 1, 31, 23, 59));
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, asOfJanuary.orElseThrow().hot().orElseThrow().getCachedAt());
		// Newest of an owner only in January: cold.
		Assertions.assertEquals("a020@1", this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "a020"), null).orElseThrow().hot()
				.orElseThrow().getData().get("marker"));
		// First in an interval: the January row of an owner with both months.
		final Optional<TieredHit<TieredMonthlySnapshot>> first = this.tieringServiceComponent.findFirstTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "a002"),
				LocalDateTime.of(2025, 12, 31, 0, 0), LocalDateTime.of(2026, 2, 28, 0, 0));
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, first.orElseThrow().hot().orElseThrow().getCachedAt());
		// Timeline mixes tiers, newest first.
		final List<TieredHit<TieredMonthlySnapshot>> timeline = this.tieringServiceComponent.findTimelineTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "a003"), null);
		Assertions.assertEquals(2, timeline.size());
		Assertions.assertEquals(PartitionTieringServiceTest.FEBRUARY_MOMENT, timeline.get(0).hot().orElseThrow().getCachedAt());
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, timeline.get(1).hot().orElseThrow().getCachedAt());
		// Batch by keys: two found, one missing.
		final List<TieredHit<TieredMonthlySnapshot>> batch = this.tieringServiceComponent.findAllByKeysTiered(TieredMonthlySnapshot.class,
				List.of(new TieredMonthlySnapshotKey("a030", PartitionTieringServiceTest.JANUARY_MOMENT), new TieredMonthlySnapshotKey("z001", PartitionTieringServiceTest.JANUARY_MOMENT),
						new TieredMonthlySnapshotKey("none", PartitionTieringServiceTest.JANUARY_MOMENT)));
		Assertions.assertEquals(2, batch.size());
		// Past the TTL the rehydrated and kept rows leave by the row-delete eviction; February stays.
		TestHelper.moveClockBy(Duration.ofHours(2));
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.januaryRows().size(), count -> count == 0, TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(6, this.repository.findAll().size());
		// Still readable cold afterwards.
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, keptKey).isPresent());
	}

	@Test
	@DisplayName("Stale partitions are read but not swept; excluded and open-ended partitions are never exported; corrupt blocks and newer formats are refused")
	public void testGuards() throws Exception {
		this.seedMonths();
		this.seed("old1", LocalDateTime.of(2025, 6, 1, 0, 0));
		this.exportJanuary();
		// A reload marks the partition stale: readable, not sweepable.
		this.partitionTieringServiceComponent.markStale(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY_MOMENT);
		Assertions.assertEquals(PartitionArchiveState.STALE, this.archive().orElseThrow().state());
		TestHelper.moveClockBy(Duration.ofDays(3));
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
		Assertions.assertFalse(this.partitionTieringServiceComponent.readMatching(this.table, PartitionTieringServiceTest.JANUARY, Map.of("owner_id", "a001")).isEmpty());
		// Export candidates once quiet: the stale January (re-export), then February; never _legacy
		// (excluded, although it holds a row), _plus (open-ended) nor the empty March.
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY, this.partitionTieringServiceComponent.findExportCandidate(this.table).orElseThrow().name());
		this.archiveRepository.markState(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY, PartitionArchiveState.VERIFIED,
				LocalDateTime.of(2026, 3, 1, 0, 0));
		final Optional<LeafPartition> candidate = this.partitionTieringServiceComponent.findExportCandidate(this.table);
		Assertions.assertEquals(ColdTieringTestConfiguration.MONTHLY_TABLE_NAME + "_2026_02", candidate.orElseThrow().name());
		this.archiveRepository.startExport(ColdTieringTestConfiguration.MONTHLY_TABLE, candidate.get().name(), candidate.get().rangeFrom(), candidate.get().rangeTo(),
				LocalDateTime.of(2026, 3, 1, 0, 0), List.of(""), null);
		Assertions.assertTrue(this.partitionTieringServiceComponent.findExportCandidate(this.table).isEmpty());
		// A corrupt block fails the read instead of serving another row.
		final Path blocks = this.pathOf(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, 0));
		final byte[] content = Files.readAllBytes(blocks);
		content[content.length / 2] ^= 0x7f;
		Files.write(blocks, content);
		this.partitionTieredRepository.forgetAll();
		final PartitionManifest manifest = this.partitionTieredRepository.manifest(this.table, PartitionTieringServiceTest.JANUARY).orElseThrow();
		final String shardZeroOwner = PartitionTieringServiceTest.JANUARY_OWNERS.stream().filter(owner -> manifest.shardOf(owner) == 0).findFirst().orElseThrow();
		Assertions.assertThrows(IntegrationException.class,
				() -> this.partitionTieringServiceComponent.readMatching(this.table, PartitionTieringServiceTest.JANUARY, Map.of("owner_id", shardZeroOwner)));
		// A manifest of a newer format is refused.
		this.partitionTieredRepository.writeManifest(this.table, new PartitionManifest(99, manifest.tableId(), manifest.partition(), manifest.rangeFrom(), manifest.rangeTo(),
				manifest.keyColumns(), manifest.shardStrategy(), manifest.boundaries(), manifest.shards(), manifest.rows(), manifest.exportStartedAt(), manifest.exportedAt()));
		Assertions.assertThrows(IntegrationException.class, () -> this.partitionTieredRepository.manifest(this.table, PartitionTieringServiceTest.JANUARY));
	}

	@Test
	@DisplayName("Schedules: export and sweep crons are registered for partition tables, backfill is not")
	public void testSchedules() throws Exception {
		Assertions.assertNotNull(this.batchServiceComponent.schedule(this.table, TieringBatch.EXPORT, "0 15 * * * *"));
		Assertions.assertNotNull(this.batchServiceComponent.schedule(this.table, TieringBatch.PARTITION_SWEEP, "0 0 22 * * *"));
		Assertions.assertTrue(this.batchServiceComponent.getScheduled().containsKey(TieringBatch.EXPORT.keySuffix(ColdTieringTestConfiguration.MONTHLY_TABLE)));
		Assertions.assertFalse(this.batchServiceComponent.run(this.table, TieringBatch.BACKFILL));
	}

}
