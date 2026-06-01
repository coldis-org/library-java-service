package org.coldis.library.service.statistics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Custom statistics event summary repository implementation. Holds the comparison sufficient-statistics
 * query that pushes the window bucketing, JSONB map merge, and per-value summation into Postgres
 * (parallel-array binding via {@code unnest}, JSONB iteration via {@code jsonb_each}), so the
 * comparison path transfers a few sums per value instead of every raw bucket row or per-window map.
 */
public class StatisticsEventSummaryRepositoryImpl implements StatisticsEventSummaryRepositoryCustom {

  /**
   * Sufficient statistics for drift comparison, computed in Postgres. Shared window CTEs feed a count
   * branch and a weight branch; each emits per-value rows (sums and sums-of-squares of the value and
   * its within-window ratio over the sample windows, plus the reference window's value) and one total
   * row (a {@code null} dimension value carrying the window-total sums and the reference total). The
   * caller derives averages, standard deviations and z-scores from these. Ratios use {@code value /
   * window total}; absent windows contribute 0 to every sum (so dividing by the window count later
   * zero-fills them). Squares use {@code x * x} to stay in exact {@code numeric}.
   */
  private static final String COMPARE_STATISTICS_SQL =
      "WITH sample_window AS ("
          + " SELECT * FROM unnest(CAST(:sampleStarts AS timestamp[]), CAST(:sampleEnds AS timestamp[]))"
          + "   WITH ORDINALITY AS sample_window(window_start, window_end, window_index)"
          + "), sample_window_total AS ("
          + " SELECT sample_window.window_index AS window_index,"
          + "        COALESCE(SUM(summary.total_count), 0) AS window_total_count,"
          + "        COALESCE(SUM(summary.total_weight), 0) AS window_total_weight"
          + " FROM sample_window"
          + " LEFT JOIN statistics_event_summary summary"
          + "   ON summary.context = :context AND summary.dimension_name = :dimensionName"
          + "  AND summary.date_time >= sample_window.window_start"
          + "  AND summary.date_time <= sample_window.window_end"
          + " GROUP BY sample_window.window_index"
          + "), sample_window_value_count AS ("
          + " SELECT sample_window.window_index AS window_index,"
          + "        count_entry.dimension_value AS dimension_value, SUM(count_entry.raw_value::bigint) AS value_count"
          + " FROM sample_window"
          + " JOIN statistics_event_summary summary"
          + "   ON summary.context = :context AND summary.dimension_name = :dimensionName"
          + "  AND summary.date_time >= sample_window.window_start"
          + "  AND summary.date_time <= sample_window.window_end,"
          + " LATERAL jsonb_each_text(summary.value_counts) AS count_entry(dimension_value, raw_value)"
          + " GROUP BY sample_window.window_index, count_entry.dimension_value"
          + "), sample_window_value_weight AS ("
          + " SELECT sample_window.window_index AS window_index,"
          + "        weight_entry.dimension_value AS dimension_value, SUM(weight_entry.raw_value::numeric) AS value_weight"
          + " FROM sample_window"
          + " JOIN statistics_event_summary summary"
          + "   ON summary.context = :context AND summary.dimension_name = :dimensionName"
          + "  AND summary.date_time >= sample_window.window_start"
          + "  AND summary.date_time <= sample_window.window_end,"
          + " LATERAL jsonb_each_text(summary.value_weights) AS weight_entry(dimension_value, raw_value)"
          + " GROUP BY sample_window.window_index, weight_entry.dimension_value"
          + "), sample_count_ratio AS ("
          + " SELECT sample_window_value_count.dimension_value AS dimension_value,"
          + "        sample_window_value_count.value_count AS value,"
          + "        COALESCE(sample_window_value_count.value_count::numeric"
          + "                 / NULLIF(sample_window_total.window_total_count, 0), 0) AS value_ratio"
          + " FROM sample_window_value_count"
          + " JOIN sample_window_total ON sample_window_total.window_index = sample_window_value_count.window_index"
          + "), sample_weight_ratio AS ("
          + " SELECT sample_window_value_weight.dimension_value AS dimension_value,"
          + "        sample_window_value_weight.value_weight AS value,"
          + "        COALESCE(sample_window_value_weight.value_weight"
          + "                 / NULLIF(sample_window_total.window_total_weight, 0), 0) AS value_ratio"
          + " FROM sample_window_value_weight"
          + " JOIN sample_window_total ON sample_window_total.window_index = sample_window_value_weight.window_index"
          + "), sample_count_statistic AS ("
          + " SELECT dimension_value, SUM(value) AS sample_sum, SUM(value * value) AS sample_sum_square,"
          + "        SUM(value_ratio) AS sample_ratio_sum, SUM(value_ratio * value_ratio) AS sample_ratio_sum_square"
          + " FROM sample_count_ratio GROUP BY dimension_value"
          + "), sample_weight_statistic AS ("
          + " SELECT dimension_value, SUM(value) AS sample_sum, SUM(value * value) AS sample_sum_square,"
          + "        SUM(value_ratio) AS sample_ratio_sum, SUM(value_ratio * value_ratio) AS sample_ratio_sum_square"
          + " FROM sample_weight_ratio GROUP BY dimension_value"
          + "), reference_count AS ("
          + " SELECT count_entry.dimension_value AS dimension_value, SUM(count_entry.raw_value::bigint) AS reference_value"
          + " FROM statistics_event_summary summary,"
          + " LATERAL jsonb_each_text(summary.value_counts) AS count_entry(dimension_value, raw_value)"
          + " WHERE summary.context = :context AND summary.dimension_name = :dimensionName"
          + "   AND summary.date_time >= :referenceStart AND summary.date_time <= :referenceEnd"
          + " GROUP BY count_entry.dimension_value"
          + "), reference_weight AS ("
          + " SELECT weight_entry.dimension_value AS dimension_value, SUM(weight_entry.raw_value::numeric) AS reference_value"
          + " FROM statistics_event_summary summary,"
          + " LATERAL jsonb_each_text(summary.value_weights) AS weight_entry(dimension_value, raw_value)"
          + " WHERE summary.context = :context AND summary.dimension_name = :dimensionName"
          + "   AND summary.date_time >= :referenceStart AND summary.date_time <= :referenceEnd"
          + " GROUP BY weight_entry.dimension_value"
          + "), sample_total AS ("
          + " SELECT SUM(window_total_count) AS sum_count, SUM(window_total_count * window_total_count) AS sum_square_count,"
          + "        SUM(window_total_weight) AS sum_weight, SUM(window_total_weight * window_total_weight) AS sum_square_weight"
          + " FROM sample_window_total"
          + "), reference_total AS ("
          + " SELECT COALESCE(SUM(summary.total_count), 0) AS total_count, COALESCE(SUM(summary.total_weight), 0) AS total_weight"
          + " FROM statistics_event_summary summary"
          + " WHERE summary.context = :context AND summary.dimension_name = :dimensionName"
          + "   AND summary.date_time >= :referenceStart AND summary.date_time <= :referenceEnd"
          + ") SELECT 'count' AS metric,"
          + "   COALESCE(sample_count_statistic.dimension_value, reference_count.dimension_value) AS dimension_value,"
          + "   sample_count_statistic.sample_sum, sample_count_statistic.sample_sum_square,"
          + "   sample_count_statistic.sample_ratio_sum, sample_count_statistic.sample_ratio_sum_square,"
          + "   reference_count.reference_value"
          + " FROM sample_count_statistic"
          + " FULL OUTER JOIN reference_count ON sample_count_statistic.dimension_value = reference_count.dimension_value"
          + " UNION ALL SELECT 'weight' AS metric,"
          + "   COALESCE(sample_weight_statistic.dimension_value, reference_weight.dimension_value) AS dimension_value,"
          + "   sample_weight_statistic.sample_sum, sample_weight_statistic.sample_sum_square,"
          + "   sample_weight_statistic.sample_ratio_sum, sample_weight_statistic.sample_ratio_sum_square,"
          + "   reference_weight.reference_value"
          + " FROM sample_weight_statistic"
          + " FULL OUTER JOIN reference_weight ON sample_weight_statistic.dimension_value = reference_weight.dimension_value"
          + " UNION ALL SELECT 'count' AS metric, CAST(NULL AS text) AS dimension_value,"
          + "   sample_total.sum_count, sample_total.sum_square_count,"
          + "   CAST(NULL AS numeric), CAST(NULL AS numeric), reference_total.total_count"
          + " FROM sample_total, reference_total"
          + " UNION ALL SELECT 'weight' AS metric, CAST(NULL AS text) AS dimension_value,"
          + "   sample_total.sum_weight, sample_total.sum_square_weight,"
          + "   CAST(NULL AS numeric), CAST(NULL AS numeric), reference_total.total_weight"
          + " FROM sample_total, reference_total";

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<ComparisonStatistic> compareStatistics(
      final String context,
      final String dimensionName,
      final LocalDateTime[] sampleStarts,
      final LocalDateTime[] sampleEnds,
      final LocalDateTime referenceStart,
      final LocalDateTime referenceEnd) {
    if ((sampleStarts == null) || (sampleStarts.length == 0)) {
      return Collections.emptyList();
    }
    final Query query = this.entityManager.createNativeQuery(StatisticsEventSummaryRepositoryImpl.COMPARE_STATISTICS_SQL);
    query.setParameter("context", context);
    query.setParameter("dimensionName", dimensionName);
    query.setParameter("sampleStarts", sampleStarts);
    query.setParameter("sampleEnds", sampleEnds);
    query.setParameter("referenceStart", referenceStart);
    query.setParameter("referenceEnd", referenceEnd);
    @SuppressWarnings("unchecked")
    final List<Object[]> rows = query.getResultList();
    final List<ComparisonStatistic> statistics = new ArrayList<>(rows.size());
    for (final Object[] row : rows) {
      statistics.add(
          new ComparisonStatistic(
              (String) row[0],
              (String) row[1],
              StatisticsEventSummaryRepositoryImpl.toBigDecimal(row[2]),
              StatisticsEventSummaryRepositoryImpl.toBigDecimal(row[3]),
              StatisticsEventSummaryRepositoryImpl.toBigDecimal(row[4]),
              StatisticsEventSummaryRepositoryImpl.toBigDecimal(row[5]),
              StatisticsEventSummaryRepositoryImpl.toBigDecimal(row[6])));
    }
    return statistics;
  }

  /** Coerces a nullable numeric JDBC value to {@link BigDecimal}, preserving {@code null}. */
  private static BigDecimal toBigDecimal(final Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal bigDecimal) {
      return bigDecimal;
    }
    return new BigDecimal(value.toString());
  }
}
