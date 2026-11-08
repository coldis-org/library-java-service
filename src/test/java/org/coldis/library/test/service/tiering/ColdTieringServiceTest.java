package org.coldis.library.test.service.tiering;

import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.service.tiering.ColdRow;
import org.coldis.library.service.tiering.ColdTieringBatchServiceComponent;
import org.coldis.library.service.tiering.ColdTieringServiceComponent;
import org.coldis.library.service.tiering.FolderColdStorageRepository;
import org.coldis.library.service.tiering.TieredRowKey;
import org.coldis.library.service.tiering.TieringBatch;
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
 * Cold tiering: write-through, eviction, cold reads with and without rehydration, backfill,
 * "newest on or before" reads and the library-owned scheduling, through consumer repositories in
 * another package, on the local-folder cold storage.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ColdTieringServiceTest extends ContainerTestHelper {

	/** Batch record key of the archive table's scheduled eviction. */
	private static final String ARCHIVE_EVICTION_KEY = BatchService.BATCH_KEY_PREFIX + TieringBatch.EVICTION.keySuffix(ColdTieringTestConfiguration.ARCHIVE_TABLE);

	/** Tiering component. */
	@Autowired
	private ColdTieringServiceComponent tieringServiceComponent;

	/** Batch component. */
	@Autowired
	private ColdTieringBatchServiceComponent batchServiceComponent;

	/** Cold storage (a local folder under the temporary directory). */
	@Autowired
	private FolderColdStorageRepository coldStorage;

	/** Snapshot repository. */
	@Autowired
	private TieredSnapshotRepository snapshotRepository;

	/** Archive entry repository. */
	@Autowired
	private TieredArchiveEntryRepository archiveEntryRepository;

	/** Key-value service (batch records). */
	@Autowired
	private KeyValueServiceComponent keyValueService;

	/**
	 * Clean state.
	 */
	@BeforeEach
	public void beforeEach() {
		TestHelper.cleanClock();
		this.clean();
		this.coldStorage.reset();
	}

	/**
	 * Empties both tables and forgets the batch records: a previous test moved the clock forward
	 * while a batch ran, and a record finished "in the future" would not run again until the clock
	 * gets there.
	 */
	@Transactional
	private void clean() {
		this.snapshotRepository.deleteAll();
		this.archiveEntryRepository.deleteAll();
		for (final String tableId : List.of(ColdTieringTestConfiguration.SNAPSHOT_TABLE, ColdTieringTestConfiguration.ARCHIVE_TABLE)) {
			for (final TieringBatch batch : TieringBatch.values()) {
				this.keyValueService.getRepository().deleteById(BatchService.BATCH_KEY_PREFIX + batch.keySuffix(tableId));
			}
		}
	}

	/**
	 * Saves a snapshot without the tiering hook (a row the backfill has to find).
	 *
	 * @param snapshot Snapshot.
	 */
	@Transactional
	private void seed(
			final TieredSnapshot snapshot) {
		this.snapshotRepository.save(snapshot);
	}

	/**
	 * Restores the clock.
	 */
	@AfterEach
	public void afterEach() {
		TestHelper.cleanClock();
	}

	/**
	 * Snapshot key.
	 *
	 * @param  snapshotKey Entity key.
	 * @return             The row key.
	 */
	private TieredRowKey rowKey(
			final TieredSnapshotKey snapshotKey) {
		return this.tieringServiceComponent.keyOf(ColdTieringTestConfiguration.SNAPSHOT_TABLE,
				Map.of("owner_id", snapshotKey.getOwnerId(), "cached_at", snapshotKey.getCachedAt()));
	}

	/**
	 * Writes a snapshot the way a consumer does: JPA save, then the tiering hook, one transaction.
	 *
	 * @param  snapshot Snapshot.
	 * @return          The key.
	 */
	@Transactional
	private TieredSnapshotKey write(
			final TieredSnapshot snapshot) {
		this.snapshotRepository.save(snapshot);
		final TieredSnapshotKey key = new TieredSnapshotKey(snapshot.getOwnerId(), snapshot.getCachedAt());
		this.tieringServiceComponent.afterWrite(this.rowKey(key));
		return key;
	}

	/**
	 * Writes an archive entry with the tiering hook.
	 *
	 * @param entry Entry.
	 */
	@Transactional
	private void write(
			final TieredArchiveEntry entry) {
		this.archiveEntryRepository.save(entry);
		this.tieringServiceComponent.afterWrite(this.tieringServiceComponent.keyOf(ColdTieringTestConfiguration.ARCHIVE_TABLE, Map.of("id", entry.getId())));
	}

	/**
	 * Waits until a snapshot is archived (write-through or backfill).
	 *
	 * @param  key       Key.
	 * @throws Exception If the wait fails.
	 */
	private void waitArchived(
			final TieredSnapshotKey key) throws Exception {
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.snapshotRepository.findById(key).map(TieredSnapshot::getArchivedAt).orElse(null),
				archivedAt -> archivedAt != null, TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
	}

	/**
	 * Evicts a table and waits for a snapshot to leave the hot table.
	 *
	 * @param  key Key.
	 * @throws Exception If the batch cannot start or the wait fails.
	 */
	private void evictSnapshots(
			final TieredSnapshotKey key) throws Exception {
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.SNAPSHOT_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.snapshotRepository.findById(key).isEmpty(), evicted -> evicted, TestHelper.REGULAR_WAIT * 4,
				TestHelper.SHORT_WAIT));
	}

	/**
	 * Write-through archive, eviction past the TTL, cold read with rehydration, sliding TTL touch,
	 * and a miss when the object is gone.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: archived on write, evicted past the TTL, rehydrated on read, touched when stale")
	public void testWriteEvictRehydrateTouch() throws Exception {
		final TieredSnapshotKey key = this.write(new TieredSnapshot("owner-1", LocalDateTime.of(2026, 1, 10, 12, 0), new HashMap<>(Map.of("marker", "one"))));
		this.waitArchived(key);
		final String objectKey = this.tieringServiceComponent.objectKeyOf(this.rowKey(key));
		Assertions.assertEquals("cold-tiering/tiered-snapshot/owner-1/2026-01-10T12:00:00.000000.json.gz", objectKey);
		Assertions.assertTrue(this.coldStorage.load(objectKey).isPresent());
		Assertions.assertTrue(Files.isRegularFile(this.coldStorage.getRoot().resolve(objectKey)));
		Assertions.assertTrue(Files.readString(this.coldStorage.getRoot().resolve(objectKey + FolderColdStorageRepository.METADATA_SUFFIX)).contains("table=tiered-snapshot"));

		// Past the TTL and the safety margin, the archived row is evicted.
		TestHelper.moveClockBy(Duration.ofDays(20));
		this.evictSnapshots(key);

		// A read fetches the object and rehydrates the row with its original columns.
		final TieredSnapshot rehydrated = this.snapshotRepository.findByIdTiered(key).orElseThrow();
		Assertions.assertEquals("one", rehydrated.getData().get("marker"));
		Assertions.assertNotNull(rehydrated.getLastAccessedAt());
		Assertions.assertNotNull(rehydrated.getArchivedAt());
		Assertions.assertTrue(rehydrated.getUpdatedAt().isBefore(DateTimeHelper.getCurrentLocalDateTime().minusDays(19)));
		final LocalDateTime rehydratedClock = rehydrated.getLastAccessedAt();

		// A read past the touch threshold extends the clock.
		TestHelper.moveClockBy(Duration.ofDays(2));
		this.snapshotRepository.findByIdTiered(key).orElseThrow();
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.snapshotRepository.findById(key).orElseThrow().getLastAccessedAt(),
				clock -> clock.isAfter(rehydratedClock.plusDays(1)), TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));

		// Evicted again and the object gone: a miss, not an error.
		TestHelper.moveClockBy(Duration.ofDays(20));
		this.evictSnapshots(key);
		this.coldStorage.remove(objectKey);
		Assertions.assertTrue(this.snapshotRepository.findByIdTiered(key).isEmpty());
	}

	/**
	 * A rewrite after the archive drops the mark, is re-archived with the new content, and only then
	 * evicted.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: a rewrite is re-archived before it can be evicted")
	public void testRewriteIsReArchived() throws Exception {
		final TieredSnapshotKey key = this.write(new TieredSnapshot("owner-2", LocalDateTime.of(2026, 1, 11, 12, 0), new HashMap<>(Map.of("marker", "first"))));
		this.waitArchived(key);
		final LocalDateTime firstArchive = this.snapshotRepository.findById(key).orElseThrow().getArchivedAt();

		TestHelper.moveClockBy(Duration.ofHours(1));
		final TieredSnapshot rewritten = this.snapshotRepository.findById(key).orElseThrow();
		rewritten.setData(new HashMap<>(Map.of("marker", "second")));
		rewritten.setUpdatedAt(DateTimeHelper.getCurrentLocalDateTime());
		this.write(rewritten);
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.snapshotRepository.findById(key).orElseThrow().getArchivedAt(),
				archivedAt -> (archivedAt != null) && archivedAt.isAfter(firstArchive), TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));

		TestHelper.moveClockBy(Duration.ofDays(20));
		this.evictSnapshots(key);
		Assertions.assertEquals("second", this.snapshotRepository.findByIdTiered(key).orElseThrow().getData().get("marker"));
	}

	/**
	 * The backfill archives rows written without the hook, resuming across chunks.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: the backfill archives every unarchived row")
	public void testBackfill() throws Exception {
		final List<TieredSnapshotKey> keys = List.of(new TieredSnapshotKey("owner-3", LocalDateTime.of(2026, 1, 1, 0, 0)),
				new TieredSnapshotKey("owner-3", LocalDateTime.of(2026, 1, 2, 0, 0)), new TieredSnapshotKey("owner-4", LocalDateTime.of(2026, 1, 3, 0, 0)),
				new TieredSnapshotKey("owner-5", LocalDateTime.of(2026, 1, 4, 0, 0)), new TieredSnapshotKey("owner-6", LocalDateTime.of(2026, 1, 5, 0, 0)));
		for (final TieredSnapshotKey key : keys) {
			this.seed(new TieredSnapshot(key.getOwnerId(), key.getCachedAt(), new HashMap<>(Map.of("marker", key.getOwnerId()))));
		}
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.SNAPSHOT_TABLE, TieringBatch.BACKFILL));
		for (final TieredSnapshotKey key : keys) {
			this.waitArchived(key);
		}
		Assertions.assertEquals(keys.size(), this.coldStorage.list("cold-tiering/tiered-snapshot/").size());
	}

	/**
	 * "Newest on or before" over hot and cold rows, through the repository fragment.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: newest on or before a moment, hot or cold")
	public void testNewestAsOf() throws Exception {
		final LocalDateTime first = LocalDateTime.of(2026, 2, 1, 10, 0);
		final LocalDateTime second = LocalDateTime.of(2026, 2, 5, 10, 0);
		final LocalDateTime third = LocalDateTime.of(2026, 2, 9, 10, 0);
		final TieredSnapshotKey firstKey = this.write(new TieredSnapshot("owner-7", first, new HashMap<>(Map.of("marker", "first"))));
		final TieredSnapshotKey secondKey = this.write(new TieredSnapshot("owner-7", second, new HashMap<>(Map.of("marker", "second"))));
		this.waitArchived(firstKey);
		this.waitArchived(secondKey);
		TestHelper.moveClockBy(Duration.ofDays(20));
		this.evictSnapshots(firstKey);
		this.evictSnapshots(secondKey);
		// A hot row written after the archive, not archived yet.
		this.seed(new TieredSnapshot("owner-7", third, new HashMap<>(Map.of("marker", "third"))));

		final Map<String, Object> prefix = Map.of("owner_id", "owner-7");
		Assertions.assertEquals("third", this.snapshotRepository.findNewestTiered(prefix, null).orElseThrow().getData().get("marker"));
		Assertions.assertEquals("second", this.snapshotRepository.findNewestTiered(prefix, second).orElseThrow().getData().get("marker"));
		Assertions.assertEquals("first", this.snapshotRepository.findNewestTiered(prefix, second.minusDays(1)).orElseThrow().getData().get("marker"));
		Assertions.assertTrue(this.snapshotRepository.findNewestTiered(prefix, first.minusDays(1)).isEmpty());
		Assertions.assertTrue(this.snapshotRepository.findNewestTiered(Map.of("owner_id", "nobody"), null).isEmpty());
		// The cold ones were rehydrated by the reads.
		Assertions.assertTrue(this.snapshotRepository.findById(firstKey).isPresent());
		Assertions.assertTrue(this.snapshotRepository.findById(secondKey).isPresent());
	}

	/**
	 * A table with rehydration NONE (property override), a single id, a text payload and an age
	 * from the last write: archived on write, evicted by the library's own schedule, readable cold
	 * only, never hot again.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Archive entry: no rehydration, cold read only, evicted by the scheduled batch")
	public void testNoRehydrationAndScheduledEviction() throws Exception {
		this.write(new TieredArchiveEntry(42L, "forty-two"));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archiveEntryRepository.findById(42L).map(TieredArchiveEntry::getArchivedAt).orElse(null),
				archivedAt -> archivedAt != null, TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		Assertions.assertEquals("cold-tiering/tiered-archive-entry/0000000000000000042.json.gz",
				this.tieringServiceComponent.objectKeyOf(this.tieringServiceComponent.keyOf(ColdTieringTestConfiguration.ARCHIVE_TABLE, Map.of("id", 42L))));

		// Evicted by the eviction scheduled every second (TTL one day, no safety margin).
		TestHelper.moveClockBy(Duration.ofDays(2));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archiveEntryRepository.findById(42L).isEmpty(), evicted -> evicted, TestHelper.REGULAR_WAIT * 8,
				TestHelper.SHORT_WAIT));
		Assertions.assertTrue(this.keyValueService.getRepository().findById(ColdTieringServiceTest.ARCHIVE_EVICTION_KEY).isPresent());

		// Cold only: the tiered read is a miss, the cold row holds the whole row, the hot table stays empty.
		Assertions.assertTrue(this.archiveEntryRepository.findByIdTiered(42L).isEmpty());
		final Optional<ColdRow> coldRow = this.archiveEntryRepository.findColdRow(42L);
		Assertions.assertTrue(coldRow.get().rowJson().contains("\"content\":\"forty-two\""));
		Assertions.assertTrue(this.archiveEntryRepository.findById(42L).isEmpty());
	}

}
