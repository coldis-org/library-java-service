package org.coldis.library.service.tiering.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hot table access of the cold tiering. Every statement is native and generated from the
 * {@link TieredTable}: none bumps the watermark column (the data pipeline's clock) nor goes
 * through JPA (no history listener, no entity). The archived payload is the whole row
 * ({@code row_to_json}); the rehydration is its inverse ({@code json_populate_record}), so the
 * library needs no knowledge of the other columns.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.ENABLED,
		matchIfMissing = false
)
public class HotTierRepository {

	/**
	 * A hot row read for archiving.
	 *
	 * @param watermark Watermark value when read (string form; the archive mark takes it).
	 * @param rowJson   The whole row as JSON.
	 * @param isArchived Whether the cold tier already holds this content (archive mark at or after
	 *                       the watermark): a duplicate archive request has nothing to upload.
	 */
	public record HotRow(String watermark, String rowJson, Boolean isArchived) {

	}

	/** Parameter prefix of key columns. */
	private static final String KEY_PARAMETER_PREFIX = "key_";

	/** JDBC template. */
	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	/** Entity manager, flushed before the write hook so its update sees (and is not overwritten by) the caller's pending JPA write. */
	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * {@code k1 = :key_k1 AND k2 = :key_k2}.
	 *
	 * @param  table Table.
	 * @return       The key predicate.
	 */
	private static String keyPredicate(
			final TieredTable table) {
		final List<String> terms = new ArrayList<>();
		for (final String column : table.getKeyColumns()) {
			terms.add(column + " = :" + HotTierRepository.KEY_PARAMETER_PREFIX + column);
		}
		return String.join(" AND ", terms);
	}

	/**
	 * Key parameters.
	 *
	 * @param  keyValues Key values (JDBC objects).
	 * @return           The parameters.
	 */
	private static MapSqlParameterSource keyParameters(
			final Map<String, Object> keyValues) {
		final MapSqlParameterSource parameters = new MapSqlParameterSource();
		keyValues.forEach((
				column,
				value) -> parameters.addValue(HotTierRepository.KEY_PARAMETER_PREFIX + column, value));
		return parameters;
	}

	/**
	 * {@code (k1, k2)} column tuple.
	 *
	 * @param  table Table.
	 * @return       The tuple.
	 */
	private static String keyTuple(
			final TieredTable table) {
		return "(" + String.join(", ", table.getKeyColumns()) + ")";
	}

	/**
	 * The predicate of an evictable row: archived after its last write, clock before the moment.
	 *
	 * @param  table Table.
	 * @return       The predicate (parameter {@code before}).
	 */
	private static String evictablePredicate(
			final TieredTable table) {
		return table.getArchivedAtColumn() + " IS NOT NULL AND " + table.getArchivedAtColumn() + " >= " + table.getWatermarkColumn() + " AND "
				+ table.clockExpression() + " < :before";
	}

