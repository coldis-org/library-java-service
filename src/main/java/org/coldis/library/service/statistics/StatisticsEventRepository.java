package org.coldis.library.service.statistics;

import java.time.LocalDateTime;

import org.coldis.library.persistence.repository.PostgresJpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics event repository. Row-locking helpers (e.g. {@code findByIdForUpdateWait}) come from
 * {@link PostgresJpaRepository}.
 */
@Repository
public interface StatisticsEventRepository
    extends PostgresJpaRepository<StatisticsEvent, StatisticsEventKey>,
        StatisticsEventRepositoryCustom {

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
              + "SELECT ctid FROM statistics_event WHERE expired_at IS NOT NULL AND expired_at < :now LIMIT :limit)")
  int deleteExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
