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
import org.coldis.library.service.tiering.TieredTableRegistry;
import org.coldis.library.service.tiering.TieringBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.tiering.model.LeafPartition;
import org.coldis.library.service.tiering.model.PartitionArchive;
import org.coldis.library.service.tiering.model.PartitionArchiveState;
import org.coldis.library.service.tiering.model.PartitionManifest;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.ShardExportRequest;
import org.coldis.library.service.tiering.model.ShardIndex;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.coldis.library.service.tiering.model.TieredTable;
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

	/** Owners with a January row (skewed prefixes, digit-only). */
	private static final List<String> JANUARY_OWNERS = new ArrayList<>();

	static {
		for (int index = 1; index <= 40; index++) {
			PartitionTieringServiceTest.JANUARY_OWNERS.add(String.format("1%05d", index));
		}
		for (int index = 1; index <= 12; index++) {
			PartitionTieringServiceTest.JANUARY_OWNERS.add(String.format("9%05d", index));
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

	@Autowired
	private TieredTableRegistry registry;

	@Autowired
	@Qualifier("tieredSnapshotTable")
	private TieredTable snapshotTable;

	@Autowired
	private DataSource dataSource;

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

	@Transactional
	private void seedWithWatermark(
			final String owner,
			final LocalDateTime cachedAt,
			final LocalDateTime updatedAt) {
		final TieredMonthlySnapshot snapshot = new TieredMonthlySnapshot(owner, cachedAt, new HashMap<>(Map.of("marker", owner + "@" + cachedAt.getMonthValue())));
		snapshot.setUpdatedAt(updatedAt);
		this.repository.save(snapshot);
	}

	@Transactional
	private void rewrite(
			final String owner,
			final LocalDateTime cachedAt,
			final String marker,
			final LocalDateTime updatedAt) {
		final TieredMonthlySnapshot snapshot = this.repository.findById(new TieredMonthlySnapshotKey(owner, cachedAt)).orElseThrow();
		snapshot.setData(new HashMap<>(Map.of("marker", marker)));
		snapshot.setUpdatedAt(updatedAt);
		this.repository.save(snapshot);
	}

	private void seedMonths() {
		for (final String owner : PartitionTieringServiceTest.JANUARY_OWNERS) {
			this.seed(owner, PartitionTieringServiceTest.JANUARY_MOMENT);
		}
		// Owners 100001..100005 have a February row too; 100099 only February.
		for (int index = 1; index <= 5; index++) {
			this.seed(String.format("1%05d", index), PartitionTieringServiceTest.FEBRUARY_MOMENT);
		}
		this.seed("100099", PartitionTieringServiceTest.FEBRUARY_MOMENT);
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
		final PartitionManifest manifest = this.partitionTieredRepository.manifest(archive.manifestKey()).orElseThrow();
		Assertions.assertEquals(archive.generation(), manifest.generation());
		Assertions.assertEquals(archive.shardCount(), manifest.boundaries().size());
		Assertions.assertEquals("", manifest.boundaries().get(0));
		for (int shard = 1; shard < manifest.boundaries().size(); shard++) {
			Assertions.assertTrue(manifest.boundaries().get(shard - 1).compareTo(manifest.boundaries().get(shard)) < 0);
		}
		Assertions.assertEquals(52L, manifest.shards().stream().mapToLong(PartitionManifest.ShardSummary::rows).sum());
		// Every shard holds about the same rows (equi-depth over a skewed key).
		for (final PartitionManifest.ShardSummary shard : manifest.shards()) {
			Assertions.assertTrue((shard.rows() >= 4) && (shard.rows() <= 12), "shard rows: " + shard.rows());
			Assertions.assertTrue(Files.isRegularFile(
					this.pathOf(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, archive.generation(), shard.shard()))));
			Assertions.assertTrue(Files.isRegularFile(
					this.pathOf(this.partitionTieredRepository.indexKey(this.table, PartitionTieringServiceTest.JANUARY, archive.generation(), shard.shard()))));
		}
		// The table's own prefix applies to the manifest, the indexes and the blocks.
		Assertions.assertEquals("cold-tiering-full/tiered-monthly-snapshot/" + PartitionTieringServiceTest.JANUARY + "/" + archive.generation() + "/manifest.json.gz",
				archive.manifestKey());
		Assertions.assertTrue(Files.isRegularFile(this.pathOf(archive.manifestKey())));
		Assertions.assertTrue(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, archive.generation(), 0)
				.startsWith("cold-tiering-full/tiered-monthly-snapshot/"));
		Assertions.assertFalse(Files.isDirectory(this.coldStorage.getRoot().resolve("cold-tiering/tiered-monthly-snapshot")));
		// The verification sampled rows back through range reads; hot reads cost none.
		final Long rangeReads = this.partitionTieredRepository.getRangeReads();
		Assertions.assertTrue(rangeReads > 0);
		Assertions.assertTrue(this.tieringServiceComponent
				.findByIdTiered(TieredMonthlySnapshot.class, new TieredMonthlySnapshotKey("100010", PartitionTieringServiceTest.JANUARY_MOMENT)).isPresent());
		Assertions.assertEquals(rangeReads, this.partitionTieredRepository.getRangeReads());
		// A redelivered shard request writes nothing and changes nothing on the record.
		final Path blocks = this.pathOf(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, archive.generation(), 0));
		final byte[] before = Files.readAllBytes(blocks);
		final Path indexPath = this.pathOf(this.partitionTieredRepository.indexKey(this.table, PartitionTieringServiceTest.JANUARY, archive.generation(), 0));
		final byte[] indexBefore = Files.readAllBytes(indexPath);
		this.partitionTieringServiceComponent.exportShard(new ShardExportRequest(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY,
				archive.generation(), 0, "", manifest.boundaries().size() > 1 ? manifest.boundaries().get(1) : null,
				this.partitionTieredRepository.loadIndex(this.table, PartitionTieringServiceTest.JANUARY, archive.generation(), 0).orElseThrow().exportStartedAt()));
		Assertions.assertArrayEquals(before, Files.readAllBytes(blocks));
		Assertions.assertArrayEquals(indexBefore, Files.readAllBytes(indexPath));
		final PartitionArchive afterRedelivery = this.archive().orElseThrow();
		Assertions.assertEquals(archive.shardsDone(), afterRedelivery.shardsDone());
		Assertions.assertEquals(archive.verifiedAt(), afterRedelivery.verifiedAt());
		Assertions.assertEquals(archive.manifestKey(), afterRedelivery.manifestKey());
		Assertions.assertEquals(archive.state(), afterRedelivery.state());
		Assertions.assertEquals(rangeReads, this.partitionTieredRepository.getRangeReads());
		// A prefix that skips the shard key, or names an unknown column, is refused.
		Assertions.assertThrows(IntegrationException.class,
				() -> this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class, Map.of("cached_at", PartitionTieringServiceTest.JANUARY_MOMENT), null));
		Assertions.assertThrows(IntegrationException.class, () -> this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class, Map.of("data", "x"), null));
		// Nothing to sweep inside the hold.
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
	}

	@Test
	@DisplayName("Sweep after the hold keeps warm rows; cold reads rehydrate into the month; warm rows expire by row delete")
	public void testSweepAndColdReads() throws Exception {
		this.seedMonths();
		this.exportJanuary();
		// Touch 100010 before the sweep so it is kept.
		TestHelper.moveClockBy(Duration.ofMinutes(90));
		final TieredMonthlySnapshotKey keptKey = new TieredMonthlySnapshotKey("100010", PartitionTieringServiceTest.JANUARY_MOMENT);
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
		Assertions.assertEquals("100010", remaining.get(0).getOwnerId());
		Assertions.assertEquals(remaining.get(0).getUpdatedAt(), remaining.get(0).getArchivedAt());
		Assertions.assertEquals(7, this.repository.findAll().size(), "February rows untouched");
		// A cold read rehydrates the row into the January partition with its original columns.
		final Long rangeReads = this.partitionTieredRepository.getRangeReads();
		final TieredMonthlySnapshotKey coldKey = new TieredMonthlySnapshotKey("900007", PartitionTieringServiceTest.JANUARY_MOMENT);
		final TieredMonthlySnapshot rehydrated = this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, coldKey).orElseThrow();
		Assertions.assertEquals("900007@1", rehydrated.getData().get("marker"));
		Assertions.assertEquals(rehydrated.getUpdatedAt(), rehydrated.getArchivedAt());
		Assertions.assertNotNull(rehydrated.getLastAccessedAt());
		Assertions.assertTrue(this.partitionTieredRepository.getRangeReads() > rangeReads);
		Assertions.assertEquals(2, this.januaryRows().size());
		// A key in a gap or outside costs no block read and is a miss.
		final Long afterRehydration = this.partitionTieredRepository.getRangeReads();
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, new TieredMonthlySnapshotKey("999999999", PartitionTieringServiceTest.JANUARY_MOMENT))
				.isEmpty());
		Assertions.assertEquals(afterRehydration, this.partitionTieredRepository.getRangeReads());
		// Newest: a hot February row wins over the cold January one without a range read.
		final Optional<TieredHit<TieredMonthlySnapshot>> newest = this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "100001"), null);
		Assertions.assertEquals(PartitionTieringServiceTest.FEBRUARY_MOMENT, newest.orElseThrow().hot().orElseThrow().getCachedAt());
		Assertions.assertEquals(afterRehydration, this.partitionTieredRepository.getRangeReads());
		// Newest as of January: the cold row, rehydrated.
		final Optional<TieredHit<TieredMonthlySnapshot>> asOfJanuary = this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class,
				Map.of("owner_id", "100001"), LocalDateTime.of(2026, 1, 31, 23, 59));
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, asOfJanuary.orElseThrow().hot().orElseThrow().getCachedAt());
		// Newest of an owner only in January: cold.
		Assertions.assertEquals("100020@1", this.tieringServiceComponent.findNewestTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "100020"), null).orElseThrow().hot()
				.orElseThrow().getData().get("marker"));
		// First in an interval: the January row of an owner with both months.
		final Optional<TieredHit<TieredMonthlySnapshot>> first = this.tieringServiceComponent.findFirstTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "100002"),
				LocalDateTime.of(2025, 12, 31, 0, 0), LocalDateTime.of(2026, 2, 28, 0, 0));
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, first.orElseThrow().hot().orElseThrow().getCachedAt());
		// Timeline mixes tiers, newest first.
		final List<TieredHit<TieredMonthlySnapshot>> timeline = this.tieringServiceComponent.findTimelineTiered(TieredMonthlySnapshot.class, Map.of("owner_id", "100003"), null);
		Assertions.assertEquals(2, timeline.size());
		Assertions.assertEquals(PartitionTieringServiceTest.FEBRUARY_MOMENT, timeline.get(0).hot().orElseThrow().getCachedAt());
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, timeline.get(1).hot().orElseThrow().getCachedAt());
		// Batch by keys: two found, one missing.
		final List<TieredHit<TieredMonthlySnapshot>> batch = this.tieringServiceComponent.findAllByIdsTiered(TieredMonthlySnapshot.class,
				List.of(new TieredMonthlySnapshotKey("100030", PartitionTieringServiceTest.JANUARY_MOMENT), new TieredMonthlySnapshotKey("900001", PartitionTieringServiceTest.JANUARY_MOMENT),
						new TieredMonthlySnapshotKey("999999998", PartitionTieringServiceTest.JANUARY_MOMENT)));
		Assertions.assertEquals(2, batch.size());
		Assertions.assertEquals("100030", batch.get(0).hot().orElseThrow().getOwnerId());
		Assertions.assertEquals("100030@1", batch.get(0).hot().orElseThrow().getData().get("marker"));
		Assertions.assertEquals("900001", batch.get(1).hot().orElseThrow().getOwnerId());
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY_MOMENT, batch.get(1).hot().orElseThrow().getCachedAt());
		// Past the TTL the rehydrated and kept rows leave by the row-delete eviction; February stays.
		TestHelper.moveClockBy(Duration.ofHours(2));
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.januaryRows().size(), count -> count == 0, TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(6, this.repository.findAll().size());
		// Still readable cold afterwards.
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredMonthlySnapshot.class, keptKey).isPresent());
		// A reload of a swept month is refused whatever it holds (a second delivery would orphan
		// the first): the swept keys stay served from the previous generation, left intact.
		final PartitionArchive beforeReload = this.archive().orElseThrow();
		this.seed("700001", PartitionTieringServiceTest.JANUARY_MOMENT);
		this.seed("700002", PartitionTieringServiceTest.JANUARY_MOMENT);
		this.partitionTieringServiceComponent.partitionLoaded(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY_MOMENT, 2L);
		Assertions.assertEquals(PartitionArchiveState.LOADED, this.archive().orElseThrow().state());
		Assertions.assertEquals(beforeReload.manifestKey(), this.archive().orElseThrow().manifestKey());
		Assertions.assertEquals("900003@1", this.tieringServiceComponent
				.findByIdTiered(TieredMonthlySnapshot.class, new TieredMonthlySnapshotKey("900003", PartitionTieringServiceTest.JANUARY_MOMENT)).orElseThrow().getData().get("marker"));
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.EXPORT));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::state).orElse(null),
				state -> state == PartitionArchiveState.NEEDS_ATTENTION, TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		final PartitionArchive refused = this.archive().orElseThrow();
		Assertions.assertEquals(beforeReload.manifestKey(), refused.manifestKey());
		Assertions.assertEquals(beforeReload.generation(), refused.generation());
		Assertions.assertEquals(beforeReload.rowCount(), refused.rowCount());
		Assertions.assertTrue(Files.isRegularFile(this.pathOf(beforeReload.manifestKey())));
		Assertions.assertEquals("900004@1", this.tieringServiceComponent
				.findByIdTiered(TieredMonthlySnapshot.class, new TieredMonthlySnapshotKey("900004", PartitionTieringServiceTest.JANUARY_MOMENT)).orElseThrow().getData().get("marker"));
		Assertions.assertTrue(this.partitionTieringServiceComponent.findExportCandidate(this.table).isEmpty());
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
		// An operator confirms: the export runs under a new generation over the hot rows only.
		this.partitionTieringServiceComponent.confirmReexport(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY_MOMENT);
		Assertions.assertEquals(PartitionArchiveState.LOADED, this.archive().orElseThrow().state());
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.EXPORT));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::state).orElse(null), state -> state == PartitionArchiveState.VERIFIED,
				TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		final PartitionArchive reexported = this.archive().orElseThrow();
		Assertions.assertNotEquals(beforeReload.generation(), reexported.generation());
		Assertions.assertNotEquals(beforeReload.manifestKey(), reexported.manifestKey());
		Assertions.assertEquals((long) this.januaryRows().size(), reexported.rowCount());
		Assertions.assertFalse(reexported.isReexportConfirmed());
		Assertions.assertNull(reexported.sweptAt());
		Assertions.assertTrue(Files.isRegularFile(this.pathOf(beforeReload.manifestKey())), "previous generation left in place");
		Assertions.assertTrue(Files.isRegularFile(this.pathOf(reexported.manifestKey())));
	}

	@Test
	@DisplayName("Data-loss guards: a row written after the export leaves the month STALE at the sweep; rows changed during an export end it STALE")
	public void testDataLossGuards() throws Exception {
		this.seedMonths();
		final PartitionArchive exported = this.exportJanuary();
		// A row rewritten after the export (same key, newer watermark, count unchanged) survives
		// the sweep with its new content and is not counted as deleted.
		this.rewrite("100005", PartitionTieringServiceTest.JANUARY_MOMENT, "rewritten", exported.exportStartedAt().plusMinutes(1));
		TestHelper.moveClockBy(Duration.ofHours(3));
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.PARTITION_SWEEP));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::sweptAt).orElse(null), sweptAt -> sweptAt != null,
				TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(51L, this.archive().orElseThrow().sweepDeleted());
		final TieredMonthlySnapshot survivor = this.repository.findById(new TieredMonthlySnapshotKey("100005", PartitionTieringServiceTest.JANUARY_MOMENT)).orElseThrow();
		Assertions.assertEquals("rewritten", survivor.getData().get("marker"));
		Assertions.assertNull(survivor.getArchivedAt(), "a rewritten row is not covered by the export, so never marked");
		Assertions.assertEquals(1, this.januaryRows().size());
		this.clean();
		this.coldStorage.reset();
		this.partitionTieredRepository.forgetAll();
		this.seedMonths();
		this.exportJanuary();
		// A row the archive does not cover: the sweep's re-count refuses, nothing deleted, STALE committed.
		this.seed("800001", PartitionTieringServiceTest.JANUARY_MOMENT);
		TestHelper.moveClockBy(Duration.ofHours(3));
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isPresent());
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.PARTITION_SWEEP));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::state).orElse(null), state -> state == PartitionArchiveState.STALE,
				TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(53, this.januaryRows().size());
		Assertions.assertNull(this.archive().orElseThrow().sweepCursor());
		Assertions.assertNull(this.archive().orElseThrow().sweptAt());
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
		// Still readable cold meanwhile.
		Assertions.assertFalse(this.partitionTieringServiceComponent.readMatching(this.table, PartitionTieringServiceTest.JANUARY, Map.of("owner_id", "100001")).isEmpty());
		// Rows changed during an export (a watermark after its start) end it STALE, never VERIFIED.
		this.clean();
		this.coldStorage.reset();
		this.partitionTieredRepository.forgetAll();
		this.seedMonths();
		this.seedWithWatermark("800002", PartitionTieringServiceTest.JANUARY_MOMENT, DateTimeHelper.getCurrentLocalDateTime().plusMinutes(1));
		this.partitionTieringServiceComponent.partitionLoaded(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY_MOMENT, null);
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.MONTHLY_TABLE, TieringBatch.EXPORT));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archive().map(PartitionArchive::state).orElse(null), state -> state == PartitionArchiveState.STALE,
				TestHelper.REGULAR_WAIT * 8, TestHelper.SHORT_WAIT));
		Assertions.assertNotNull(this.archive().orElseThrow().manifestKey());
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
	}

	@Test
	@DisplayName("Delete: a row of an archived partition is refused, a row of a hot partition is deleted; a date partition column is rejected")
	public void testDeleteAndValidation() throws Exception {
		this.seedMonths();
		final TieredMonthlySnapshotKey februaryKey = new TieredMonthlySnapshotKey("100099", PartitionTieringServiceTest.FEBRUARY_MOMENT);
		this.tieringServiceComponent.delete(this.tieringServiceComponent.keyOf(TieredMonthlySnapshot.class, februaryKey));
		Assertions.assertTrue(this.repository.findById(februaryKey).isEmpty());
		this.exportJanuary();
		final TieredMonthlySnapshotKey januaryKey = new TieredMonthlySnapshotKey("100001", PartitionTieringServiceTest.JANUARY_MOMENT);
		Assertions.assertThrows(IntegrationException.class, () -> this.tieringServiceComponent.delete(this.tieringServiceComponent.keyOf(TieredMonthlySnapshot.class, januaryKey)));
		Assertions.assertTrue(this.repository.findById(januaryKey).isPresent());
		// Only a timestamp (without time zone) partition column is accepted.
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS tiered_daily_snapshot CASCADE");
		jdbcTemplate.execute("CREATE TABLE tiered_daily_snapshot (owner_id varchar(255) NOT NULL, cached_on date NOT NULL, data jsonb, created_at timestamptz NOT NULL,"
				+ " updated_at timestamp(6) NOT NULL, last_accessed_at timestamp(6), archived_at timestamp(6), PRIMARY KEY (owner_id, cached_on)) PARTITION BY RANGE (cached_on)");
		jdbcTemplate.execute("DROP TABLE IF EXISTS tiered_bad_shard CASCADE");
		jdbcTemplate.execute("CREATE TABLE tiered_bad_shard (owner_at timestamp(6) NOT NULL, cached_at timestamp(6) NOT NULL, data jsonb, created_at timestamptz NOT NULL,"
				+ " updated_at timestamp(6) NOT NULL, last_accessed_at timestamp(6), archived_at timestamp(6), PRIMARY KEY (owner_at, cached_at)) PARTITION BY RANGE (cached_at)");
		try {
			Assertions.assertThrows(IntegrationException.class,
					() -> this.registry.validate(new PartitionTieredTable("tiered-daily-snapshot", "tiered_daily_snapshot", List.of("owner_id", "cached_on"))));
			// The shard key must be digit-only text or an integer; the partition column the last key column.
			Assertions.assertThrows(IntegrationException.class,
					() -> this.registry.validate(new PartitionTieredTable("tiered-bad-shard", "tiered_bad_shard", List.of("owner_at", "cached_at"))));
			Assertions.assertThrows(IntegrationException.class, () -> new PartitionTieredTable("tiered-bad-partition", ColdTieringTestConfiguration.MONTHLY_TABLE_NAME,
					List.of("owner_id", "cached_at")).withPartitionColumn("owner_id"));
			this.registry.validate(this.table);
		}
		finally {
			jdbcTemplate.execute("DROP TABLE IF EXISTS tiered_daily_snapshot CASCADE");
			jdbcTemplate.execute("DROP TABLE IF EXISTS tiered_bad_shard CASCADE");
		}
		// A shard key that is not digit-only fails the export loudly before anything is written.
		this.seed("bad-1", PartitionTieringServiceTest.FEBRUARY_MOMENT);
		this.partitionTieringServiceComponent.partitionLoaded(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.FEBRUARY_MOMENT, null);
		final LeafPartition february = this.partitionTieringServiceComponent.findExportCandidate(this.table).orElseThrow();
		Assertions.assertEquals(ColdTieringTestConfiguration.MONTHLY_TABLE_NAME + "_2026_02", february.name());
		Assertions.assertThrows(IntegrationException.class, () -> this.partitionTieringServiceComponent.startExport(this.table, february));
		Assertions.assertTrue(this.archiveRepository.find(ColdTieringTestConfiguration.MONTHLY_TABLE, february.name()).orElseThrow().manifestKey() == null);
	}

	@Test
	@DisplayName("Stale partitions are read but not swept; excluded and open-ended partitions are never exported; corrupt blocks and newer formats are refused")
	public void testGuards() throws Exception {
		this.seedMonths();
		this.seed("500001", LocalDateTime.of(2025, 6, 1, 0, 0));
		this.exportJanuary();
		// A reload marks the partition stale: readable, not sweepable.
		this.partitionTieringServiceComponent.markStale(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY_MOMENT);
		Assertions.assertEquals(PartitionArchiveState.STALE, this.archive().orElseThrow().state());
		TestHelper.moveClockBy(Duration.ofDays(3));
		Assertions.assertTrue(this.partitionTieringServiceComponent.findSweepCandidate(this.table).isEmpty());
		Assertions.assertFalse(this.partitionTieringServiceComponent.readMatching(this.table, PartitionTieringServiceTest.JANUARY, Map.of("owner_id", "100001")).isEmpty());
		// Export candidates once quiet: the stale January (re-export), then February; never _legacy
		// (excluded, although it holds a row), _plus (open-ended) nor the empty March.
		Assertions.assertEquals(PartitionTieringServiceTest.JANUARY, this.partitionTieringServiceComponent.findExportCandidate(this.table).orElseThrow().name());
		this.archiveRepository.markState(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY, PartitionArchiveState.VERIFIED,
				LocalDateTime.of(2026, 3, 1, 0, 0));
		final Optional<LeafPartition> candidate = this.partitionTieringServiceComponent.findExportCandidate(this.table);
		Assertions.assertEquals(ColdTieringTestConfiguration.MONTHLY_TABLE_NAME + "_2026_02", candidate.orElseThrow().name());
		this.archiveRepository.startExport(ColdTieringTestConfiguration.MONTHLY_TABLE, candidate.get().name(), candidate.get().rangeFrom(), candidate.get().rangeTo(),
				LocalDateTime.of(2026, 3, 1, 0, 0), "g0", List.of(""));
		Assertions.assertTrue(this.partitionTieringServiceComponent.findExportCandidate(this.table).isEmpty());
		// A corrupt block fails the read instead of serving another row.
		final PartitionArchive exported = this.archive().orElseThrow();
		final Path blocks = this.pathOf(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, exported.generation(), 0));
		final byte[] content = Files.readAllBytes(blocks);
		content[content.length / 2] ^= 0x7f;
		Files.write(blocks, content);
		this.partitionTieredRepository.forgetAll();
		final PartitionManifest manifest = this.partitionTieredRepository.manifest(exported.manifestKey()).orElseThrow();
		final String shardZeroOwner = PartitionTieringServiceTest.JANUARY_OWNERS.stream().filter(owner -> manifest.shardOf(owner) == 0).findFirst().orElseThrow();
		Assertions.assertThrows(IntegrationException.class,
				() -> this.partitionTieringServiceComponent.readMatching(this.table, PartitionTieringServiceTest.JANUARY, Map.of("owner_id", shardZeroOwner)));
		// A manifest of a newer format is refused.
		this.partitionTieredRepository.writeManifest(this.table, new PartitionManifest(99, manifest.tableId(), manifest.partition(), manifest.generation(),
				manifest.rangeFrom(), manifest.rangeTo(), manifest.keyColumns(), manifest.shardStrategy(), manifest.boundaries(), manifest.shards(), manifest.rows(),
				manifest.exportStartedAt(), manifest.exportedAt()));
		Assertions.assertThrows(IntegrationException.class, () -> this.partitionTieredRepository.manifest(exported.manifestKey()));
		// Verification failures: a row that appears in a shard's range after it was written fails
		// the count check; a sample that does not read back fails the sample check. Neither
		// touches the record.
		final ShardIndex shardZero = this.partitionTieredRepository.loadIndex(this.table, PartitionTieringServiceTest.JANUARY, exported.generation(), 0).orElseThrow();
		final ShardExportRequest shardZeroRequest = new ShardExportRequest(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY,
				exported.generation(), 0, "", manifest.boundaries().size() > 1 ? manifest.boundaries().get(1) : null, shardZero.exportStartedAt());
		Assertions.assertThrows(IntegrationException.class, () -> this.partitionTieringServiceComponent.verifyShard(this.table, shardZeroRequest, shardZero,
				Map.of(List.of(shardZeroOwner, "2026-01-10T00:00:00.000000"), "{\"owner_id\":\"" + shardZeroOwner + "\",\"other\":true}")));
		this.seed("100000", PartitionTieringServiceTest.JANUARY_MOMENT);
		Assertions.assertThrows(IntegrationException.class, () -> this.partitionTieringServiceComponent.verifyShard(this.table, shardZeroRequest, shardZero, Map.of()));
		final PartitionArchive untouched = this.archive().orElseThrow();
		Assertions.assertEquals(exported.shardsDone(), untouched.shardsDone());
		Assertions.assertEquals(exported.verifiedAt(), untouched.verifiedAt());
		Assertions.assertEquals(exported.manifestKey(), untouched.manifestKey());
		Assertions.assertEquals(exported.state(), untouched.state());
		// An empty shard: an index with no block, a miss without a range read.
		this.partitionTieringServiceComponent.exportShard(new ShardExportRequest(ColdTieringTestConfiguration.MONTHLY_TABLE, PartitionTieringServiceTest.JANUARY,
				exported.generation(), 99, "99999", null, "empty"));
		final ShardIndex empty = this.partitionTieredRepository.loadIndex(this.table, PartitionTieringServiceTest.JANUARY, exported.generation(), 99).orElseThrow();
		Assertions.assertEquals(0L, empty.rows());
		Assertions.assertTrue(empty.blocks().isEmpty());
		Assertions.assertEquals(0L, this.coldStorage.head(this.partitionTieredRepository.blocksKey(this.table, PartitionTieringServiceTest.JANUARY, exported.generation(), 99))
				.orElseThrow().sizeBytes());
		final Long rangeReads = this.partitionTieredRepository.getRangeReads();
		Assertions.assertTrue(this.partitionTieredRepository.readMatching(this.table, PartitionTieringServiceTest.JANUARY, empty, List.of("999999999")).isEmpty());
		Assertions.assertEquals(rangeReads, this.partitionTieredRepository.getRangeReads());
		Assertions.assertEquals(exported.shardsDone(), this.archive().orElseThrow().shardsDone());
	}

	@Test
	@DisplayName("Schedules: export and sweep crons are registered for partition tables, backfill is not")
	public void testSchedules() throws Exception {
		// The startup decision itself: the monthly table's properties carry crons for the export
		// and the sweep (a date that never comes round in a test run), none for the row batches.
		this.batchServiceComponent.cancelAll();
		this.batchServiceComponent.afterSingletonsInstantiated();
		final Map<String, String> scheduled = this.batchServiceComponent.getScheduled();
		Assertions.assertEquals("0 0 0 29 2 *", scheduled.get(TieringBatch.EXPORT.keySuffix(ColdTieringTestConfiguration.MONTHLY_TABLE)));
		Assertions.assertEquals("0 0 0 29 2 *", scheduled.get(TieringBatch.PARTITION_SWEEP.keySuffix(ColdTieringTestConfiguration.MONTHLY_TABLE)));
		Assertions.assertFalse(scheduled.containsKey(TieringBatch.BACKFILL.keySuffix(ColdTieringTestConfiguration.MONTHLY_TABLE)));
		Assertions.assertFalse(scheduled.containsKey(TieringBatch.EVICTION.keySuffix(ColdTieringTestConfiguration.MONTHLY_TABLE)));
		Assertions.assertFalse(scheduled.containsKey(TieringBatch.EXPORT.keySuffix(ColdTieringTestConfiguration.SNAPSHOT_TABLE)));
		Assertions.assertFalse(scheduled.containsKey(TieringBatch.PARTITION_SWEEP.keySuffix(ColdTieringTestConfiguration.SNAPSHOT_TABLE)));
		// The properties enable and schedule every batch on both tables: the split is the code's.
		Assertions.assertFalse(this.batchServiceComponent.run(this.table, TieringBatch.BACKFILL));
		Assertions.assertFalse(this.batchServiceComponent.run(this.snapshotTable, TieringBatch.EXPORT));
		Assertions.assertFalse(this.batchServiceComponent.run(this.snapshotTable, TieringBatch.PARTITION_SWEEP));
	}

}
