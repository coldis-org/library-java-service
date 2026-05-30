package org.coldis.library.test.service.statistics;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.coldis.library.service.statistics.MetricComparisonStats;
import org.coldis.library.service.statistics.StatisticsEventSummaryComparison;
import org.coldis.library.service.statistics.StatisticsEventSummaryServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the cross-dimension z-score aggregators on {@link
 * StatisticsEventSummaryServiceComponent}. These reductions touch no component state, so the
 * component is instantiated directly and fed hand-built comparisons — no Spring context or database
 * required.
 */
public class StatisticsEventSummaryServiceComponentZScoreTest {

  /** Component under test (only its pure aggregator methods are exercised). */
  private final StatisticsEventSummaryServiceComponent component =
      new StatisticsEventSummaryServiceComponent();

  /**
   * Fixture spanning two dimensions with ratio z-scores {@code 1, -2} and {@code 3}; the flat
   * absolute set is {@code {1, 2, 3}} (k = 3, Σz² = 14).
   */
  private final List<StatisticsEventSummaryComparison> fixture =
      List.of(
          StatisticsEventSummaryServiceComponentZScoreTest.comparison(
              Map.of("a", BigDecimal.valueOf(1.0), "b", BigDecimal.valueOf(-2.0))),
          StatisticsEventSummaryServiceComponentZScoreTest.comparison(
              Map.of("c", BigDecimal.valueOf(3.0))));

  /** Builds a comparison carrying the given ratio z-score map on its count stats. */
  private static StatisticsEventSummaryComparison comparison(
      final Map<String, BigDecimal> zScoreRatios) {
    final MetricComparisonStats countStats = new MetricComparisonStats();
    countStats.setZScoreRatios(zScoreRatios);
    final StatisticsEventSummaryComparison comparison = new StatisticsEventSummaryComparison();
    comparison.setCountStats(countStats);
    return comparison;
  }

  /** Asserts every aggregator returns {@code null} for the given input. */
  private void assertAllNull(final List<StatisticsEventSummaryComparison> input) {
    Assertions.assertNull(this.component.maxAbsRatioZScore(input));
    Assertions.assertNull(this.component.minAbsRatioZScore(input));
    Assertions.assertNull(this.component.meanAbsRatioZScore(input));
    Assertions.assertNull(this.component.countAbsRatioZScoreAbove(input, 1.0));
    Assertions.assertNull(this.component.rootSumSquareRatioZScore(input));
    Assertions.assertNull(this.component.standardizedChiSquareRatioZScore(input));
    Assertions.assertNull(this.component.fisherCombinedRatioZScore(input));
    Assertions.assertNull(this.component.standardizedFisherRatioZScore(input));
  }

  @Test
  @DisplayName("Every aggregator returns null for null input")
  public void testNullInputYieldsNull() {
    this.assertAllNull(null);
  }

  @Test
  @DisplayName("Every aggregator returns null for an empty comparison list")
  public void testEmptyListYieldsNull() {
    this.assertAllNull(List.of());
  }

  @Test
  @DisplayName("Every aggregator returns null when no z-scores are present")
  public void testEmptyZScoreMapYieldsNull() {
    this.assertAllNull(
        List.of(StatisticsEventSummaryServiceComponentZScoreTest.comparison(Map.of())));
  }

  @Test
  @DisplayName("Null comparison elements are ignored, not dereferenced")
  public void testNullComparisonElementIsIgnored() {
    this.assertAllNull(Arrays.asList((StatisticsEventSummaryComparison) null));
  }

  @Test
  @DisplayName("Comparisons with null count stats are ignored")
  public void testNullCountStatsIsIgnored() {
    final StatisticsEventSummaryComparison nullStats = new StatisticsEventSummaryComparison();
    nullStats.setCountStats(null);
    this.assertAllNull(List.of(nullStats));
  }

  @Test
  @DisplayName("Null z-score map values are filtered out of the aggregation")
  public void testNullZScoreValuesAreIgnored() {
    final Map<String, BigDecimal> withNulls = new HashMap<>();
    withNulls.put("present", BigDecimal.valueOf(2.0));
    withNulls.put("missing", null);
    final List<StatisticsEventSummaryComparison> input =
        List.of(StatisticsEventSummaryServiceComponentZScoreTest.comparison(withNulls));
    Assertions.assertEquals(
        0, this.component.maxAbsRatioZScore(input).compareTo(BigDecimal.valueOf(2.0)));
    Assertions.assertEquals(
        0, this.component.minAbsRatioZScore(input).compareTo(BigDecimal.valueOf(2.0)));
  }

