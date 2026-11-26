package org.coldis.library.service.throttle.repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Synchronized throttle repository: data access to the runs shared between
 * instances. It holds no throttle policy (when to push, pull or delete, and how
 * pulled runs merge, are the throttle's decisions).
 *
 * The table is unlogged: rows only live until their key may run again, and
 * losing them only makes keys run earlier, so skipping the WAL is a free
 * performance gain. "Synchronized at" values are set by the database clock (a
 * single time domain across instances, used for the pull watermark), while
 * "last run at" and "next run after" stay in the instances' wall-clock domain.
 */
public class SynchronizedThrottleRepository {

	/**
	 * Advisory lock key that lets a single instance at a time run the cleanup.
	 */
	public static final long CLEANUP_LOCK_KEY = 0x5448524F54544CL;

	/**
	 * Default statement timeout.
	 */
	public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Maximum batch size for pushing runs.
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
	 * A run of a key: when it ran and when the key may run again (wall-clock
	 * epoch millis).
	 *
	 * @param lastRunAt    When the key last ran.
	 * @param nextRunAfter When the key may run again.
	 */
	public record ThrottleRun(long lastRunAt, long nextRunAfter) {}

	/**
	 * A run pulled from the shared store.
	 *
	 * @param throttleKey    Throttle key (throttle name and key).
	 * @param lastRunAt      When the key last ran (wall-clock epoch millis).
	 * @param nextRunAfter   When the key may run again (wall-clock epoch millis).
	 * @param synchronizedAt When the row was last written (database clock epoch
	 *                           millis).
	 */
	public record ThrottleRecord(String throttleKey, long lastRunAt, long nextRunAfter, long synchronizedAt) {}

	/**
	 * Constructor.
	 *
	 * @param dataSource   Data source.
	 * @param tableName    Table name (validated as a plain SQL identifier).
	 * @param queryTimeout Statement timeout (rounded up to whole seconds, zero for
	 *                         none).
	 */
	public SynchronizedThrottleRepository(final DataSource dataSource, final String tableName, final Duration queryTimeout) {
		if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Invalid synchronized throttle table name '" + tableName + "'.");
		}
		this.tableName = tableName;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.jdbcTemplate.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, (queryTimeout.toMillis() + 999) / 1000));
		this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	/**
	 * Constructor with the default statement timeout (5 seconds).
	 *
	 * @param dataSource Data source.
	 * @param tableName  Table name (validated as a plain SQL identifier).
	 */
	public SynchronizedThrottleRepository(final DataSource dataSource, final String tableName) {
		this(dataSource, tableName, SynchronizedThrottleRepository.DEFAULT_QUERY_TIMEOUT);
	}

	/**
	 * Creates the table and its indexes if they do not exist.
	 */
	public void ensureSchema() {
		this.jdbcTemplate.execute("CREATE UNLOGGED TABLE IF NOT EXISTS " + this.tableName
				+ " (throttle_key TEXT PRIMARY KEY, last_run_at BIGINT NOT NULL, next_run_after BIGINT NOT NULL, synchronized_at BIGINT NOT NULL)");
		this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + this.tableName + "_synchronized_at_idx ON " + this.tableName + " (synchronized_at)");
		this.jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + this.tableName + "_next_run_after_idx ON " + this.tableName + " (next_run_after)");
	}

	/**
	 * Checks if the table exists (without creating it).
	 *
	 * @return If the table exists.
	 */
	public boolean tableExists() {
		return Boolean.TRUE.equals(this.jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, this.tableName));
	}

	/**
	 * Pushes runs. A key only moves forward: an existing row is only replaced by
	 * a later run. Runs are written in key order, so concurrent pushes from
	 * several instances lock shared rows in the same order and do not deadlock
	 * each other.
	 *
	 * @param runs Runs by throttle key.
	 */
	public void push(
			final Map<String, ThrottleRun> runs) {
		final List<Map.Entry<String, ThrottleRun>> entries = new ArrayList<>(new TreeMap<>(runs).entrySet());
		final String statement = "INSERT INTO " + this.tableName + " (throttle_key, last_run_at, next_run_after, synchronized_at) VALUES (?, ?, ?, "
				+ SynchronizedThrottleRepository.DATABASE_CLOCK_MILLIS + ") ON CONFLICT (throttle_key) DO UPDATE SET last_run_at = EXCLUDED.last_run_at,"
				+ " next_run_after = EXCLUDED.next_run_after, synchronized_at = EXCLUDED.synchronized_at WHERE EXCLUDED.last_run_at > " + this.tableName
				+ ".last_run_at";
		this.jdbcTemplate.batchUpdate(statement, entries, SynchronizedThrottleRepository.PUSH_BATCH_SIZE, (preparedStatement, entry) -> {
			preparedStatement.setString(1, entry.getKey());
			preparedStatement.setLong(2, entry.getValue().lastRunAt());
			preparedStatement.setLong(3, entry.getValue().nextRunAfter());
		});
	}

	/**
	 * Pulls the runs synchronized after the given watermark.
	 *
	 * @param  synchronizedAfter Watermark (database clock epoch millis).
	 * @return                   The runs.
	 */
	public List<ThrottleRecord> pull(
			final long synchronizedAfter) {
		return this.jdbcTemplate.query(
				"SELECT throttle_key, last_run_at, next_run_after, synchronized_at FROM " + this.tableName + " WHERE synchronized_at > ?",
				(resultSet, rowNumber) -> new ThrottleRecord(resultSet.getString("throttle_key"), resultSet.getLong("last_run_at"),
						resultSet.getLong("next_run_after"), resultSet.getLong("synchronized_at")),
				synchronizedAfter);
	}

	/**
	 * Deletes the rows whose key may run again before the cutoff (computed inside
	 * the cleanup transaction, once its lock is held).
	 *
	 * @param  nextRunBefore Cutoff supplier (epoch millis).
	 * @return               Deleted rows, or null when another instance held the
	 *                       cleanup lock.
	 */
	private Integer deleteNextRunBefore(
			final LongSupplier nextRunBefore) {
		return this.transactionTemplate.execute(status -> {
			Integer deletedRows = null;
			if (Boolean.TRUE.equals(this.jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
					SynchronizedThrottleRepository.CLEANUP_LOCK_KEY))) {
				deletedRows = this.jdbcTemplate.update("DELETE FROM " + this.tableName + " WHERE next_run_after < ?", nextRunBefore.getAsLong());
			}
			return deletedRows;
		});
	}

	/**
	 * Deletes the rows whose key may run again before the cutoff, unless another
	 * instance is running the cleanup at the same time (a transaction-level
	 * advisory lock, never waited for).
	 *
	 * @param  nextRunBefore Cutoff (wall-clock epoch millis).
	 * @return               Deleted rows, or null when another instance held the
	 *                       cleanup lock (nothing was deleted).
	 */
	public Integer deleteNextRunBefore(
			final long nextRunBefore) {
		return this.deleteNextRunBefore(() -> nextRunBefore);
	}

	/**
	 * Deletes the expired rows (whose key may already run again), unless another
	 * instance is running the cleanup at the same time. "Now" is the database
	 * clock: a wall clock (as the runs' times), never an instance's application
	 * clock, and the same for every instance running the cleanup. It is read
	 * from the database and then bound as a value, so the "next run after" index
	 * is used.
	 *
	 * @return Deleted rows, or null when another instance held the cleanup lock
	 *         (nothing was deleted).
	 */
	public Integer deleteExpired() {
		return this.deleteNextRunBefore(
				() -> this.jdbcTemplate.queryForObject("SELECT " + SynchronizedThrottleRepository.DATABASE_CLOCK_MILLIS, Long.class));
	}

}
