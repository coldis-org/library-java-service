package org.coldis.library.test.service.tiering;

import java.util.List;

import org.coldis.library.service.tiering.Rehydration;
import org.coldis.library.service.tiering.TieredTable;
import org.coldis.library.service.tiering.TtlClock;
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

	/**
	 * Snapshots: composite key with a time version, JSONB payload, sliding TTL, rehydration.
	 *
	 * @return The definition.
	 */
	@Bean
	public TieredTable tieredSnapshotTable() {
		return new TieredTable(ColdTieringTestConfiguration.SNAPSHOT_TABLE, "tiered_snapshot", List.of("owner_id", "cached_at")).versioned()
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
		return new TieredTable(ColdTieringTestConfiguration.ARCHIVE_TABLE, "tiered_archive_entry", List.of("id")).withTtlClock(TtlClock.LAST_WRITE, null).withRehydration(Rehydration.INSERT).withEntity(TieredArchiveEntry.class, null);
	}

}
