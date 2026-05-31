package org.coldis.library.service.statistics;

import java.time.LocalDateTime;
import java.util.List;

import org.coldis.library.persistence.repository.PostgresJpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics event summary repository. Row-locking and find-or-create helpers (e.g. {@code
 * findByIdForUpdateWait}, {@code findByIdForUpdateOrCreate}) come from {@link PostgresJpaRepository};
 * {@link #insertIfAbsent} is the idempotent insert they pair with.
 */
@Repository
public interface StatisticsEventSummaryRepository
    extends PostgresJpaRepository<StatisticsEventSummary, StatisticsEventSummaryKey> {

  /**
   * Atomically inserts a summary row if absent, no-op if a row with the same composite key already
   * exists. Pairs with {@code findByIdForUpdateOrCreate} as its idempotent insert: a single
   * round-trip handled by Postgres' {@code ON CONFLICT DO NOTHING}, with no nested transactions or
   * exception bouncing.
   *
   * <p>Native SQL bypasses {@code EntityTimestampListener}, so {@code created_at} / {@code
   * updated_at} are set inline via Postgres {@code now()}.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param dateTime Date time (already truncated).
   */
  @Modifying
  @Transactional(propagation = Propagation.REQUIRED)
  @Query(
      nativeQuery = true,
      value =
          "INSERT INTO statistics_event_summary "
              + "(context, dimension_name, date_time, created_at, updated_at) "
              + "VALUES (:context, :dimensionName, :dateTime, now(), now()) "
              + "ON CONFLICT (context, dimension_name, date_time) DO NOTHING")
  void insertIfAbsent(
      @Param("context") String context,
      @Param("dimensionName") String dimensionName,
      @Param("dateTime") LocalDateTime dateTime);

  /**
   * Finds all statistics event summaries for a context and dimension within a date range. A single
   * index-friendly range scan over {@code (context, dimension_name, date_time)} that collapses what
   * used to be one query per sample window into one round-trip per dimension; callers bucket the
   * returned rows into their windows in memory.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param startDateTime Start date time (inclusive).
   * @param endDateTime End date time (inclusive).
   * @return The list of summaries in the period for the dimension.
   */
  @Query(
      "SELECT summary FROM StatisticsEventSummary summary "
          + "WHERE context = :context "
          + "AND dimensionName = :dimensionName "
          + "AND dateTime >= :startDateTime "
          + "AND dateTime <= :endDateTime")
  List<StatisticsEventSummary> findByPeriod(
      @Param("context") String context,
      @Param("dimensionName") String dimensionName,
      @Param("startDateTime") LocalDateTime startDateTime,
      @Param("endDateTime") LocalDateTime endDateTime);
}
