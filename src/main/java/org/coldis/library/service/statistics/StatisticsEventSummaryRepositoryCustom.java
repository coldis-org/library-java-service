package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Custom statistics event summary repository operations (native, Postgres-specific). */
public interface StatisticsEventSummaryRepositoryCustom {

  /**
   * One row of comparison sufficient statistics, as computed in Postgres. The heavy data reduction
   * (window bucketing, JSONB map merge, per-value sums) happens in the database; the caller turns
   * these compact sums into averages, standard deviations, ratios and z-scores. Returned per metric
   * ({@code "count"} / {@code "weight"}) and per dimension value, plus one total row per metric with a
   * {@code null} {@link #dimensionValue()}.
   *
   * <p>{@code sample*} fields aggregate over the sample (historical) windows; they are {@code null}
   * when the value never appears in the sample windows. {@link #referenceValue()} is the value in the
   * reference window; {@code null} when the value never appears there. So a value present only in the
   * samples has {@code null} {@link #referenceValue()}, and a value present only in the reference has
   * {@code null} {@code sample*}.
   *
   * @param metric Metric: {@code "count"} or {@code "weight"}.
   * @param dimensionValue Dimension value, or {@code null} for the metric's total row.
   * @param sampleSum Σ of the per-window value (count/weight) over the sample windows.
   * @param sampleSumSquare Σ of the per-window value squared over the sample windows.
   * @param sampleRatioSum Σ of the per-window ratio (value/window total) over the sample windows.
   * @param sampleRatioSumSquare Σ of the per-window ratio squared over the sample windows.
   * @param referenceValue The value in the reference window (or the reference total for total rows).
   */
  record ComparisonStatistic(
      String metric,
      String dimensionValue,
      BigDecimal sampleSum,
      BigDecimal sampleSumSquare,
      BigDecimal sampleRatioSum,
      BigDecimal sampleRatioSumSquare,
      BigDecimal referenceValue) {}

  /**
   * Computes the per-value comparison sufficient statistics for a dimension in one query: counts and
   * weights, summed (and sum-of-squares) over the sample windows for both raw values and ratios, plus
   * the reference window's values, plus the metric totals — all reduced in Postgres so the caller
   * transfers a handful of numbers per value instead of the per-window maps.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param sampleStarts Sample (historical) window start bounds (inclusive), aligned with {@code sampleEnds}.
   * @param sampleEnds Sample (historical) window end bounds (inclusive), aligned with {@code sampleStarts}.
   * @param referenceStart Reference window start bound (inclusive).
   * @param referenceEnd Reference window end bound (inclusive).
   * @return The sufficient-statistic rows (per metric × value, plus per-metric totals).
   */
  List<ComparisonStatistic> compareStatistics(
      String context,
      String dimensionName,
      LocalDateTime[] sampleStarts,
      LocalDateTime[] sampleEnds,
      LocalDateTime referenceStart,
      LocalDateTime referenceEnd);
}
