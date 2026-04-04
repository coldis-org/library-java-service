package org.coldis.library.service.statistics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

/** Statistics event summary repository. */
@Repository
public interface StatisticsEventSummaryRepository
    extends JpaRepository<StatisticsEventSummary, StatisticsEventSummaryKey> {

  /**
   * Finds the statistics event summary for update (with pessimistic write lock).
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param dateTime Date time.
   * @return The statistics event summary, if found.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Transactional(propagation = Propagation.REQUIRED, timeout = 11)
  @Query(
      "SELECT summary FROM StatisticsEventSummary summary "
          + "WHERE context = :context "
          + "AND dimensionName = :dimensionName "
          + "AND dateTime = :dateTime")
  Optional<StatisticsEventSummary> findByIdForUpdate(
      @Param("context") String context,
      @Param("dimensionName") String dimensionName,
      @Param("dateTime") LocalDateTime dateTime);

  /**
   * Finds all statistics event summaries for a context and dimension within a date range.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param startDateTime Start date time (inclusive).
   * @param endDateTime End date time (inclusive).
   * @return The list of summaries in the period.
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
