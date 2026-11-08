package org.coldis.library.service.tiering;

import java.util.ArrayList;
import java.util.List;

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
	 * @param  table Table.
	 * @return       The statement.
	 */
	public static String columnsStatement(
			final TieredTable table) {
		final StringBuilder alter = new StringBuilder("ALTER TABLE ").append(table.getTableName());
		if (table.isTouching()) {
			alter.append(" ADD COLUMN IF NOT EXISTS ").append(table.getLastAccessedColumn()).append(" TIMESTAMPTZ,");
		}
		alter.append(" ADD COLUMN IF NOT EXISTS ").append(table.getArchivedAtColumn()).append(" TIMESTAMPTZ");
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
	 * @param  table        Table.
	 * @param  concurrently Whether the eviction index is built {@code CONCURRENTLY}.
	 * @return              The statements.
	 */
	public static List<String> statements(
			final TieredTable table,
			final Boolean concurrently) {
		final List<String> statements = new ArrayList<>();
		statements.add(ColdTieringSchema.columnsStatement(table));
		statements.add(ColdTieringSchema.evictionIndexStatement(table, concurrently));
		return statements;
	}

}