	/**
	 * A timestamp column as a local date time.
	 *
	 * @param  resultSet Result set.
	 * @param  column    Column.
	 * @return           The value, or null.
	 * @throws SQLException If the read fails.
	 */
	private static LocalDateTime localDateTime(
			final ResultSet resultSet,
			final String column) throws SQLException {
		final Timestamp timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	/**
	 * SQL types of a table's columns.
	 *
	 * @param  tableName Table name.
	 * @return           {@code data_type} by column name; empty when the table does not exist.
	 */
	public Map<String, String> columnTypes(
			final String tableName) {
		final Map<String, String> types = new LinkedHashMap<>();
		this.jdbcTemplate.query(
				"SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = :table ORDER BY ordinal_position",
				new MapSqlParameterSource("table", tableName), resultSet -> {
					types.put(resultSet.getString("column_name"), resultSet.getString("data_type"));
				});
		return types;
	}

	/**
	 * The nullable columns of a table.
	 *
	 * @param  tableName Table name.
	 * @return           The column names.
	 */
	public Set<String> nullableColumns(
			final String tableName) {
		final Set<String> columns = new HashSet<>();
		this.jdbcTemplate.query(
				"SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = :table AND is_nullable = 'YES'",
				new MapSqlParameterSource("table", tableName), resultSet -> {
					columns.add(resultSet.getString("column_name"));
				});
		return columns;
	}

	/**
	 * Runs DDL.
	 *
	 * @param statement Statement.
	 */
	public void executeDdl(
			final String statement) {
		this.jdbcTemplate.getJdbcOperations().execute(statement);
	}

	/**
	 * The row locked for archiving: serializes archivers of one key with each other and with the
	 * writers, so the object always follows the latest content.
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @return             The row, or empty.
	 */
	public Optional<HotRow> lockForArchive(
			final TieredTable table,
			final TieredRowKey key) {
		final String sql = "SELECT hot." + table.getWatermarkColumn() + " AS watermark_, CAST(row_to_json(hot) AS text) AS row_, (hot." + table.getArchivedAtColumn()
				+ " IS NOT NULL AND hot." + table.getArchivedAtColumn() + " >= hot." + table.getWatermarkColumn() + ") AS archived_ FROM " + table.getTableName()
				+ " hot WHERE " + HotTierRepository.keyPredicate(table) + " FOR UPDATE";
		final List<HotRow> rows = this.jdbcTemplate.query(sql, HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)), (
				resultSet,
				rowNumber) -> new HotRow(ColumnValues.format(ColumnValues.read(resultSet, "watermark_", table.getColumnTypes().get(table.getWatermarkColumn()))),
						resultSet.getString("row_"), resultSet.getBoolean("archived_")));
		return rows.stream().findFirst();
	}

	/**
	 * Marks a row archived with the watermark of the content uploaded, only if it was not
	 * rewritten since it was read: a rewrite keeps the mark null (or older than its watermark) and
	 * its own upload archives the new content.
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @param  watermark   Watermark read for the archive (string form).
	 * @return             Rows marked (0 when rewritten).
	 */
	public Integer markArchived(
			final TieredTable table,
			final TieredRowKey key,
			final String watermark) {
		final String sql = "UPDATE " + table.getTableName() + " SET " + table.getArchivedAtColumn() + " = :watermark WHERE " + HotTierRepository.keyPredicate(table)
				+ " AND " + table.getWatermarkColumn() + " = :watermark";
		return this.jdbcTemplate.update(sql, HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)).addValue("watermark",
				ColumnValues.parse(table.getColumnTypes().get(table.getWatermarkColumn()), watermark)));
	}

	/**
	 * Starts a row's hot-tier clock after a write and drops its archive mark (the cold copy is
	 * stale until re-uploaded).
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @param  now         Write moment.
	 * @return             Rows updated.
	 */
	public Integer afterWrite(
			final TieredTable table,
			final TieredRowKey key,
			final LocalDateTime now) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			this.entityManager.flush();
		}
		final String sql = "UPDATE " + table.getTableName() + " SET " + table.getArchivedAtColumn() + " = NULL"
				+ (table.isTouching() ? ", " + table.getLastAccessedColumn() + " = :now" : "") + " WHERE " + HotTierRepository.keyPredicate(table);
		return this.jdbcTemplate.update(sql, HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)).addValue("now", now));
	}

	/**
	 * Extends a row's clock; never backwards, so a stale or repeated touch is a no-op.
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @param  now         Access moment.
	 * @return             Rows updated.
	 */
	public Integer touch(
			final TieredTable table,
			final TieredRowKey key,
			final LocalDateTime now) {
		final String sql = "UPDATE " + table.getTableName() + " SET " + table.getLastAccessedColumn() + " = GREATEST(" + table.clockExpression() + ", :now) WHERE "
				+ HotTierRepository.keyPredicate(table);
		return this.jdbcTemplate.update(sql, HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)).addValue("now", now));
	}

	/**
	 * A row's TTL clock.
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @return             The clock, or empty when the row is not hot.
	 */
	public Optional<LocalDateTime> clock(
			final TieredTable table,
			final TieredRowKey key) {
		final String sql = "SELECT " + table.clockExpression() + " AS clock_ FROM " + table.getTableName() + " WHERE " + HotTierRepository.keyPredicate(table);
		final List<LocalDateTime> clocks = this.jdbcTemplate.query(sql, HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)), (
				resultSet,
				rowNumber) -> HotTierRepository.localDateTime(resultSet, "clock_"));
		return clocks.stream().findFirst();
	}

	/**
	 * Rehydrates a cold row from its JSON (every column as archived: no watermark bump), marked
	 * archived at its own watermark (the content is the archived one) with a fresh clock. A
	 * concurrent rehydration of the same key is a no-op.
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @param  rowJson     The whole row as JSON.
	 * @param  now         Access moment.
	 * @return             Rows inserted.
	 */
	public Integer rehydrate(
			final TieredTable table,
			final TieredRowKey key,
			final String rowJson,
			final LocalDateTime now) {
		final MapSqlParameterSource parameters = HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)).addValue("now", now)
				.addValue("rowJson", rowJson);
		final String insert = "INSERT INTO " + table.getTableName() + " SELECT * FROM json_populate_record(NULL::" + table.getTableName()
				+ ", CAST(:rowJson AS json)) ON CONFLICT " + HotTierRepository.keyTuple(table) + " DO NOTHING";
		final Integer inserted = this.jdbcTemplate.update(insert, parameters);
		if (inserted > 0) {
			final String mark = "UPDATE " + table.getTableName() + " SET " + table.getArchivedAtColumn() + " = " + table.getWatermarkColumn()
					+ (table.isTouching() ? ", " + table.getLastAccessedColumn() + " = :now" : "") + " WHERE " + HotTierRepository.keyPredicate(table);
			this.jdbcTemplate.update(mark, parameters);
		}
		return inserted;
	}

	/**
	 * Deletes a hot row.
	 *
	 * @param  table       Table.
	 * @param  key         Row key.
	 * @return             Rows deleted.
	 */
	public Integer delete(
			final TieredTable table,
			final TieredRowKey key) {
		return this.jdbcTemplate.update("DELETE FROM " + table.getTableName() + " WHERE " + HotTierRepository.keyPredicate(table),
				HotTierRepository.keyParameters(ColumnValues.parseKey(table, table.getColumnTypes(), key)));
	}

	/**
	 * Whether any row archived after its last write has a clock before the moment.
	 *
	 * @param  table  Table.
	 * @param  before Moment.
	 * @return        Whether an evictable row exists.
	 */
	public Boolean existsEvictable(
			final TieredTable table,
			final LocalDateTime before) {
		final String sql = "SELECT EXISTS (SELECT 1 FROM " + table.getTableName() + " WHERE " + HotTierRepository.evictablePredicate(table) + " LIMIT 1 FOR UPDATE SKIP LOCKED)";
		return this.jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("before", before), Boolean.class);
	}

	/**
	 * Deletes up to a chunk of evictable rows: archived after their last write, clock before the
	 * moment. The chunk is picked {@code FOR UPDATE SKIP LOCKED} (a row being archived or
	 * rewritten is left to the next run) and the predicate is repeated on the delete itself, so a
	 * row rewritten between the pick and the delete is kept.
	 *
	 * @param  table  Table.
	 * @param  before Moment.
	 * @param  limit  Chunk size.
	 * @return        Rows deleted.
	 */
	public Integer evictExpired(
			final TieredTable table,
			final LocalDateTime before,
			final Long limit) {
		final String sql = "DELETE FROM " + table.getTableName() + " WHERE " + HotTierRepository.keyTuple(table) + " IN (SELECT " + String.join(", ", table.getKeyColumns())
				+ " FROM " + table.getTableName() + " WHERE " + HotTierRepository.evictablePredicate(table) + " LIMIT :limit FOR UPDATE SKIP LOCKED) AND "
				+ HotTierRepository.evictablePredicate(table);
		return this.jdbcTemplate.update(sql, new MapSqlParameterSource("before", before).addValue("limit", limit));
	}

	/**
	 * The next keys after the cursor whose current content is not archived (never, or rewritten
	 * since), in key order.
	 *
	 * @param  table       Table.
	 * @param  cursor      Last key processed; null for the start.
	 * @param  limit       Keys.
	 * @return             The keys.
	 */
	public List<TieredRowKey> findNextUnarchivedKeys(
			final TieredTable table,
			final TieredRowKey cursor,
			final Long limit) {
		final MapSqlParameterSource parameters = new MapSqlParameterSource("limit", limit);
		final StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", table.getKeyColumns())).append(" FROM ").append(table.getTableName())
				.append(" WHERE (").append(table.getArchivedAtColumn()).append(" IS NULL OR ").append(table.getArchivedAtColumn()).append(" < ")
				.append(table.getWatermarkColumn()).append(')');
		if (cursor != null) {
			final List<String> cursorValues = new ArrayList<>();
			for (final String column : table.getKeyColumns()) {
				cursorValues.add(":" + HotTierRepository.KEY_PARAMETER_PREFIX + column);
				parameters.addValue(HotTierRepository.KEY_PARAMETER_PREFIX + column, ColumnValues.parse(table.getColumnTypes().get(column), cursor.values().get(column)));
			}
			sql.append(" AND ").append(HotTierRepository.keyTuple(table)).append(" > (").append(String.join(", ", cursorValues)).append(')');
		}
		sql.append(" ORDER BY ").append(String.join(", ", table.getKeyColumns())).append(" LIMIT :limit");
		return this.jdbcTemplate.query(sql.toString(), parameters, (
				resultSet,
				rowNumber) -> {
			final Map<String, String> values = new LinkedHashMap<>();
			for (final String column : table.getKeyColumns()) {
				values.put(column, ColumnValues.format(ColumnValues.read(resultSet, column, table.getColumnTypes().get(column))));
			}
			return new TieredRowKey(table.getId(), values);
		});
	}

}
