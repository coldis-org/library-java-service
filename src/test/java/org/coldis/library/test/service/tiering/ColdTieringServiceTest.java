package org.coldis.library.test.service.tiering;

import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.persistence.keyvalue.KeyValue;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.batch.BatchExecutor;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.ColdTieringBatchServiceComponent;
import org.coldis.library.service.tiering.ColdTieringProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.ColdTieringServiceComponent;
import org.coldis.library.service.tiering.TieredHit;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.TieringBatch;
import org.coldis.library.service.tiering.repository.FolderColdStorageRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cold tiering: write-through, eviction (and what eviction must leave alone), cold reads with and
 * without rehydration, backfill, "newest on or before" reads, disabled tables and the
 * library-owned scheduling, through consumer entities in another package, on the local-folder
 * cold storage.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ColdTieringServiceTest extends ContainerTestHelper {

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

	/** Archive table definition. */
	@Autowired
	@Qualifier("tieredArchiveEntryTable")
	private TieredTable archiveTable;

	/** Event repository. */
	@Autowired
	private TieredEventRepository eventRepository;

	/** Tiering properties (one test slows a run down by overriding a table at runtime). */
	@Autowired
	private ColdTieringProperties properties;

	/** Batch service. */
	@Autowired
	private BatchService batchService;

	/**
	 * Clean state.
	 */
	@BeforeEach
	public void beforeEach() {
		TestHelper.cleanClock();
		this.batchServiceComponent.cancelAll();
		this.clean();
		this.coldStorage.reset();
	}

	/**
	 * Restores the clock and the schedules.
	 */
	@AfterEach
	public void afterEach() {
		TestHelper.cleanClock();
		this.batchServiceComponent.cancelAll();
	}

	/**
	 * Empties both tables and forgets the batch records: a previous test moved the clock forward
	 * while a batch ran, and a record finished "in the future" would not run again until the clock
	 * gets there.
	 */
	@Transactional
	private void clean() {
		this.truncateTables("tiered_snapshot", "tiered_archive_entry", "tiered_event");
		for (final String tableId : List.of(ColdTieringTestConfiguration.SNAPSHOT_TABLE, ColdTieringTestConfiguration.ARCHIVE_TABLE,
				ColdTieringTestConfiguration.EVENT_TABLE)) {
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
	 * Snapshot key.
	 *
	 * @param  snapshotKey Entity key.
	 * @return             The row key.
	 */
	private TieredRowKey rowKey(
			final TieredSnapshotKey snapshotKey) {
		return this.tieringServiceComponent.keyOf(TieredSnapshot.class, snapshotKey);
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
	 * Writes an archive entry with the tiering hook of one of the archive table's definitions.
	 *
	 * @param entry   Entry.
	 * @param tableId Definition id.
	 */
	@Transactional
	private void write(
			final TieredArchiveEntry entry,
			final String tableId) {
		this.archiveEntryRepository.save(entry);
		this.tieringServiceComponent.afterWrite(this.tieringServiceComponent.keyOf(tableId, Map.of("id", entry.getId())));
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
	 * @param  key       Key.
	 * @throws Exception If the batch cannot start or the wait fails.
	 */
	private void evictSnapshots(
			final TieredSnapshotKey key) throws Exception {
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.SNAPSHOT_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.snapshotRepository.findById(key).isEmpty(), evicted -> evicted, TestHelper.REGULAR_WAIT * 4,
				TestHelper.SHORT_WAIT));
	}

	/**
	 * Write-through archive, eviction past the TTL (and what it must keep), cold read with
	 * rehydration, sliding TTL touch, and a miss when the object is gone.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: archived on write, evicted past the TTL, rehydrated on read, touched when stale")
	public void testWriteEvictRehydrateTouch() throws Exception {
		final TieredSnapshotKey key = this.write(new TieredSnapshot("owner-1", LocalDateTime.of(2026, 1, 10, 12, 0), new HashMap<>(Map.of("marker", "one"))));
		this.waitArchived(key);
		final TieredSnapshot archived = this.snapshotRepository.findById(key).orElseThrow();
		Assertions.assertEquals(archived.getUpdatedAt(), archived.getArchivedAt());
		final String objectKey = this.tieringServiceComponent.objectKeyOf(this.rowKey(key));
		Assertions.assertEquals("cold-tiering/tiered-snapshot/owner-1/2026-01-10T12:00:00.000000.json.gz", objectKey);
		Assertions.assertTrue(this.coldStorage.load(objectKey).isPresent());
		Assertions.assertTrue(Files.isRegularFile(this.coldStorage.getRoot().resolve(objectKey)));
		Assertions.assertTrue(Files.readString(this.coldStorage.getRoot().resolve(objectKey + FolderColdStorageRepository.METADATA_SUFFIX)).contains("table=tiered-snapshot"));

		// Rows the eviction must keep: never archived; archived, then rewritten by a path that
		// bumped the watermark but skipped the hook.
		final TieredSnapshotKey neverArchivedKey = new TieredSnapshotKey("owner-1", LocalDateTime.of(2026, 1, 11, 12, 0));
		this.seed(new TieredSnapshot(neverArchivedKey.getOwnerId(), neverArchivedKey.getCachedAt(), new HashMap<>(Map.of("marker", "never"))));
		final TieredSnapshotKey rewrittenKey = this.write(new TieredSnapshot("owner-1", LocalDateTime.of(2026, 1, 12, 12, 0), new HashMap<>(Map.of("marker", "before"))));
		this.waitArchived(rewrittenKey);
		TestHelper.moveClockBy(Duration.ofMinutes(1));
		final TieredSnapshot rewritten = this.snapshotRepository.findById(rewrittenKey).orElseThrow();
		rewritten.setData(new HashMap<>(Map.of("marker", "after")));
		rewritten.setUpdatedAt(DateTimeHelper.getCurrentLocalDateTime());
		this.seed(rewritten);

		// Past the TTL and the safety margin, only the archived row is evicted.
		TestHelper.moveClockBy(Duration.ofDays(20));
		this.evictSnapshots(key);
		Assertions.assertTrue(this.snapshotRepository.findById(neverArchivedKey).isPresent());
		Assertions.assertTrue(this.snapshotRepository.findById(rewrittenKey).isPresent());
		Assertions.assertTrue(this.coldStorage.load(this.tieringServiceComponent.objectKeyOf(this.rowKey(neverArchivedKey))).isEmpty());

		// A read fetches the object and rehydrates the row with its original columns.
		final TieredSnapshot rehydrated = this.tieringServiceComponent.findByIdTiered(TieredSnapshot.class, key).orElseThrow();
		Assertions.assertEquals("one", rehydrated.getData().get("marker"));
		Assertions.assertNotNull(rehydrated.getLastAccessedAt());
		Assertions.assertEquals(rehydrated.getUpdatedAt(), rehydrated.getArchivedAt());
		Assertions.assertTrue(rehydrated.getUpdatedAt().isBefore(DateTimeHelper.getCurrentLocalDateTime().minusDays(19)));
		final LocalDateTime rehydratedClock = rehydrated.getLastAccessedAt();

		// A read past the touch threshold extends the clock.
		TestHelper.moveClockBy(Duration.ofDays(2));
		this.tieringServiceComponent.findByIdTiered(TieredSnapshot.class, key).orElseThrow();
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.snapshotRepository.findById(key).orElseThrow().getLastAccessedAt(),
				clock -> clock.isAfter(rehydratedClock.plusDays(1)), TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));

		// Evicted again and the object gone: a miss, not an error.
		TestHelper.moveClockBy(Duration.ofDays(20));
		this.evictSnapshots(key);
		this.coldStorage.delete(objectKey);
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredSnapshot.class, key).isEmpty());
	}

	/**
	 * A rewrite after the archive drops the mark, is re-archived with the new content, and only then
	 * evicted; a delete removes both tiers.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: a rewrite is re-archived before it can be evicted; a delete clears both tiers")
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
		Assertions.assertEquals("second", this.tieringServiceComponent.findByIdTiered(TieredSnapshot.class, key).orElseThrow().getData().get("marker"));

		this.tieringServiceComponent.delete(this.rowKey(key));
		Assertions.assertTrue(this.snapshotRepository.findById(key).isEmpty());
		Assertions.assertTrue(this.coldStorage.load(this.tieringServiceComponent.objectKeyOf(this.rowKey(key))).isEmpty());
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredSnapshot.class, key).isEmpty());
	}

	/**
	 * The snapshot table's backfill batch record.
	 *
	 * @return The record, or null when none.
	 */
	private BatchExecutor<?> snapshotBackfillRecord() {
		return (BatchExecutor<?>) this.keyValueService.getRepository()
				.findById(BatchService.BATCH_KEY_PREFIX + TieringBatch.BACKFILL.keySuffix(ColdTieringTestConfiguration.SNAPSHOT_TABLE)).map(KeyValue::getValue)
				.orElse(null);
	}

	/**
	 * Seeds snapshots without the hook (rows only the backfill finds), one per key.
	 *
	 * @return The keys, in key order.
	 */
	private List<TieredSnapshotKey> seedUnarchived() {
		final List<TieredSnapshotKey> keys = List.of(new TieredSnapshotKey("owner-3", LocalDateTime.of(2026, 1, 1, 0, 0)),
				new TieredSnapshotKey("owner-3", LocalDateTime.of(2026, 1, 2, 0, 0)), new TieredSnapshotKey("owner-4", LocalDateTime.of(2026, 1, 3, 0, 0)),
				new TieredSnapshotKey("owner-5", LocalDateTime.of(2026, 1, 4, 0, 0)), new TieredSnapshotKey("owner-6", LocalDateTime.of(2026, 1, 5, 0, 0)));
		for (final TieredSnapshotKey key : keys) {
			this.seed(new TieredSnapshot(key.getOwnerId(), key.getCachedAt(), new HashMap<>(Map.of("marker", key.getOwnerId()))));
		}
		return keys;
	}

	/**
	 * The backfill enqueues rows written without the hook, across chunks, each key once (the cursor
	 * never selects a key enqueued earlier in the run, archived or not yet), and the backfill
	 * listener archives every one; the deprecated delegate that runs persisted by earlier versions
	 * still name enqueues too.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@SuppressWarnings("deprecation")
	@DisplayName("Snapshot: the backfill enqueues every unarchived row once and the listener archives it")
	public void testBackfill() throws Exception {
		final List<TieredSnapshotKey> keys = this.seedUnarchived();
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.SNAPSHOT_TABLE, TieringBatch.BACKFILL));
		for (final TieredSnapshotKey key : keys) {
			this.waitArchived(key);
		}
		Assertions.assertEquals(keys.size(), this.coldStorage.list("cold-tiering/tiered-snapshot/").size());
		Assertions.assertTrue(TestHelper.waitUntilValid(this::snapshotBackfillRecord, batchRecord -> (batchRecord != null) && batchRecord.isFinished(),
				TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		Assertions.assertEquals(Long.valueOf(keys.size()), this.snapshotBackfillRecord().getLastProcessedCount());

		// A run persisted before the upgrade still names the old delegate: it enqueues as well.
		final TieredSnapshotKey legacyKey = new TieredSnapshotKey("owner-9", LocalDateTime.of(2026, 1, 6, 0, 0));
		this.seed(new TieredSnapshot(legacyKey.getOwnerId(), legacyKey.getCachedAt(), new HashMap<>(Map.of("marker", "legacy"))));
		this.batchServiceComponent.archiveKey(this.rowKey(legacyKey));
		this.waitArchived(legacyKey);
	}

	/**
	 * A duplicate archive request for content already archived uploads nothing and leaves the row
	 * archived at the same watermark.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: a duplicate archive request of an archived row uploads nothing")
	public void testDuplicateArchiveRequest() throws Exception {
		final TieredSnapshotKey key = this.write(new TieredSnapshot("owner-8", LocalDateTime.of(2026, 3, 1, 12, 0), new HashMap<>(Map.of("marker", "once"))));
		this.waitArchived(key);
		final LocalDateTime archivedAt = this.snapshotRepository.findById(key).orElseThrow().getArchivedAt();
		// A sentinel in place of the object: any new upload would replace it.
		final String objectKey = this.tieringServiceComponent.objectKeyOf(this.rowKey(key));
		final byte[] sentinel = new byte[] { 1 };
		this.coldStorage.store(objectKey, sentinel, Map.of());

		// The duplicate, then an unarchived row as a barrier, both on the backfill queue: its single
		// consumer (pinned in the test properties) takes them in order.
		this.tieringServiceComponent.requestArchive(this.rowKey(key));
		final TieredSnapshotKey barrierKey = new TieredSnapshotKey("owner-8", LocalDateTime.of(2026, 3, 2, 12, 0));
		this.seed(new TieredSnapshot(barrierKey.getOwnerId(), barrierKey.getCachedAt(), new HashMap<>(Map.of("marker", "barrier"))));
		this.tieringServiceComponent.requestArchive(this.rowKey(barrierKey));
		this.waitArchived(barrierKey);

		// The sentinel survives: the duplicate uploaded nothing.
		Assertions.assertTrue(Arrays.equals(sentinel, this.coldStorage.load(objectKey).orElseThrow()));
		// The mark is kept (not cleared); this alone would not catch a re-upload, which writes the same mark.
		Assertions.assertEquals(archivedAt, this.snapshotRepository.findById(key).orElseThrow().getArchivedAt());
		Assertions.assertFalse(this.tieringServiceComponent.archive(this.rowKey(key)));
	}

	/**
	 * A start while a run is active continues that run instead of starting another one: same
	 * start, same cursor and count, no key enqueued ahead of the run's own pace.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Snapshot: a backfill start while a run is active does not start a parallel run")
	public void testBackfillNoParallelRun() throws Exception {
		final List<TieredSnapshotKey> keys = this.seedUnarchived();
		// A long pause between chunks keeps the run active after its first chunk.
		final TableProperties slowTable = new TableProperties();
		slowTable.getBackfill().setDelayBetweenChunks(Duration.ofMinutes(10));
		this.properties.getTable().put(ColdTieringTestConfiguration.SNAPSHOT_TABLE, slowTable);
		try {
			Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.SNAPSHOT_TABLE, TieringBatch.BACKFILL));
			Assertions.assertTrue(TestHelper.waitUntilValid(this::snapshotBackfillRecord,
					batchRecord -> (batchRecord != null) && Long.valueOf(2).equals(batchRecord.getLastProcessedCount()), TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
			final LocalDateTime startedAt = this.snapshotBackfillRecord().getLastStartedAt();
			this.waitArchived(keys.get(0));
			this.waitArchived(keys.get(1));

			// The second start (an hourly cron on any instance) joins the active run.
			Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.SNAPSHOT_TABLE, TieringBatch.BACKFILL));
			Thread.sleep(TestHelper.REGULAR_WAIT);
			final BatchExecutor<?> batchRecord = this.snapshotBackfillRecord();
			Assertions.assertEquals(startedAt, batchRecord.getLastStartedAt());
			Assertions.assertEquals(Long.valueOf(2), batchRecord.getLastProcessedCount());
			Assertions.assertFalse(batchRecord.isFinished());
			Assertions.assertNull(this.snapshotRepository.findById(keys.get(2)).orElseThrow().getArchivedAt());
			Assertions.assertEquals(2, this.coldStorage.list("cold-tiering/tiered-snapshot/").size());
		}
		finally {
			this.properties.getTable().remove(ColdTieringTestConfiguration.SNAPSHOT_TABLE);
			this.batchService.cancel(TieringBatch.BACKFILL.keySuffix(ColdTieringTestConfiguration.SNAPSHOT_TABLE));
		}
	}

	/**
	 * "Newest on or before" over hot and cold rows.
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
		Assertions.assertEquals("third", this.newest(prefix, null).getData().get("marker"));
		Assertions.assertEquals("second", this.newest(prefix, second).getData().get("marker"));
		Assertions.assertEquals("first", this.newest(prefix, second.minusDays(1)).getData().get("marker"));
		Assertions.assertTrue(this.tieringServiceComponent.findNewestTiered(TieredSnapshot.class, prefix, first.minusDays(1)).isEmpty());
		Assertions.assertTrue(this.tieringServiceComponent.findNewestTiered(TieredSnapshot.class, Map.of("owner_id", "nobody"), null).isEmpty());
		// The cold ones were rehydrated by the reads.
		Assertions.assertTrue(this.snapshotRepository.findById(firstKey).isPresent());
		Assertions.assertTrue(this.snapshotRepository.findById(secondKey).isPresent());
	}

	/**
	 * The newest hot snapshot of a prefix.
	 *
	 * @param  prefix Prefix.
	 * @param  asOf   Moment.
	 * @return        The entity.
	 */
	private TieredSnapshot newest(
			final Map<String, Object> prefix,
			final LocalDateTime asOf) {
		return this.tieringServiceComponent.findNewestTiered(TieredSnapshot.class, prefix, asOf).orElseThrow().hot().orElseThrow();
	}

	/**
	 * A table with rehydration NONE (property override), a single id, a text payload and an age
	 * from the last write: archived on write, evicted, readable cold only, never hot again; the
	 * library schedules a batch from a cron.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Archive entry: no rehydration, cold read only, eviction and schedule registration")
	public void testNoRehydration() throws Exception {
		this.write(new TieredArchiveEntry(42L, "forty-two"), ColdTieringTestConfiguration.ARCHIVE_TABLE);
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archiveEntryRepository.findById(42L).map(TieredArchiveEntry::getArchivedAt).orElse(null),
				archivedAt -> archivedAt != null, TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		Assertions.assertEquals("cold-tiering/tiered-archive-entry/0000000000000000042.json.gz",
				this.tieringServiceComponent.objectKeyOf(this.tieringServiceComponent.keyOf(TieredArchiveEntry.class, 42L)));

		// Evicted (TTL one day, no safety margin).
		TestHelper.moveClockBy(Duration.ofDays(2));
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.ARCHIVE_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archiveEntryRepository.findById(42L).isEmpty(), evicted -> evicted, TestHelper.REGULAR_WAIT * 4,
				TestHelper.SHORT_WAIT));

		// Cold only: the tiered read is a miss, the cold row holds the whole row, the hot table stays empty.
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredArchiveEntry.class, 42L).isEmpty());
		final Optional<ColdRow> coldRow = this.tieringServiceComponent.findColdRow(TieredArchiveEntry.class, 42L);
		Assertions.assertTrue(coldRow.get().rowJson().contains("\"content\":\"forty-two\""));
		Assertions.assertTrue(this.archiveEntryRepository.findById(42L).isEmpty());

		// The library registers a schedule from a cron, none from "-", and forgets it on unschedule.
		final String scheduleKey = TieringBatch.EVICTION.keySuffix(ColdTieringTestConfiguration.ARCHIVE_TABLE);
		Assertions.assertFalse(this.batchServiceComponent.getScheduled().containsKey(scheduleKey));
		try {
			final ScheduledFuture<?> task = this.batchServiceComponent.schedule(this.archiveTable, TieringBatch.EVICTION, "0 0 3 * * *");
			Assertions.assertNotNull(task);
			Assertions.assertEquals("0 0 3 * * *", this.batchServiceComponent.getScheduled().get(scheduleKey));
			Assertions.assertNull(this.batchServiceComponent.schedule(this.archiveTable, TieringBatch.BACKFILL, "-"));
		}
		finally {
			this.batchServiceComponent.unschedule(this.archiveTable, TieringBatch.EVICTION);
		}
		Assertions.assertFalse(this.batchServiceComponent.getScheduled().containsKey(scheduleKey));
	}

	/**
	 * A definition disabled by property does nothing; one with cold reads off archives but never
	 * reads cold.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Archive entry: a disabled definition does nothing, cold reads off never reach the store")
	public void testDisabledDefinitions() throws Exception {
		this.write(new TieredArchiveEntry(43L, "disabled"), ColdTieringTestConfiguration.DISABLED_TABLE);
		final TieredRowKey disabledKey = this.tieringServiceComponent.keyOf(ColdTieringTestConfiguration.DISABLED_TABLE, Map.of("id", 43L));
		Assertions.assertFalse(this.batchServiceComponent.run(ColdTieringTestConfiguration.DISABLED_TABLE, TieringBatch.EVICTION));
		Assertions.assertFalse(this.batchServiceComponent.run(ColdTieringTestConfiguration.DISABLED_TABLE, TieringBatch.BACKFILL));
		// A later write through the enabled definition is archived; the disabled one still is not.
		this.write(new TieredArchiveEntry(45L, "enabled"), ColdTieringTestConfiguration.ARCHIVE_TABLE);
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.archiveEntryRepository.findById(45L).map(TieredArchiveEntry::getArchivedAt).orElse(null),
				archivedAt -> archivedAt != null, TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		Assertions.assertNull(this.archiveEntryRepository.findById(43L).orElseThrow().getArchivedAt());
		Assertions.assertTrue(this.coldStorage.list("cold-tiering/tiered-archive-entry-disabled/").isEmpty());
		// Even with an object in place, a disabled table never reads it.
		this.coldStorage.store(this.tieringServiceComponent.objectKeyOf(disabledKey), new byte[] { 1 }, Map.of());
		Assertions.assertTrue(this.tieringServiceComponent.readCold(disabledKey).isEmpty());

		this.write(new TieredArchiveEntry(44L, "no-cold-read"), ColdTieringTestConfiguration.NO_COLD_READ_TABLE);
		final TieredRowKey noColdReadKey = this.tieringServiceComponent.keyOf(ColdTieringTestConfiguration.NO_COLD_READ_TABLE, Map.of("id", 44L));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.coldStorage.load(this.tieringServiceComponent.objectKeyOf(noColdReadKey)).isPresent(),
				archived -> archived, TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		Assertions.assertTrue(this.tieringServiceComponent.readCold(noColdReadKey).isEmpty());
	}

	/**
	 * Writes an event with the tiering hook.
	 *
	 * @param event Event.
	 */
	@Transactional
	private void write(
			final TieredEvent event) {
		this.eventRepository.save(event);
		this.tieringServiceComponent.afterWrite(this.tieringServiceComponent.keyOf(TieredEvent.class, new TieredEventKey(event.getSource(), event.getOccurredAt())));
	}

	/**
	 * A {@code TIMESTAMPTZ} version column mapped as {@code LocalDateTime}, a TTL on that column
	 * and no rehydration: kept before the TTL, evicted after it, read cold by key and as the
	 * newest on or before a moment.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Event: timestamptz key, TTL on the column, cold-only newest read")
	public void testColumnClockAndTimestamptzKey() throws Exception {
		final LocalDateTime recent = DateTimeHelper.getCurrentLocalDateTime().minusDays(10).withNano(0);
		final LocalDateTime old = DateTimeHelper.getCurrentLocalDateTime().minusDays(30).withNano(0);
		this.write(new TieredEvent("web", recent, "recent"));
		this.write(new TieredEvent("web", old, "old"));
		final TieredEventKey recentKey = new TieredEventKey("web", recent);
		final TieredEventKey oldKey = new TieredEventKey("web", old);
		for (final TieredEventKey key : List.of(recentKey, oldKey)) {
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.eventRepository.findById(key).map(TieredEvent::getArchivedAt).orElse(null),
					archivedAt -> archivedAt != null, TestHelper.REGULAR_WAIT * 4, TestHelper.SHORT_WAIT));
		}
		final String objectKey = this.tieringServiceComponent.objectKeyOf(this.tieringServiceComponent.keyOf(TieredEvent.class, recentKey));
		Assertions.assertTrue(objectKey.startsWith("cold-tiering-events/tiered-event/web/") && objectKey.endsWith("Z.json.gz"), objectKey);

		// The TTL (15 days) counts from the column: the old event goes, the recent one stays.
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.EVENT_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.eventRepository.findById(oldKey).isEmpty(), evicted -> evicted, TestHelper.REGULAR_WAIT * 4,
				TestHelper.SHORT_WAIT));
		Assertions.assertTrue(this.eventRepository.findById(recentKey).isPresent());

		// Cold only, by key and as the newest on or before a moment; the hot one wins when newer.
		Assertions.assertTrue(this.tieringServiceComponent.findByIdTiered(TieredEvent.class, oldKey).isEmpty());
		Assertions.assertTrue(this.tieringServiceComponent.findColdRow(TieredEvent.class, oldKey).orElseThrow().rowJson().contains("\"content\":\"old\""));
		final Map<String, Object> prefix = Map.of("source", "web");
		final TieredHit<TieredEvent> newestOld = this.tieringServiceComponent.findNewestTiered(TieredEvent.class, prefix, old.plusDays(1)).orElseThrow();
		Assertions.assertTrue(newestOld.hot().isEmpty());
		Assertions.assertTrue(newestOld.cold().orElseThrow().rowJson().contains("\"content\":\"old\""));
		Assertions.assertEquals("recent", this.tieringServiceComponent.findNewestTiered(TieredEvent.class, prefix, null).orElseThrow().hot().orElseThrow().getContent());
		Assertions.assertTrue(this.eventRepository.findById(oldKey).isEmpty());

		// Past the TTL on the column, the recent one goes too.
		TestHelper.moveClockBy(Duration.ofDays(10));
		Assertions.assertTrue(this.batchServiceComponent.run(ColdTieringTestConfiguration.EVENT_TABLE, TieringBatch.EVICTION));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.eventRepository.findById(recentKey).isEmpty(), evicted -> evicted, TestHelper.REGULAR_WAIT * 4,
				TestHelper.SHORT_WAIT));
	}

}
