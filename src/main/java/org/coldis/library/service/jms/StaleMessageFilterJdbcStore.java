package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stale message filter JDBC store (shared processing records).
 *
 * The table is unlogged: records are a lossy optimization cache (the filter
 * fails open), so skipping the WAL is a free performance gain. "Synchronized
 * at" values are set by the database clock (a single time domain across
 * instances), while "processed at" values stay in the producers' and
 * consumers' wall-clock domain.
 */
public class StaleMessageFilterJdbcStore {

	/**
	 * Advisory lock key that lets a single instance at a time run the cleanup.
	 */
	public static final long CLEANUP_LOCK_KEY = 0x5354414C4546L;

	/**
	 * Default statement timeout.
	 */
	private static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);

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
	 * Transaction template (the cleanup and its advisory lock share a
	 * transaction).
	 */
	private final TransactionTemplate transactionTemplate;

	/**
	 * Processing record entry pulled from the shared store.
	 */
	public record ProcessingRecord(String messageKey, long processedAt, long synchronizedAt) {}

	/**
	 * Constructor.
	 *
	 * @param dataSource   Data source.
	 * @param tableName    Table name (validated as a plain SQL identifier).
	 * @param queryTimeout Statement timeout (whole seconds, zero for none).
	 */
	public StaleMessageFilterJdbcStore(final DataSource dataSource, final String tableName, final Duration queryTimeout) {
		if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Invalid stale message filter table name '" + tableName + "'.");
		}
		this.tableName = tableName;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.jdbcTemplate.setQueryTimeout((int) queryTimeout.toSeconds());
		this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	/**
	 * Constructor with the default statement timeout (5 seconds).
	 *
	 * @param dataSource Data source.
	 * @param tableName  Table name (validated as a plain SQL identifier).
	 */
	public StaleMessageFilterJdbcStore(final DataSource dataSource, final String tableName) {
		this(dataSource, tableName, StaleMessageFilterJdbcStore.DEFAULT_QUERY_TIMEOUT);
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
	 * Records are written in key order, so concurrent pushes from several
	 * instances lock shared rows in the same order and do not deadlock each
	 * other.
	 *
	 * @param processingRecords Processing start timestamp by message key.
	 */
	public void push(
			final Map<String, Long> processingRecords) {
		final List<Map.Entry<String, Long>> entries = new ArrayList<>(new TreeMap<>(processingRecords).entrySet());
		final String statement = "INSERT INTO " + this.tableName + " (message_key, processed_at, synchronized_at) VALUES (?, ?, "
				+ StaleMessageFilterJdbcStore.DATABASE_CLOCK_MILLIS + ") ON CONFLICT (message_key) DO UPDATE SET processed_at = EXCLUDED.processed_at,"
				+ " synchronized_at = EXCLUDED.synchronized_at WHERE " + this.tableName + ".processed_at < EXCLUDED.processed_at";
		this.jdbcTemplate.batchUpdate(statement, entries, StaleMessageFilterJdbcStore.PUSH_BATCH_SIZE, (preparedStatement, entry) -> {
			preparedStatement.setString(1, entry.getKey());
			preparedStatement.setLong(2, entry.getValue());
		});
	}

	/**
	 * Escapes the LIKE wildcards (and the escape character itself) of a value.
	 *
	 * @param  value Value.
	 * @return       The value, matched literally by LIKE.
	 */
	private static String escapeLikePattern(
			final String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/**
	 * Pulls processing records whose message key starts with one of the given
	 * prefixes and that were synchronized after the given watermark.
	 *
	 * @param  synchronizedAfter Watermark (database clock epoch millis).
	 * @param  keyPrefixes       Message key prefixes (matched literally).
	 * @return                   The matching records.
	 */
	public List<ProcessingRecord> pull(
			final long synchronizedAfter,
			final Collection<String> keyPrefixes) {
		final Object[] patterns = keyPrefixes.stream().map(keyPrefix -> StaleMessageFilterJdbcStore.escapeLikePattern(keyPrefix) + "%").toArray();
		return this.jdbcTemplate.query(
				"SELECT message_key, processed_at, synchronized_at FROM " + this.tableName + " WHERE synchronized_at > ? AND message_key LIKE ANY (?)",
				preparedStatement -> {
					preparedStatement.setLong(1, synchronizedAfter);
					preparedStatement.setArray(2, preparedStatement.getConnection().createArrayOf("text", patterns));
				}, (resultSet, rowNumber) -> new ProcessingRecord(resultSet.getString("message_key"), resultSet.getLong("processed_at"),
						resultSet.getLong("synchronized_at")));
	}

	/**
	 * Deletes records synchronized before the cutoff, unless another instance is
	 * running the cleanup at the same time (a transaction-level advisory lock,
	 * never waited for). The cutoff is a bound value, so the "synchronized at"
	 * index is used.
	 *
	 * @param  synchronizedBefore Cutoff (epoch millis).
	 * @return                    Deleted rows, or null when another instance held
	 *                            the cleanup lock (nothing was deleted).
	 */
	public Integer deleteSynchronizedBefore(
			final long synchronizedBefore) {
		return this.transactionTemplate.execute(status -> {
			Integer deletedRows = null;
			if (Boolean.TRUE.equals(this.jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
					StaleMessageFilterJdbcStore.CLEANUP_LOCK_KEY))) {
				deletedRows = this.jdbcTemplate.update("DELETE FROM " + this.tableName + " WHERE synchronized_at < ?", synchronizedBefore);
			}
			return deletedRows;
		});
	}

}
