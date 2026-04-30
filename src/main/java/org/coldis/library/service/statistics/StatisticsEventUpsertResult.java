package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-key result of {@link StatisticsEventServiceComponent#upsertEventBatch} — captures whether
 * the row was applied (vs. filtered out as stale), whether it was a fresh insert (vs. an update),
 * and the pre-update state needed for summary delta computation.
 */
public record StatisticsEventUpsertResult(
    String context,
    String ownerKey,
    String dimensionName,
    boolean applied,
    boolean wasInserted,
    String oldDimensionValue,
    BigDecimal oldWeight,
    LocalDateTime oldDateTime) {

  StatisticsEventKey key() {
    return new StatisticsEventKey(this.context, this.ownerKey, this.dimensionName);
  }
}
