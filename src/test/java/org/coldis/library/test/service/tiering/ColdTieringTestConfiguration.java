package org.coldis.library.test.service.tiering;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.coldis.library.service.tiering.Rehydration;
import org.coldis.library.service.tiering.TieredTable;
import org.coldis.library.service.tiering.TtlClock;
import org.coldis.library.service.tiering.repository.FolderColdStorageRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tiered tables of the tests: what a consumer declares.
 */
@Configuration
public class ColdTieringTestConfiguration {

	/** Snapshot table id. */
	public static final String SNAPSHOT_TABLE = "tiered-snapshot";

	/** Archive entry table id. */
	public static final String ARCHIVE_TABLE = "tiered-archive-entry";

	/** A definition of the archive table disabled by property. */
	public static final String DISABLED_TABLE = "tiered-archive-entry-disabled";

	/** A definition of the archive table with cold reads off by property. */
	public static final String NO_COLD_READ_TABLE = "tiered-archive-entry-no-cold-read";

	/** Event table id. */
	public static final String EVENT_TABLE = "tiered-event";

	/**
	 * Snapshots: composite key with a time version, JSONB payload, sliding TTL, rehydration.
	 *
	 * @return The definition.
	 */
	@Bean
	public TieredTable tieredSnapshotTable() {
		return new TieredTable(ColdTieringTestConfiguration.SNAPSHOT_TABLE, "tiered_snapshot", List.of("owner_id", "cached_at")).withVersioned()
				.withEntity(TieredSnapshot.class, null);
	}

	/**
	 * Archive entries: single id, text payload, age from the last write, no touch; the definition
	 * says {@link Rehydration#INSERT} and the property overrides it to {@code NONE}.
	 *
	 * @return The definition.
	 */
	@Bean
	public TieredTable tieredArchiveEntryTable() {
		return new TieredTable(ColdTieringTestConfiguration.ARCHIVE_TABLE, "tiered_archive_entry", List.of("id")).withTtlClock(TtlClock.LAST_WRITE, null)
				.withRehydration(Rehydration.INSERT).withEntity(TieredArchiveEntry.class, null);
	}

	/**
	 * The archive table again, disabled by {@code table.<id>.enabled=false}: nothing happens.
	 *
	 * @return The definition.
	 */
	@Bean
	public TieredTable tieredArchiveEntryDisabledTable() {
		return new TieredTable(ColdTieringTestConfiguration.DISABLED_TABLE, "tiered_archive_entry", List.of("id")).withTtlClock(TtlClock.LAST_WRITE, null)
				.withRehydration(Rehydration.NONE);
	}

	/**
	 * The archive table again, enabled with {@code cold-read-enabled=false}: archives, never reads
	 * cold.
	 *
	 * @return The definition.
	 */
	@Bean
	public TieredTable tieredArchiveEntryNoColdReadTable() {
		return new TieredTable(ColdTieringTestConfiguration.NO_COLD_READ_TABLE, "tiered_archive_entry", List.of("id")).withTtlClock(TtlClock.LAST_WRITE, null)
				.withRehydration(Rehydration.NONE);
	}

	/**
	 * Events: a {@code TIMESTAMPTZ} version column mapped as {@code LocalDateTime}, aged by that
	 * column, never rehydrated.
	 *
	 * @return The definition.
	 */
	@Bean
	public TieredTable tieredEventTable() {
		return new TieredTable(ColdTieringTestConfiguration.EVENT_TABLE, "tiered_event", List.of("source", "occurred_at")).withVersioned()
				.withTtlClock(TtlClock.COLUMN, "occurred_at").withRehydration(Rehydration.NONE).withEntity(TieredEvent.class, null);
	}

	/**
	 * Deletes the folder storage's root when the context closes.
	 *
	 * @param  coldStorage Folder storage.
	 * @return             The cleaner.
	 */
	@Bean
	public DisposableBean coldTieringFolderCleaner(
			final FolderColdStorageRepository coldStorage) {
		return () -> {
			coldStorage.reset();
			try {
				Files.deleteIfExists(coldStorage.getRoot());
			}
			catch (final IOException exception) {
				throw new IllegalStateException(exception);
			}
		};
	}

}
