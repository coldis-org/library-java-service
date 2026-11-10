package org.coldis.library.service.tiering;

import java.util.ArrayList;
import java.util.List;

import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.repository.PartitionArchiveRepository;

/**
 * DDL a tiered table needs on its hot table: the lifecycle columns and the eviction index. The
 * consumer runs it in its migrations; with
 * {@code org.coldis.library.service.tiering.create-schema=true} the registry runs it at startup
 * (tests). The cold tier itself needs no schema: an object's location is derived from the key.
 */
public final class ColdTieringSchema {

	/**
	 * No instances.
	 */
	private ColdTieringSchema() {
		super();
	}

	/**
	 * DDL of the lifecycle columns.
	 *
	 * @param  table         Table.
	 * @param  watermarkType SQL type of the watermark column ({@code information_schema}'s
	 *                           {@code data_type}): the added columns take it, so the eviction
	 *                           index's {@code COALESCE} mixes no {@code timestamp} with
	 *                           {@code timestamptz} (a session-time-zone cast is not immutable).
	 * @return               The statement.
	 */
	public static String columnsStatement(
			final TieredTable table,
			final String watermarkType) {
		final StringBuilder alter = new StringBuilder("ALTER TABLE ").append(table.getTableName());
		if (table.isTouching()) {
			alter.append(" ADD COLUMN IF NOT EXISTS ").append(table.getLastAccessedColumn()).append(' ').append(watermarkType).append(',');
		}
		alter.append(" ADD COLUMN IF NOT EXISTS ").append(table.getArchivedAtColumn()).append(' ').append(watermarkType);
		return alter.toString();
	}

	/**
	 * DDL of the eviction index (what the eviction scans).
	 *
	 * @param  table        Table.
	 * @param  concurrently Whether to build it {@code CONCURRENTLY} (production; not inside a
	 *                          transaction).
	 * @return              The statement.
	 */
	public static String evictionIndexStatement(
			final TieredTable table,
			final Boolean concurrently) {
		return "CREATE INDEX " + (concurrently ? "CONCURRENTLY " : "") + "IF NOT EXISTS " + table.getTableName() + "_cold_evict_idx ON " + table.getTableName()
				+ " ((" + table.clockExpression() + ")) WHERE " + table.getArchivedAtColumn() + " IS NOT NULL";
	}

	/**
	 * Every statement a table needs, in order.
	 *
	 * @param  table         Table.
	 * @param  watermarkType SQL type of the watermark column.
	 * @param  concurrently  Whether the eviction index is built {@code CONCURRENTLY}.
	 * @return               The statements.
	 */
	public static List<String> statements(
			final TieredTable table,
			final String watermarkType,
			final Boolean concurrently) {
		final List<String> statements = new ArrayList<>();
		statements.add(ColdTieringSchema.columnsStatement(table, watermarkType));
		statements.add(ColdTieringSchema.evictionIndexStatement(table, concurrently));
		return statements;
	}


	/**
	 * DDL of the per-partition archive record table (partition tiering).
	 *
	 * @return Statements.
	 */
	public static List<String> partitionArchiveStatements() {
		return List.of("CREATE TABLE IF NOT EXISTS " + PartitionArchiveRepository.TABLE + " (table_id varchar NOT NULL, partition_name varchar NOT NULL,"
				+ " range_from text, range_to text, state varchar(16) NOT NULL, loaded_at timestamptz, export_started_at timestamptz, exported_at timestamptz,"
				+ " verified_at timestamptz, swept_at timestamptz, format_version integer, shard_count integer, shards_done integer, row_count bigint, loaded_rows bigint, generation text,"
				+ " size_bytes bigint, reexport_confirmed boolean NOT NULL DEFAULT FALSE, boundaries jsonb, shards jsonb, sweep_blocks bigint, sweep_cursor bigint, sweep_deleted bigint, sweep_kept bigint,"
				+ " manifest_key text, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, PRIMARY KEY (table_id, partition_name))");
	}

}
