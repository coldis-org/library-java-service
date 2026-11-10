package org.coldis.library.service.tiering.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.LeafPartition;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.TieredTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the leaf partitions of a partition-tiered table: catalog, key-only and data
 * pages in key order, counts, and the heap-order sweep chunk. Every partition name is validated
 * as an identifier; callers take names from {@link #leafPartitions(TieredTable)}.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.ENABLED,
		matchIfMissing = false
)
public class PartitionHotTierRepository {

	/** One exported row. */
	public record ExportRow(Map<String, String> key, String rowJson) {

	}

	/** Result of a sweep chunk. */
	public record SweepResult(Integer deleted, Integer kept) {

	}

	/** Key parameter prefix. */
	private static final String KEY_PARAMETER_PREFIX = "key_";

	/** JDBC. */
	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * Keyset predicate after the last key (row-value comparison on the full key).
	 *
	 * @param  table      Table.
	 * @param  lastKey    Last key (formatted values), or null.
	 * @param  parameters Parameters to fill.
	 * @return            SQL fragment ("" when there is no last key).
	 */
	private static String afterKey(
			final TieredTable table,
			final Map<String, String> lastKey,
			final MapSqlParameterSource parameters) {
		String predicate = "";
		if (lastKey != null) {
			final List<String> placeholders = new ArrayList<>();
			for (final String column : table.getKeyColumns()) {
				placeholders.add(":" + PartitionHotTierRepository.KEY_PARAMETER_PREFIX + column);
				parameters.addValue(PartitionHotTierRepository.KEY_PARAMETER_PREFIX + column, ColumnValues.parse(table.getColumnTypes().get(column), lastKey.get(column)));
			}
			predicate = " AND (" + String.join(", ", table.getKeyColumns()) + ") > (" + String.join(", ", placeholders) + ")";
		}
		return predicate;
	}

	/**
	 * Shard range predicate on the shard key (canonical segments to column values).
	 *
	 * @param  table      Table.
	 * @param  from       Lower bound segment ("" for none).
	 * @param  to         Upper bound segment (null for none).
	 * @param  parameters Parameters to fill.
	 * @return            SQL fragment.
	 */
	private static String shardRange(
			final PartitionTieredTable table,
			final String from,
			final String to,
			final MapSqlParameterSource parameters) {
		final String type = table.getColumnTypes().get(table.getShardKeyColumn());
		final StringBuilder predicate = new StringBuilder();
		if ((from != null) && !from.isEmpty()) {
			predicate.append(" AND ").append(table.getShardKeyColumn()).append(" >= :shard_from");
			parameters.addValue("shard_from", ColumnValues.parse(type, ColumnValues.fromSegment(type, from)));
		}
		if (to != null) {
			predicate.append(" AND ").append(table.getShardKeyColumn()).append(" < :shard_to");
			parameters.addValue("shard_to", ColumnValues.parse(type, ColumnValues.fromSegment(type, to)));
		}
		return predicate.toString();
	}

	/**
	 * Leaf partitions of a table with their bounds, by name.
	 *
	 * @param  table Table.
	 * @return       Partitions.
	 */
	public List<LeafPartition> leafPartitions(
			final TieredTable table) {
		return this.jdbcTemplate.query("SELECT child.relname AS name_, pg_get_expr(child.relpartbound, child.oid) AS bound_ FROM pg_inherits"
				+ " JOIN pg_class parent ON parent.oid = pg_inherits.inhparent JOIN pg_class child ON child.oid = pg_inherits.inhrelid"
				+ " JOIN pg_namespace ns ON ns.oid = child.relnamespace WHERE parent.relname = :table AND ns.nspname = current_schema()"
				+ " AND child.relkind = 'r' ORDER BY child.relname", new MapSqlParameterSource("table", table.getTableName()), (
						resultSet,
						rowNumber) -> LeafPartition.of(TieredTable.identifier(resultSet.getString("name_")), resultSet.getString("bound_")));
	}

