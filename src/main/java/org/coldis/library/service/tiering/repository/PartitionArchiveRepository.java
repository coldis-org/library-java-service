package org.coldis.library.service.tiering.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.service.tiering.model.PartitionArchive;
import org.coldis.library.service.tiering.model.PartitionArchiveState;
import org.coldis.library.service.tiering.model.PartitionManifest.ShardSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JDBC access to {@code cold_tiering_partition_archive}, the per-partition archive record.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.ENABLED,
		matchIfMissing = false
)
public class PartitionArchiveRepository {

	/** Table. */
	public static final String TABLE = "cold_tiering_partition_archive";

	/** Mapper. */
	private static final ObjectMapper OBJECT_MAPPER = ObjectMapperHelper.createMapper();

	/** Columns. */
	private static final String COLUMNS = "table_id, partition_name, range_from, range_to, state, loaded_at, export_started_at, exported_at, verified_at, swept_at,"
			+ " format_version, shard_count, shards_done, row_count, size_bytes, boundaries, shards, sweep_blocks, sweep_cursor, sweep_deleted, sweep_kept, manifest_key";

	/** JDBC. */
	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * Reads a nullable timestamp.
	 *
	 * @param  resultSet Result set.
	 * @param  column    Column.
	 * @return           Moment.
	 * @throws SQLException On error.
	 */
	private static LocalDateTime moment(
			final ResultSet resultSet,
			final String column) throws SQLException {
		final Timestamp timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	/**
	 * Reads a nullable long.
	 *
	 * @param  resultSet Result set.
	 * @param  column    Column.
	 * @return           Value.
	 * @throws SQLException On error.
	 */
	private static Long longValue(
			final ResultSet resultSet,
			final String column) throws SQLException {
		final long value = resultSet.getLong(column);
		return resultSet.wasNull() ? null : value;
	}

	/**
	 * Reads a nullable integer.
	 *
	 * @param  resultSet Result set.
	 * @param  column    Column.
	 * @return           Value.
	 * @throws SQLException On error.
	 */
	private static Integer intValue(
			final ResultSet resultSet,
			final String column) throws SQLException {
		final int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	/**
	 * Parses JSON.
	 *
	 * @param  <Type> Type.
	 * @param  json   JSON.
	 * @param  type   Type.
	 * @return        Value, null for null JSON.
	 */
	private static <Type> Type fromJson(
			final String json,
			final TypeReference<Type> type) {
		try {
			return json == null ? null : PartitionArchiveRepository.OBJECT_MAPPER.readValue(json, type);
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.partition.record.invalid"), exception);
		}
	}

	/**
	 * Writes JSON.
	 *
	 * @param  value Value.
	 * @return       JSON.
	 */
	public static String toJson(
			final Object value) {
		try {
			return PartitionArchiveRepository.OBJECT_MAPPER.writeValueAsString(value);
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.partition.record.invalid"), exception);
		}
	}

	/**
	 * Maps a row.
	 *
	 * @param  resultSet Result set.
	 * @return           Record.
	 * @throws SQLException On error.
	 */
	private static PartitionArchive map(
			final ResultSet resultSet) throws SQLException {
		return new PartitionArchive(resultSet.getString("table_id"), resultSet.getString("partition_name"), resultSet.getString("range_from"),
				resultSet.getString("range_to"), PartitionArchiveState.valueOf(resultSet.getString("state")), PartitionArchiveRepository.moment(resultSet, "loaded_at"),
				PartitionArchiveRepository.moment(resultSet, "export_started_at"), PartitionArchiveRepository.moment(resultSet, "exported_at"),
				PartitionArchiveRepository.moment(resultSet, "verified_at"), PartitionArchiveRepository.moment(resultSet, "swept_at"),
				PartitionArchiveRepository.intValue(resultSet, "format_version"), PartitionArchiveRepository.intValue(resultSet, "shard_count"),
				PartitionArchiveRepository.intValue(resultSet, "shards_done"), PartitionArchiveRepository.longValue(resultSet, "row_count"),
				PartitionArchiveRepository.longValue(resultSet, "size_bytes"),
				PartitionArchiveRepository.fromJson(resultSet.getString("boundaries"), new TypeReference<List<String>>() {
				}), PartitionArchiveRepository.fromJson(resultSet.getString("shards"), new TypeReference<List<ShardSummary>>() {
				}), PartitionArchiveRepository.longValue(resultSet, "sweep_blocks"), PartitionArchiveRepository.longValue(resultSet, "sweep_cursor"),
				PartitionArchiveRepository.longValue(resultSet, "sweep_deleted"), PartitionArchiveRepository.longValue(resultSet, "sweep_kept"),
				resultSet.getString("manifest_key"));
	}

	/**
	 * Key parameters.
	 *
	 * @param  tableId   Table id.
	 * @param  partition Partition.
	 * @return           Parameters.
	 */
	private static MapSqlParameterSource keyOf(
			final String tableId,
			final String partition) {
		return new MapSqlParameterSource().addValue("table_id", tableId).addValue("partition", partition);
	}

	/**
	 * Finds a record.
	 *
	 * @param  tableId   Table id.
	 * @param  partition Partition.
	 * @return           Record.
	 */
	public Optional<PartitionArchive> find(
			final String tableId,
			final String partition) {
		return this.jdbcTemplate.query("SELECT " + PartitionArchiveRepository.COLUMNS + " FROM " + PartitionArchiveRepository.TABLE
				+ " WHERE table_id = :table_id AND partition_name = :partition", PartitionArchiveRepository.keyOf(tableId, partition), (
						resultSet,
						rowNumber) -> PartitionArchiveRepository.map(resultSet)).stream().findFirst();
	}

	/**
	 * Records of a table.
	 *
	 * @param  tableId Table id.
	 * @return         Records, by partition name.
	 */
	public List<PartitionArchive> findAll(
			final String tableId) {
		return this.jdbcTemplate.query("SELECT " + PartitionArchiveRepository.COLUMNS + " FROM " + PartitionArchiveRepository.TABLE
				+ " WHERE table_id = :table_id ORDER BY partition_name", new MapSqlParameterSource("table_id", tableId), (
						resultSet,
						rowNumber) -> PartitionArchiveRepository.map(resultSet));
	}

	/**
	 * Marks a partition loaded (upsert): a new record, or an existing one back to {@code LOADED}
	 * with its export fields cleared (a reload is re-exported).
	 *
	 * @param tableId   Table id.
	 * @param partition Partition.
	 * @param rangeFrom Range lower bound.
	 * @param rangeTo   Range upper bound.
	 * @param loadedAt  Moment.
	 * @param rowCount  Rows, when known.
	 */
	public void markLoaded(
			final String tableId,
			final String partition,
			final String rangeFrom,
			final String rangeTo,
			final LocalDateTime loadedAt,
			final Long rowCount) {
		this.jdbcTemplate.update("INSERT INTO " + PartitionArchiveRepository.TABLE
				+ " (table_id, partition_name, range_from, range_to, state, loaded_at, row_count, created_at, updated_at)"
				+ " VALUES (:table_id, :partition, :range_from, :range_to, 'LOADED', :loaded_at, :row_count, :now, :now)"
				+ " ON CONFLICT (table_id, partition_name) DO UPDATE SET state = 'LOADED', loaded_at = EXCLUDED.loaded_at, row_count = EXCLUDED.row_count,"
				+ " exported_at = NULL, verified_at = NULL, swept_at = NULL, sweep_cursor = NULL, sweep_blocks = NULL, updated_at = EXCLUDED.updated_at",
				PartitionArchiveRepository.keyOf(tableId, partition).addValue("range_from", rangeFrom).addValue("range_to", rangeTo).addValue("loaded_at", loadedAt)
						.addValue("row_count", rowCount).addValue("now", loadedAt));
	}

	/**
	 * Starts an export (upsert to {@code EXPORTING}) with its boundaries.
	 *
	 * @param tableId    Table id.
	 * @param partition  Partition.
	 * @param rangeFrom  Range lower bound.
	 * @param rangeTo    Range upper bound.
	 * @param startedAt  Moment.
	 * @param boundaries Shard boundaries.
	 * @param rowCount   Rows counted by the boundaries pass.
	 */
	public void startExport(
			final String tableId,
			final String partition,
			final String rangeFrom,
			final String rangeTo,
			final LocalDateTime startedAt,
			final List<String> boundaries,
			final Long rowCount) {
		this.jdbcTemplate.update("INSERT INTO " + PartitionArchiveRepository.TABLE
				+ " (table_id, partition_name, range_from, range_to, state, export_started_at, shard_count, shards_done, row_count, boundaries, shards, created_at, updated_at)"
				+ " VALUES (:table_id, :partition, :range_from, :range_to, 'EXPORTING', :started_at, :shard_count, 0, :row_count, CAST(:boundaries AS jsonb), CAST('[]' AS jsonb), :started_at, :started_at)"
				+ " ON CONFLICT (table_id, partition_name) DO UPDATE SET state = 'EXPORTING', export_started_at = EXCLUDED.export_started_at,"
				+ " shard_count = EXCLUDED.shard_count, shards_done = 0, row_count = EXCLUDED.row_count, boundaries = EXCLUDED.boundaries, shards = EXCLUDED.shards,"
				+ " size_bytes = NULL, exported_at = NULL, verified_at = NULL, swept_at = NULL, sweep_cursor = NULL, sweep_blocks = NULL, updated_at = EXCLUDED.updated_at",
				PartitionArchiveRepository.keyOf(tableId, partition).addValue("range_from", rangeFrom).addValue("range_to", rangeTo).addValue("started_at", startedAt)
						.addValue("shard_count", boundaries.size()).addValue("row_count", rowCount).addValue("boundaries", PartitionArchiveRepository.toJson(boundaries)));
	}

	/**
	 * Records a completed shard (idempotent per shard number) and returns how many are done, or
	 * null when the record is not exporting or the shard was already recorded.
	 *
	 * @param  tableId   Table id.
	 * @param  partition Partition.
	 * @param  summary   Shard summary.
	 * @param  now       Moment.
	 * @return           Shards done after this one.
	 */
	public Integer completeShard(
			final String tableId,
			final String partition,
			final ShardSummary summary,
			final LocalDateTime now) {
		return this.jdbcTemplate.query("UPDATE " + PartitionArchiveRepository.TABLE
				+ " SET shards = shards || CAST(:summary AS jsonb), shards_done = shards_done + 1, updated_at = :now"
				+ " WHERE table_id = :table_id AND partition_name = :partition AND state = 'EXPORTING'"
				+ " AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(shards) done WHERE CAST(done ->> 'shard' AS integer) = :shard) RETURNING shards_done",
				PartitionArchiveRepository.keyOf(tableId, partition).addValue("summary", PartitionArchiveRepository.toJson(List.of(summary))).addValue("shard", summary.shard())
						.addValue("now", now),
				(
						resultSet,
						rowNumber) -> resultSet.getInt("shards_done")).stream().findFirst().orElse(null);
	}

	/**
	 * Marks the export verified.
	 *
	 * @param tableId     Table id.
	 * @param partition   Partition.
	 * @param manifestKey Manifest key.
	 * @param rowCount    Rows.
	 * @param sizeBytes   Bytes.
	 * @param now         Moment.
	 */
	public void markVerified(
			final String tableId,
			final String partition,
			final String manifestKey,
			final Long rowCount,
			final Long sizeBytes,
			final LocalDateTime now) {
		this.jdbcTemplate.update("UPDATE " + PartitionArchiveRepository.TABLE
				+ " SET state = 'VERIFIED', manifest_key = :manifest_key, row_count = :row_count, size_bytes = :size_bytes, format_version = :format_version,"
				+ " exported_at = :now, verified_at = :now, updated_at = :now WHERE table_id = :table_id AND partition_name = :partition",
				PartitionArchiveRepository.keyOf(tableId, partition).addValue("manifest_key", manifestKey).addValue("row_count", rowCount).addValue("size_bytes", sizeBytes)
						.addValue("format_version", org.coldis.library.service.tiering.model.PartitionManifest.FORMAT_VERSION).addValue("now", now));
	}

	/**
	 * Sets a record's state.
	 *
	 * @param  tableId   Table id.
	 * @param  partition Partition.
	 * @param  state     State.
	 * @param  now       Moment.
	 * @return           Rows updated.
	 */
	public Integer markState(
			final String tableId,
			final String partition,
			final PartitionArchiveState state,
			final LocalDateTime now) {
		return this.jdbcTemplate.update(
				"UPDATE " + PartitionArchiveRepository.TABLE + " SET state = :state, updated_at = :now WHERE table_id = :table_id AND partition_name = :partition",
				PartitionArchiveRepository.keyOf(tableId, partition).addValue("state", state.name()).addValue("now", now));
	}

	/**
	 * Saves sweep progress.
	 *
	 * @param tableId   Table id.
	 * @param partition Partition.
	 * @param blocks    Heap blocks at the sweep start.
	 * @param cursor    Next block.
	 * @param deleted   Deleted so far.
	 * @param kept      Kept so far.
	 * @param sweptAt   Finish moment, null while running.
	 * @param now       Moment.
	 */
	public void saveSweep(
			final String tableId,
			final String partition,
			final Long blocks,
			final Long cursor,
			final Long deleted,
			final Long kept,
			final LocalDateTime sweptAt,
			final LocalDateTime now) {
		this.jdbcTemplate.update("UPDATE " + PartitionArchiveRepository.TABLE
				+ " SET sweep_blocks = :blocks, sweep_cursor = :cursor, sweep_deleted = :deleted, sweep_kept = :kept, swept_at = :swept_at, updated_at = :now"
				+ " WHERE table_id = :table_id AND partition_name = :partition",
				PartitionArchiveRepository.keyOf(tableId, partition).addValue("blocks", blocks).addValue("cursor", cursor).addValue("deleted", deleted).addValue("kept", kept)
						.addValue("swept_at", sweptAt).addValue("now", now));
	}

	/**
	 * Deletes the records of a table (tests).
	 *
	 * @param tableId Table id.
	 */
	public void deleteAll(
			final String tableId) {
		this.jdbcTemplate.update("DELETE FROM " + PartitionArchiveRepository.TABLE + " WHERE table_id = :table_id", new MapSqlParameterSource("table_id", tableId));
	}

}
