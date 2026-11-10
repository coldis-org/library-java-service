package org.coldis.library.test.service.tiering;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.coldis.library.service.tiering.model.Rehydration;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.TieredTable;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.coldis.library.service.tiering.model.TtlClock;
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

	/** Partition-tiered table id. */
	public static final String MONTHLY_TABLE = "tiered-monthly-snapshot";

	/** Partition-tiered table name. */
	public static final String MONTHLY_TABLE_NAME = "tiered_monthly_snapshot";

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


	/**
	 * Partition-tiered definition.
	 *
	 * @return Definition.
	 */
	@Bean
	public PartitionTieredTable tieredMonthlySnapshotTable() {
		return new PartitionTieredTable(ColdTieringTestConfiguration.MONTHLY_TABLE, ColdTieringTestConfiguration.MONTHLY_TABLE_NAME, List.of("owner_id", "cached_at"))
				.withShardKeyColumn("owner_id").withPartitionColumn("cached_at").withEntity(TieredMonthlySnapshot.class, null);
	}

	/**
	 * Recreates the monthly snapshot table as a range-partitioned table (Hibernate's create-drop
	 * made a plain one): a legacy partition, three months, an open-ended one.
	 *
	 * @param  dataSource Data source.
	 * @return            Marker.
	 */
	@Bean
	@DependsOn("entityManagerFactory")
	public String tieredMonthlySnapshotSchema(
			final DataSource dataSource) {
		final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		final String table = ColdTieringTestConfiguration.MONTHLY_TABLE_NAME;
		jdbcTemplate.execute("DROP TABLE IF EXISTS " + table + " CASCADE");
		jdbcTemplate.execute("CREATE TABLE " + table + " (owner_id varchar(255) NOT NULL, cached_at timestamp(6) NOT NULL, data jsonb, created_at timestamptz NOT NULL,"
				+ " updated_at timestamp(6) NOT NULL, last_accessed_at timestamp(6), archived_at timestamp(6), PRIMARY KEY (owner_id, cached_at))"
				+ " PARTITION BY RANGE (cached_at)");
		jdbcTemplate.execute("CREATE TABLE " + table + "_legacy PARTITION OF " + table + " FOR VALUES FROM (MINVALUE) TO ('2026-01-01 00:00:00')");
		jdbcTemplate.execute("CREATE TABLE " + table + "_2026_01 PARTITION OF " + table + " FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2026-02-01 00:00:00')");
		jdbcTemplate.execute("CREATE TABLE " + table + "_2026_02 PARTITION OF " + table + " FOR VALUES FROM ('2026-02-01 00:00:00') TO ('2026-03-01 00:00:00')");
		jdbcTemplate.execute("CREATE TABLE " + table + "_2026_03 PARTITION OF " + table + " FOR VALUES FROM ('2026-03-01 00:00:00') TO ('2026-04-01 00:00:00')");
		jdbcTemplate.execute("CREATE TABLE " + table + "_plus PARTITION OF " + table + " FOR VALUES FROM ('2026-04-01 00:00:00') TO (MAXVALUE)");
		return table;
	}

}