	/**
	 * Partition strategy of a table ('r' for range), empty when not partitioned.
	 *
	 * @param  tableName Table.
	 * @return           Strategy.
	 */
	public Optional<String> partitionStrategy(
			final String tableName) {
		return this.jdbcTemplate.query("SELECT CAST(pt.partstrat AS text) AS strategy_ FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid"
				+ " JOIN pg_namespace ns ON ns.oid = c.relnamespace WHERE c.relname = :table AND ns.nspname = current_schema()",
				new MapSqlParameterSource("table", tableName), (
						resultSet,
						rowNumber) -> resultSet.getString("strategy_")).stream().findFirst();
	}

	/**
	 * Primary key columns of a table, in index order.
	 *
	 * @param  tableName Table.
	 * @return           Columns.
	 */
	public List<String> primaryKeyColumns(
			final String tableName) {
		return this.jdbcTemplate.query("SELECT a.attname AS column_ FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid"
				+ " JOIN pg_namespace ns ON ns.oid = c.relnamespace CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, ordinal)"
				+ " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum WHERE c.relname = :table AND ns.nspname = current_schema()"
				+ " AND i.indisprimary ORDER BY k.ordinal", new MapSqlParameterSource("table", tableName), (
						resultSet,
						rowNumber) -> resultSet.getString("column_"));
	}

	/**
	 * Next page of keys (every key column) in key order: the boundaries pass.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  lastKey   Last key, or null.
	 * @param  pageSize  Page size.
	 * @return           Keys (formatted values).
	 */
	public List<Map<String, String>> nextKeys(
			final PartitionTieredTable table,
			final String partition,
			final Map<String, String> lastKey,
			final Integer pageSize) {
		final MapSqlParameterSource parameters = new MapSqlParameterSource("limit", pageSize);
		final String sql = "SELECT " + String.join(", ", table.getKeyColumns()) + " FROM ONLY " + TieredTable.identifier(partition) + " WHERE TRUE"
				+ PartitionHotTierRepository.afterKey(table, lastKey, parameters) + " ORDER BY " + String.join(", ", table.getKeyColumns()) + " LIMIT :limit";
		return this.jdbcTemplate.query(sql, parameters, (
				resultSet,
				rowNumber) -> {
			final Map<String, String> values = new LinkedHashMap<>();
			for (final String column : table.getKeyColumns()) {
				values.put(column, ColumnValues.format(ColumnValues.read(resultSet, column, table.getColumnTypes().get(column))));
			}
			return values;
		});
	}

	/**
	 * Next page of rows of a shard in key order: the data pass.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  from      Shard lower bound segment.
	 * @param  to        Shard upper bound segment (null on the last shard).
	 * @param  lastKey   Last key, or null.
	 * @param  pageSize  Page size.
	 * @return           Rows.
	 */
	public List<ExportRow> nextPage(
			final PartitionTieredTable table,
			final String partition,
			final String from,
			final String to,
			final Map<String, String> lastKey,
			final Integer pageSize) {
		final MapSqlParameterSource parameters = new MapSqlParameterSource("limit", pageSize);
		final String sql = "SELECT " + String.join(", ", table.getKeyColumns()) + ", CAST(row_to_json(part) AS text) AS row_ FROM ONLY "
				+ TieredTable.identifier(partition) + " part WHERE TRUE" + PartitionHotTierRepository.shardRange(table, from, to, parameters)
				+ PartitionHotTierRepository.afterKey(table, lastKey, parameters) + " ORDER BY " + String.join(", ", table.getKeyColumns()) + " LIMIT :limit";
		return this.jdbcTemplate.query(sql, parameters, (
				resultSet,
				rowNumber) -> {
			final Map<String, String> values = new LinkedHashMap<>();
			for (final String column : table.getKeyColumns()) {
				values.put(column, ColumnValues.format(ColumnValues.read(resultSet, column, table.getColumnTypes().get(column))));
			}
			return new ExportRow(values, resultSet.getString("row_"));
		});
	}

	/**
	 * Rows of a shard range (an index range count).
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  from      Lower bound segment.
	 * @param  to        Upper bound segment.
	 * @return           Count.
	 */
	public Long countInRange(
			final PartitionTieredTable table,
			final String partition,
			final String from,
			final String to) {
		final MapSqlParameterSource parameters = new MapSqlParameterSource();
		final String sql = "SELECT count(*) FROM ONLY " + TieredTable.identifier(partition) + " WHERE TRUE"
				+ PartitionHotTierRepository.shardRange(table, from, to, parameters);
		return this.jdbcTemplate.queryForObject(sql, parameters, Long.class);
	}

