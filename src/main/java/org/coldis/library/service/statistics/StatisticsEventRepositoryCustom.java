package org.coldis.library.service.statistics;

import java.util.List;

/**
 * Custom statistics event repository operations not expressible as Spring Data derived queries.
 */
public interface StatisticsEventRepositoryCustom {

  /**
   * Batched MERGE upsert. Caller must hold the per-key advisory lock (via
   * {@code AdvisoryLockServiceComponent}) for every input key — the MERGE relies on that lock to
   * keep the {@code existing} snapshot stable.
   *
   * <p>Applies the latest-emission-wins ordering rule: rows whose incoming {@code emittedAt} is
   * older than the persisted value are filtered out by the MERGE predicate and reported back as
   * {@code applied = false}.
   *
   * @param events Events to upsert.
   * @return One result row per input event, in input order, with the pre-update state captured for
   *     summary-delta computation.
   */
  List<StatisticsEventUpsertResult> upsertBatch(List<StatisticsEvent> events);
}
