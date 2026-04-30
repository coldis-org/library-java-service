package org.coldis.library.service.statistics;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

/** Statistics event repository. */
@Repository
public interface StatisticsEventRepository
    extends JpaRepository<StatisticsEvent, StatisticsEventKey>, StatisticsEventRepositoryCustom {

  /**
   * Finds the statistics event for update (with pessimistic write lock).
   *
   * @param context Context.
   * @param ownerKey Owner key.
   * @param dimensionName Dimension name.
   * @return The statistics event, if found.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Transactional(propagation = Propagation.REQUIRED, timeout = 11)
  @Query(
      "SELECT statisticsEvent FROM StatisticsEvent statisticsEvent "
          + "WHERE context = :context "
          + "AND ownerKey = :ownerKey "
          + "AND dimensionName = :dimensionName")
  Optional<StatisticsEvent> findByIdForUpdate(
      @Param("context") String context,
      @Param("ownerKey") String ownerKey,
      @Param("dimensionName") String dimensionName);

  /**
   * Deletes a batch of expired statistics events.
   *
   * @param now Current date time.
   * @param limit Maximum number of rows to delete.
   * @return The number of deleted rows.
   */
  @Modifying
  @Transactional(propagation = Propagation.REQUIRED)
  @Query(
      nativeQuery = true,
      value =
          "DELETE FROM statistics_event WHERE ctid IN ("
              + "SELECT ctid FROM statistics_event WHERE expired_at < :now LIMIT :limit)")
  int deleteExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