  @Test
  @DisplayName("maxAbs returns the largest |z| across dimensions")
  public void testMaxAbs() {
    Assertions.assertEquals(
        0, this.component.maxAbsRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(3.0)));
  }

  @Test
  @DisplayName("minAbs returns the smallest |z| across dimensions")
  public void testMinAbs() {
    Assertions.assertEquals(
        0, this.component.minAbsRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(1.0)));
  }

  @Test
  @DisplayName("meanAbs returns the mean |z| across dimensions")
  public void testMeanAbs() {
    Assertions.assertEquals(
        0, this.component.meanAbsRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(2.0)));
  }

  @Test
  @DisplayName("countAbove counts |z| strictly exceeding the threshold (0 stays 0, never null)")
  public void testCountAbove() {
    Assertions.assertEquals(
        0, this.component.countAbsRatioZScoreAbove(this.fixture, 2.0).compareTo(BigDecimal.ONE));
    Assertions.assertEquals(
        0,
        this.component.countAbsRatioZScoreAbove(this.fixture, 0.5).compareTo(BigDecimal.valueOf(3)));
    Assertions.assertEquals(
        0,
        this.component.countAbsRatioZScoreAbove(this.fixture, 5.0).compareTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("rootSumSquare returns sqrt(Σz²)")
  public void testRootSumSquare() {
    Assertions.assertEquals(
        Math.sqrt(14.0), this.component.rootSumSquareRatioZScore(this.fixture).doubleValue(), 1e-6);
  }

  @Test
  @DisplayName("standardizedChiSquare returns (Σz² − k)/sqrt(2k)")
  public void testStandardizedChiSquare() {
    final double expected = (14.0 - 3.0) / Math.sqrt(2.0 * 3.0);
    Assertions.assertEquals(
        expected, this.component.standardizedChiSquareRatioZScore(this.fixture).doubleValue(), 1e-6);
  }

  @Test
  @DisplayName("Fisher combined surprise is zero when no dimension deviates")
  public void testFisherIsZeroWhenNoDeviation() {
    final List<StatisticsEventSummaryComparison> zeros =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.comparison(
                Map.of("a", BigDecimal.ZERO, "b", BigDecimal.ZERO)));
    Assertions.assertEquals(
        0.0, this.component.fisherCombinedRatioZScore(zeros).doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Standardized Fisher is (S − k)/sqrt(k), i.e. -sqrt(k) under no deviation")
  public void testStandardizedFisherWhenNoDeviation() {
    final List<StatisticsEventSummaryComparison> zeros =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.comparison(
                Map.of("a", BigDecimal.ZERO, "b", BigDecimal.ZERO)));
    Assertions.assertEquals(
        -2.0 / Math.sqrt(2.0),
        this.component.standardizedFisherRatioZScore(zeros).doubleValue(),
        1e-6);
  }

  @Test
  @DisplayName("Fisher surprise saturates at -log(1e-300) for extreme z-scores")
  public void testFisherClampsExtremeZScore() {
    final List<StatisticsEventSummaryComparison> extreme =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.comparison(
                Map.of("x", BigDecimal.valueOf(100.0))));
    Assertions.assertEquals(
        -Math.log(1e-300), this.component.fisherCombinedRatioZScore(extreme).doubleValue(), 1e-3);
  }

  @Test
  @DisplayName("Fisher combined surprise increases monotonically with |z|")
  public void testFisherIsMonotonicInZScore() {
    final BigDecimal smaller =
        this.component.fisherCombinedRatioZScore(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.comparison(
                    Map.of("x", BigDecimal.valueOf(1.0)))));
    final BigDecimal larger =
        this.component.fisherCombinedRatioZScore(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.comparison(
                    Map.of("x", BigDecimal.valueOf(3.0)))));
    Assertions.assertTrue(smaller.doubleValue() < larger.doubleValue());
  }
}