	/**
	 * Whether any row of the partition has a watermark after a moment.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  moment    Moment.
	 * @return           Whether a row was written after it.
	 */
	public Boolean hasWatermarkAfter(
			final TieredTable table,
			final String partition,
			final LocalDateTime moment) {
		final String sql = "SELECT 1 FROM ONLY " + TieredTable.identifier(partition) + " WHERE " + table.getWatermarkColumn() + " > :moment LIMIT 1";
		return !this.jdbcTemplate.queryForList(sql, new MapSqlParameterSource("moment", moment), Integer.class).isEmpty();
	}

	/**
	 * Whether the partition holds any row.
	 *
	 * @param  partition Partition.
	 * @return           Has rows.
	 */
	public Boolean hasRows(
			final String partition) {
		return !this.jdbcTemplate.queryForList("SELECT 1 FROM ONLY " + TieredTable.identifier(partition) + " LIMIT 1", new MapSqlParameterSource(), Integer.class)
				.isEmpty();
	}

	/**
	 * Planner row estimate of a partition ({@code reltuples}; negative when never vacuumed).
	 *
	 * @param  partition Partition.
	 * @return           Estimate.
	 */
	public Long estimateRows(
			final String partition) {
		return this.jdbcTemplate.queryForObject("SELECT CAST(reltuples AS bigint) FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace"
				+ " WHERE c.relname = :partition AND ns.nspname = current_schema()", new MapSqlParameterSource("partition", TieredTable.identifier(partition)),
				Long.class);
	}

	/**
	 * Heap blocks of a partition.
	 *
	 * @param  partition Partition.
	 * @return           Blocks.
	 */
	public Long blockCount(
			final String partition) {
		return this.jdbcTemplate.queryForObject(
				"SELECT pg_relation_size(CAST(:partition AS regclass)) / CAST(current_setting('block_size') AS bigint)",
				new MapSqlParameterSource("partition", TieredTable.identifier(partition)), Long.class);
	}

	/**
	 * Sweeps a range of heap blocks of a verified partition: deletes the rows the export covers
	 * ({@code watermark <= exportedAt}) whose clock is before the cutoff, and marks the ones it
	 * keeps as archived (so the row-delete eviction finds them when they expire).
	 *
	 * @param  table      Table.
	 * @param  partition  Partition.
	 * @param  fromBlock  First block (inclusive).
	 * @param  toBlock    Last block (exclusive).
	 * @param  before     Clock cutoff.
	 * @param  exportedAt Export moment.
	 * @return            Deleted and kept rows.
	 */
	public SweepResult sweepChunk(
			final PartitionTieredTable table,
			final String partition,
			final Long fromBlock,
			final Long toBlock,
			final LocalDateTime before,
			final LocalDateTime exportedAt) {
		final String name = TieredTable.identifier(partition);
		final String sql = "WITH picked AS (SELECT ctid AS tid_, (" + table.clockExpression() + " < :before) AS expired_ FROM ONLY " + name
				+ " WHERE ctid >= CAST(:from_tid AS tid) AND ctid < CAST(:to_tid AS tid) AND " + table.getWatermarkColumn()
				+ " <= :exported_at FOR UPDATE SKIP LOCKED), deleted AS (DELETE FROM ONLY " + name
				+ " part USING picked WHERE part.ctid = picked.tid_ AND picked.expired_ RETURNING 1), kept AS (UPDATE ONLY " + name + " part SET "
				+ table.getArchivedAtColumn() + " = part." + table.getWatermarkColumn() + " FROM picked WHERE part.ctid = picked.tid_ AND NOT picked.expired_ AND part."
				+ table.getArchivedAtColumn() + " IS NULL RETURNING 1) SELECT (SELECT count(*) FROM deleted) AS deleted_, (SELECT count(*) FROM kept) AS kept_";
		final MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("from_tid", "(" + fromBlock + ",0)").addValue("to_tid", "(" + toBlock + ",0)")
				.addValue("before", before).addValue("exported_at", exportedAt);
		return this.jdbcTemplate.queryForObject(sql, parameters, (
				resultSet,
				rowNumber) -> new SweepResult(resultSet.getInt("deleted_"), resultSet.getInt("kept_")));
	}

}
