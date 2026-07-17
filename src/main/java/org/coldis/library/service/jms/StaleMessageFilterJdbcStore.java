package org.coldis.library.service.jms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stale message filter JDBC store (shared processing records).
 *
 * The table is unlogged: records are a lossy optimization cache (the filter
 * fails open), so skipping the WAL is a free performance gain. All
 * "synchronized at" values come from the database clock (a single time domain
 * across instances), while "processed at" values stay in the producers' and
 * consumers' wall-clock domain.
 */
public class StaleMessageFilterJdbcStore {

	/**
	 * Maximum batch size for pushing processing records.
	 */
	private static final int PUSH_BATCH_SIZE = 1000;

	/**
	 * Database clock as epoch millis.
	 */
	private static final String DATABASE_CLOCK_MILLIS = "(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT";

	/**
	 * Table name.
	 */
	private final String tableName;

	/**
	 * JDBC template.
	 */
	private final JdbcTemplate jdbcTemplate;

	/**
	 * Processing record entry pulled from the shared store.
	 */
	public record ProcessingRecord(String messageKey, long processedAt, long synchronizedAt) {}

	/**
	 * Default constructor.
	 *
	 * @param dataSource Data source.
	 * @param tableName  Table name (validated as a plain SQL identifier).
	 */
	public StaleMessageFilterJdbcStore(final DataSource dataSource, final String tableName) {
		if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Invalid stale message filter table name '" + tableName + "'.");
		}
		this.tableName = tableName;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * Creates the table and index if they do not exist.
	 */
	public void ensureSchema() {
		this.jdbcTemplate.execute("CREATE UNLOGGED TABLE IF NOT EXISTS " + this.tableName
				+ " (message_key VARCHAR(512) PRIMARY KEY, processed_at BIGINT NOT NULL, synchronized_at BIGINT NOT NULL)");
		this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + this.tableName + "_synchronized_at_idx ON " + this.tableName + " (synchronized_at)");
	}

	/**
	 * Pushes processing records, keeping the newest processing timestamp per key.
	 *
	 * @param processingRecords Processing start timestamp by message key.
	 */
	public void push(
			final Map<String, Long> processingRecords) {
		final List<Map.Entry<String, Long>> entries = new ArrayList<>(processingRecords.entrySet());
		final String statement = "INSERT INTO " + this.tableName + " (message_key, processed_at, synchronized_at) VALUES (?, ?, "
				+ StaleMessageFilterJdbcStore.DATABASE_CLOCK_MILLIS + ") ON CONFLICT (message_key) DO UPDATE SET processed_at = EXCLUDED.processed_at,"
				+ " synchronized_at = EXCLUDED.synchronized_at WHERE " + this.tableName + ".processed_at < EXCLUDED.processed_at";
		this.jdbcTemplate.batchUpdate(statement, entries, StaleMessageFilterJdbcStore.PUSH_BATCH_SIZE, (preparedStatement, entry) -> {
			preparedStatement.setString(1, entry.getKey());
			preparedStatement.setLong(2, entry.getValue());
		});
	}

	/**
	 * Pulls processing records synchronized after the given watermark.
	 *
	 * @param  synchronizedAfter Watermark (database clock epoch millis).
	 * @return                   Records synchronized after the watermark.
	 */
	public List<ProcessingRecord> pull(
			final long synchronizedAfter) {
		return this.jdbcTemplate.query("SELECT message_key, processed_at, synchronized_at FROM " + this.tableName + " WHERE synchronized_at > ?",
				(resultSet, rowNumber) -> new ProcessingRecord(resultSet.getString("message_key"), resultSet.getLong("processed_at"),
						resultSet.getLong("synchronized_at")),
				synchronizedAfter);
	}

	/**
	 * Deletes records synchronized longer than the window ago (by the database
	 * clock).
	 *
	 * @param windowMillis Window in millis.
	 */
	public void deleteOlderThanWindow(
			final long windowMillis) {
		this.jdbcTemplate.update(
				"DELETE FROM " + this.tableName + " WHERE synchronized_at < (" + StaleMessageFilterJdbcStore.DATABASE_CLOCK_MILLIS + " - ?)", windowMillis);
	}

}
