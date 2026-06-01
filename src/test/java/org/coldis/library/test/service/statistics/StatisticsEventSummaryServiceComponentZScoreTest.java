package org.coldis.library.test.service.statistics;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.statistics.MetricComparisonStats;
import org.coldis.library.service.statistics.StatisticsEventDimensionConcentration;
import org.coldis.library.service.statistics.StatisticsEventNaiveMultiDimensionProbability;
import org.coldis.library.service.statistics.StatisticsEventSingleDimensionProbability;
import org.coldis.library.service.statistics.StatisticsEventSummary;
import org.coldis.library.service.statistics.StatisticsEventSummaryComparison;
import org.coldis.library.service.statistics.StatisticsEventSummaryHelper;
import org.coldis.library.service.statistics.StatisticsEventWindowedProbability;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the static statistics reductions on {@link StatisticsEventSummaryHelper}: the
 * cross-dimension z-score aggregators, the single-/multi-dimension probabilities and their
 * uncertainty descriptors, the dimension concentration measures, and the windowed probability. The
 * z-score aggregators come in three families that read three different per-comparison maps off the
 * count stats: <b>ratio</b> ({@code zScoreRatios} — per-value share drift), <b>value</b> ({@code
 * zScoreValues} — per-value raw-count drift) and <b>total</b> ({@code zScoreTotal} — one
 * overall-volume z per dimension). Each family has {@code |z|} and signed variants. Everything here
 * is a pure reduction over hand-built inputs — no Spring context or database required.
 */
public class StatisticsEventSummaryServiceComponentZScoreTest {

  /**
   * Ratio fixture spanning two dimensions with z-scores {@code 1, -2} and {@code 3}; the flat
   * absolute set is {@code {1, 2, 3}} (k = 3, Σz² = 14), the flat signed set is {@code [1, -2, 3]}.
   */
  private final List<StatisticsEventSummaryComparison> fixture =
      List.of(
          StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
              Map.of("a", BigDecimal.valueOf(1.0), "b", BigDecimal.valueOf(-2.0))),
          StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
              Map.of("c", BigDecimal.valueOf(3.0))));

  /**
   * Signed ratio fixture {@code [1, -4, 3]} — chosen so the largest-magnitude z ({@code -4}) is
   * negative, separating {@code maxAbsSigned} (-4) from {@code maxSigned} (3) and giving a net mean
   * of exactly 0.
   */
  private final List<StatisticsEventSummaryComparison> signedFixture =
      List.of(
          StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
              Map.of("a", BigDecimal.valueOf(1.0), "b", BigDecimal.valueOf(-4.0))),
          StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
              Map.of("c", BigDecimal.valueOf(3.0))));

  /**
   * Value fixture carrying the same {@code [1, -2, 3]} numbers as {@link #fixture} but on {@code
   * zScoreValues}; each comparison also carries a decoy {@code zScoreRatios} of {@code 99} to prove
   * the value family reads its own map.
   */
  private final List<StatisticsEventSummaryComparison> valueFixture =
      List.of(
          StatisticsEventSummaryServiceComponentZScoreTest.fullComparison(
              Map.of("a", BigDecimal.valueOf(99.0)),
              Map.of("a", BigDecimal.valueOf(1.0), "b", BigDecimal.valueOf(-2.0)),
              null),
          StatisticsEventSummaryServiceComponentZScoreTest.fullComparison(
              Map.of("c", BigDecimal.valueOf(99.0)),
              Map.of("c", BigDecimal.valueOf(3.0)),
              null));

  /** Total fixture: one total-volume z per dimension, {@code -1} and {@code 3}. */
  private final List<StatisticsEventSummaryComparison> totalFixture =
      List.of(
          StatisticsEventSummaryServiceComponentZScoreTest.totalComparison(BigDecimal.valueOf(-1.0)),
          StatisticsEventSummaryServiceComponentZScoreTest.totalComparison(BigDecimal.valueOf(3.0)));

  /** Builds a comparison carrying the given ratio z-score map on its count stats. */
  private static StatisticsEventSummaryComparison ratioComparison(
      final Map<String, BigDecimal> zScoreRatios) {
    return StatisticsEventSummaryServiceComponentZScoreTest.fullComparison(zScoreRatios, null, null);
  }

  /** Builds a comparison carrying the given raw-count value z-score map on its count stats. */
  private static StatisticsEventSummaryComparison valueComparison(
      final Map<String, BigDecimal> zScoreValues) {
    return StatisticsEventSummaryServiceComponentZScoreTest.fullComparison(null, zScoreValues, null);
  }

  /** Builds a comparison carrying only the given total-volume z-score on its count stats. */
  private static StatisticsEventSummaryComparison totalComparison(final BigDecimal zScoreTotal) {
    return StatisticsEventSummaryServiceComponentZScoreTest.fullComparison(null, null, zScoreTotal);
  }

  /** Builds a comparison whose count stats carry any combination of the three z-score shapes. */
  private static StatisticsEventSummaryComparison fullComparison(
      final Map<String, BigDecimal> zScoreRatios,
      final Map<String, BigDecimal> zScoreValues,
      final BigDecimal zScoreTotal) {
    final MetricComparisonStats countStats = new MetricComparisonStats();
    countStats.setZScoreRatios(zScoreRatios);
    countStats.setZScoreValues(zScoreValues);
    countStats.setZScoreTotal(zScoreTotal);
    final StatisticsEventSummaryComparison comparison = new StatisticsEventSummaryComparison();
    comparison.setCountStats(countStats);
    return comparison;
  }

  /** Builds a single-period summary in context {@code "ctx"} with the given value counts (total = Σ). */
  private static StatisticsEventSummary summaryOf(
      final String dimensionName, final Map<String, Long> valueCounts) {
    final StatisticsEventSummary summary = new StatisticsEventSummary("ctx", dimensionName, null);
    summary.setValueCounts(new HashMap<>(valueCounts));
    summary.setTotalCount(valueCounts.values().stream().mapToLong(Long::longValue).sum());
    return summary;
  }

  /** Asserts every aggregator across all three families returns {@code null} for the given input. */
  private void assertAllNull(final List<StatisticsEventSummaryComparison> input) {
    // Ratio family (|z| and signed).
    Assertions.assertNull(StatisticsEventSummaryHelper.maxAbsRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.minAbsRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanAbsRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.countAbsRatioZScoreAbove(input, 1.0));
    Assertions.assertNull(StatisticsEventSummaryHelper.rootSumSquareRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.standardizedChiSquareRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.fisherCombinedRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.standardizedFisherRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.maxSignedRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.minSignedRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanSignedRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanPositiveRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanNegativeRatioZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.countRatioZScoreAbove(input, 1.0));
    Assertions.assertNull(StatisticsEventSummaryHelper.countRatioZScoreBelow(input, 1.0));
    // Value family (|z| and signed).
    Assertions.assertNull(StatisticsEventSummaryHelper.maxAbsValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.minAbsValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanAbsValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.countAbsValueZScoreAbove(input, 1.0));
    Assertions.assertNull(StatisticsEventSummaryHelper.rootSumSquareValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.standardizedChiSquareValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.fisherCombinedValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.standardizedFisherValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.maxSignedValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.minSignedValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanSignedValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanPositiveValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanNegativeValueZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.countValueZScoreAbove(input, 1.0));
    Assertions.assertNull(StatisticsEventSummaryHelper.countValueZScoreBelow(input, 1.0));
    // Total family.
    Assertions.assertNull(StatisticsEventSummaryHelper.meanSignedTotalZScore(input));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanAbsTotalZScore(input));
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
        List.of(StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(Map.of())));
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
        List.of(StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(withNulls));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxAbsRatioZScore(input).compareTo(BigDecimal.valueOf(2.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.minAbsRatioZScore(input).compareTo(BigDecimal.valueOf(2.0)));
  }

  @Test
  @DisplayName("maxAbs returns the largest |z| across dimensions")
  public void testMaxAbs() {
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxAbsRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(3.0)));
  }

  @Test
  @DisplayName("minAbs returns the smallest |z| across dimensions")
  public void testMinAbs() {
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.minAbsRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(1.0)));
  }

  @Test
  @DisplayName("meanAbs returns the mean |z| across dimensions")
  public void testMeanAbs() {
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanAbsRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(2.0)));
  }

  @Test
  @DisplayName("countAbove counts |z| strictly exceeding the threshold (0 stays 0, never null)")
  public void testCountAbove() {
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countAbsRatioZScoreAbove(this.fixture, 2.0).compareTo(BigDecimal.ONE));
    Assertions.assertEquals(
        0,
        StatisticsEventSummaryHelper.countAbsRatioZScoreAbove(this.fixture, 0.5).compareTo(BigDecimal.valueOf(3)));
    Assertions.assertEquals(
        0,
        StatisticsEventSummaryHelper.countAbsRatioZScoreAbove(this.fixture, 5.0).compareTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("rootSumSquare returns sqrt(Σz²)")
  public void testRootSumSquare() {
    Assertions.assertEquals(
        Math.sqrt(14.0), StatisticsEventSummaryHelper.rootSumSquareRatioZScore(this.fixture).doubleValue(), 1e-6);
  }

  @Test
  @DisplayName("standardizedChiSquare returns (Σz² − k)/sqrt(2k)")
  public void testStandardizedChiSquare() {
    final double expected = (14.0 - 3.0) / Math.sqrt(2.0 * 3.0);
    Assertions.assertEquals(
        expected, StatisticsEventSummaryHelper.standardizedChiSquareRatioZScore(this.fixture).doubleValue(), 1e-6);
  }

  @Test
  @DisplayName("Fisher combined surprise is zero when no dimension deviates")
  public void testFisherIsZeroWhenNoDeviation() {
    final List<StatisticsEventSummaryComparison> zeros =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                Map.of("a", BigDecimal.ZERO, "b", BigDecimal.ZERO)));
    Assertions.assertEquals(
        0.0, StatisticsEventSummaryHelper.fisherCombinedRatioZScore(zeros).doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Standardized Fisher is (S − k)/sqrt(k), i.e. -sqrt(k) under no deviation")
  public void testStandardizedFisherWhenNoDeviation() {
    final List<StatisticsEventSummaryComparison> zeros =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                Map.of("a", BigDecimal.ZERO, "b", BigDecimal.ZERO)));
    Assertions.assertEquals(
        -2.0 / Math.sqrt(2.0),
        StatisticsEventSummaryHelper.standardizedFisherRatioZScore(zeros).doubleValue(),
        1e-6);
  }

  @Test
  @DisplayName("Fisher surprise saturates at -log(1e-300) for extreme z-scores")
  public void testFisherClampsExtremeZScore() {
    final List<StatisticsEventSummaryComparison> extreme =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                Map.of("x", BigDecimal.valueOf(100.0))));
    Assertions.assertEquals(
        -Math.log(1e-300), StatisticsEventSummaryHelper.fisherCombinedRatioZScore(extreme).doubleValue(), 1e-3);
  }

  @Test
  @DisplayName("Fisher combined surprise increases monotonically with |z|")
  public void testFisherIsMonotonicInZScore() {
    final BigDecimal smaller =
        StatisticsEventSummaryHelper.fisherCombinedRatioZScore(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                    Map.of("x", BigDecimal.valueOf(1.0)))));
    final BigDecimal larger =
        StatisticsEventSummaryHelper.fisherCombinedRatioZScore(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                    Map.of("x", BigDecimal.valueOf(3.0)))));
    Assertions.assertTrue(smaller.doubleValue() < larger.doubleValue());
  }

  @Test
  @DisplayName("Fisher surprise matches the known two-sided normal tail at moderate z (CDF accuracy)")
  public void testFisherSurpriseMatchesKnownNormalTails() {
    // z = 1.0: Φ(1) = 0.8413447 -> two-sided tail 0.3173106 -> surprise = -ln(tail).
    final double expectedAtOne = -Math.log(2.0 * (1.0 - 0.8413447461));
    Assertions.assertEquals(
        expectedAtOne,
        StatisticsEventSummaryHelper
            .fisherCombinedRatioZScore(
                List.of(
                    StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                        Map.of("x", BigDecimal.valueOf(1.0)))))
            .doubleValue(),
        1e-3);
    // z = 1.96: Φ(1.96) = 0.9750021 -> two-sided tail ~0.05 -> surprise = -ln(tail).
    final double expectedAtCritical = -Math.log(2.0 * (1.0 - 0.9750021048));
    Assertions.assertEquals(
        expectedAtCritical,
        StatisticsEventSummaryHelper
            .fisherCombinedRatioZScore(
                List.of(
                    StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                        Map.of("y", BigDecimal.valueOf(1.96)))))
            .doubleValue(),
        1e-3);
  }

  @Test
  @DisplayName("Signed ratio aggregators preserve drift direction (max +3, min -4, mean 0) with directional counts")
  public void testSignedRatioAggregatorsPreserveDirection() {
    // signedFixture flat signed set is [1, -4, 3].
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxSignedRatioZScore(this.signedFixture).compareTo(BigDecimal.valueOf(3.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.minSignedRatioZScore(this.signedFixture).compareTo(BigDecimal.valueOf(-4.0)));
    Assertions.assertEquals(
        0.0, StatisticsEventSummaryHelper.meanSignedRatioZScore(this.signedFixture).doubleValue(), 1e-9);
    // Directional counts are signed, not |z|: above(+2) catches only +3; below(2) catches only -4.
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countRatioZScoreAbove(this.signedFixture, 2.0).compareTo(BigDecimal.ONE));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countRatioZScoreBelow(this.signedFixture, 2.0).compareTo(BigDecimal.ONE));
    // +1 is neither above +2 nor below -2, so a threshold of 2 leaves it out of both counts.
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countRatioZScoreAbove(this.signedFixture, 0.5).compareTo(BigDecimal.valueOf(2)));
  }

  @Test
  @DisplayName("mean-positive / mean-negative split the tails: each averages only its own side (0 when a side is empty)")
  public void testMeanPositiveNegativeSplitTails() {
    // fixture flat signed set is [1, -2, 3]: positives {1, 3} -> 2.0, negatives {-2} -> -2.0.
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanPositiveRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(2.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanNegativeRatioZScore(this.fixture).compareTo(BigDecimal.valueOf(-2.0)));
    // Value family reads its own [1, -2, 3] map.
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanPositiveValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(2.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanNegativeValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(-2.0)));
    // One-sided data: all-positive -> the negative side is present but empty, so it is 0, not null.
    final List<StatisticsEventSummaryComparison> allPositive =
        List.of(
            StatisticsEventSummaryServiceComponentZScoreTest.ratioComparison(
                Map.of("a", BigDecimal.valueOf(2.0), "b", BigDecimal.valueOf(4.0))));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanPositiveRatioZScore(allPositive).compareTo(BigDecimal.valueOf(3.0)));
    Assertions.assertEquals(0, StatisticsEventSummaryHelper.meanNegativeRatioZScore(allPositive).signum());
  }

  @Test
  @DisplayName("Value family reduces the raw-count z map with the same math as the ratio family")
  public void testValueFamilyMatchesRatioMath() {
    // valueFixture carries [1, -2, 3] on zScoreValues (k = 3, Σz² = 14).
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxAbsValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(3.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.minAbsValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(1.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanAbsValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(2.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countAbsValueZScoreAbove(this.valueFixture, 2.0).compareTo(BigDecimal.ONE));
    Assertions.assertEquals(
        Math.sqrt(14.0), StatisticsEventSummaryHelper.rootSumSquareValueZScore(this.valueFixture).doubleValue(), 1e-6);
    Assertions.assertEquals(
        (14.0 - 3.0) / Math.sqrt(2.0 * 3.0),
        StatisticsEventSummaryHelper.standardizedChiSquareValueZScore(this.valueFixture).doubleValue(),
        1e-6);
    // Signed value variants over [1, -2, 3].
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxSignedValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(3.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.minSignedValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(-2.0)));
    // Directional counts over [1, -2, 3]: above(+2) -> just +3; below(1) -> just -2.
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countValueZScoreAbove(this.valueFixture, 2.0).compareTo(BigDecimal.ONE));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.countValueZScoreBelow(this.valueFixture, 1.0).compareTo(BigDecimal.ONE));
  }

  @Test
  @DisplayName("Ratio and value families read independent maps; value ignores the ratio decoy and vice versa")
  public void testRatioAndValueFamiliesReadSeparateMaps() {
    // valueFixture carries decoy zScoreRatios of 99 alongside the real [1, -2, 3] on zScoreValues.
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxAbsRatioZScore(this.valueFixture).compareTo(BigDecimal.valueOf(99.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxAbsValueZScore(this.valueFixture).compareTo(BigDecimal.valueOf(3.0)));
    // The ratio-only fixture has no zScoreValues, so the value family sees nothing there.
    Assertions.assertNull(StatisticsEventSummaryHelper.maxAbsValueZScore(this.fixture));
    // The value-only/decoy fixture has no zScoreTotal, so the total family sees nothing there.
    Assertions.assertNull(StatisticsEventSummaryHelper.meanSignedTotalZScore(this.valueFixture));
  }

  @Test
  @DisplayName("Total family averages one volume z per dimension, signed and absolute")
  public void testTotalAggregators() {
    // totalFixture is one total z per dimension: [-1, 3].
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanSignedTotalZScore(this.totalFixture).compareTo(BigDecimal.valueOf(1.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.meanAbsTotalZScore(this.totalFixture).compareTo(BigDecimal.valueOf(2.0)));
    // The ratio family reads the value map, not the total z, so it sees nothing on a total-only fixture.
    Assertions.assertNull(StatisticsEventSummaryHelper.maxAbsRatioZScore(this.totalFixture));
  }

  @Test
  @DisplayName("Single-dimension probability of an empty (zero-total) summary is zero, not NaN")
  public void testSingleDimensionProbabilityOnEmptySummary() {
    final StatisticsEventSummary summary = new StatisticsEventSummary("ctx", "dim", null);
    summary.setTotalCount(0L);
    final StatisticsEventSingleDimensionProbability probability =
        StatisticsEventSummaryHelper.singleDimensionProbability(summary, "anything");
    Assertions.assertEquals(0, probability.getProbability().signum());
    Assertions.assertEquals(0, probability.getSmoothedProbability().signum());
    Assertions.assertEquals(0, probability.getDistinctValueCount());
    // No sample and no vocabulary -> every uncertainty descriptor is left null rather than NaN/Infinity.
    Assertions.assertNull(probability.getWilsonLowerBound());
    Assertions.assertNull(probability.getWilsonUpperBound());
    Assertions.assertNull(probability.getPosteriorVariance());
    Assertions.assertNull(probability.getCredibleLowerBound());
    Assertions.assertNull(probability.getCredibleUpperBound());
    Assertions.assertNull(probability.getSurprisal());
    Assertions.assertNull(probability.getLogOdds());
  }

  @Test
  @DisplayName("Single-dimension uncertainty descriptors: Wilson, Beta posterior, surprisal and log-odds at p=1/2")
  public void testSingleDimensionProbabilityUncertaintyDescriptors() {
    // 50 of 100 over two values: raw = smoothed = 0.5 (smoothed is (50+1)/(100+2) = 51/102).
    final StatisticsEventSummary summary =
        StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of("sao-paulo", 50L, "rio", 50L));
    final StatisticsEventSingleDimensionProbability probability =
        StatisticsEventSummaryHelper.singleDimensionProbability(summary, "sao-paulo");
    Assertions.assertEquals(0.5, probability.getProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(0.5, probability.getSmoothedProbability().doubleValue(), 1e-9);
    // Wilson 95% score interval for 50/100 is approximately [0.40383, 0.59617].
    Assertions.assertEquals(0.40383, probability.getWilsonLowerBound().doubleValue(), 1e-3);
    Assertions.assertEquals(0.59617, probability.getWilsonUpperBound().doubleValue(), 1e-3);
    // Beta(51,51) posterior variance = 51*51 / (102^2 * 103) ~= 0.0024272.
    Assertions.assertEquals(0.0024272, probability.getPosteriorVariance().doubleValue(), 1e-6);
    // Normal-approx 95% credible interval around the 0.5 mean ~= [0.40344, 0.59656].
    Assertions.assertEquals(0.40344, probability.getCredibleLowerBound().doubleValue(), 1e-3);
    Assertions.assertEquals(0.59656, probability.getCredibleUpperBound().doubleValue(), 1e-3);
    // Surprisal -ln(0.5) = ln(2); log-odds of 0.5 is exactly 0.
    Assertions.assertEquals(Math.log(2.0), probability.getSurprisal().doubleValue(), 1e-6);
    Assertions.assertEquals(0.0, probability.getLogOdds().doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Wilson interval narrows as the sample grows (10 vs 100 at the same proportion)")
  public void testWilsonIntervalNarrowsWithSampleSize() {
    final StatisticsEventSingleDimensionProbability small =
        StatisticsEventSummaryHelper.singleDimensionProbability(
            StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("d", Map.of("x", 5L, "y", 5L)), "x");
    final StatisticsEventSingleDimensionProbability large =
        StatisticsEventSummaryHelper.singleDimensionProbability(
            StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("d", Map.of("x", 50L, "y", 50L)), "x");
    final double smallWidth = small.getWilsonUpperBound().doubleValue() - small.getWilsonLowerBound().doubleValue();
    final double largeWidth = large.getWilsonUpperBound().doubleValue() - large.getWilsonLowerBound().doubleValue();
    Assertions.assertTrue(smallWidth > largeWidth, "10-sample band should be wider than the 100-sample band");
  }

  @Test
  @DisplayName("Dimension concentration: uniform mix is maximally spread, a single value is fully concentrated")
  public void testDimensionConcentration() {
    // Uniform over four values: entropy = ln(4), normalized = 1, Gini-Simpson = 1 - 4*(0.25^2) = 0.75.
    final StatisticsEventDimensionConcentration uniform =
        StatisticsEventSummaryHelper.dimensionConcentration(
            StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("u", Map.of("a", 1L, "b", 1L, "c", 1L, "d", 1L)));
    Assertions.assertEquals(4, uniform.getDistinctValueCount());
    Assertions.assertEquals(Math.log(4.0), uniform.getEntropy().doubleValue(), 1e-6);
    Assertions.assertEquals(1.0, uniform.getNormalizedEntropy().doubleValue(), 1e-9);
    Assertions.assertEquals(0.75, uniform.getGiniSimpsonIndex().doubleValue(), 1e-9);
    // Single value carries everything: zero entropy and zero Gini-Simpson.
    final StatisticsEventDimensionConcentration concentrated =
        StatisticsEventSummaryHelper.dimensionConcentration(
            StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("c", Map.of("only", 5L)));
    Assertions.assertEquals(0.0, concentrated.getEntropy().doubleValue(), 1e-9);
    Assertions.assertEquals(0.0, concentrated.getNormalizedEntropy().doubleValue(), 1e-9);
    Assertions.assertEquals(0.0, concentrated.getGiniSimpsonIndex().doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Windowed probability: macro (equal-weighted) differs from pooled (volume-weighted) and carries dispersion")
  public void testWindowedValueProbabilityMacroVsPooled() {
    // Window 1: x = 1/1 (ratio 1.0). Window 2: x = 1/10 (ratio 0.1).
    final StatisticsEventWindowedProbability windowed =
        StatisticsEventSummaryHelper.windowedValueProbability(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of("x", 1L)),
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of("x", 1L, "y", 9L))),
            "x");
    Assertions.assertEquals(2, windowed.getWindowCount());
    Assertions.assertEquals("ctx", windowed.getContext());
    Assertions.assertEquals("city", windowed.getDimensionName());
    // Macro = mean(1.0, 0.1) = 0.55; pooled = (1+1)/(1+10) = 2/11.
    Assertions.assertEquals(0.55, windowed.getMacroProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(2.0 / 11.0, windowed.getPooledProbability().doubleValue(), 1e-9);
    // Population std dev of [1.0, 0.1] around 0.55 is 0.45.
    Assertions.assertEquals(0.45, windowed.getMacroProbabilityStdDev().doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Windowed probability over no windows yields a zero-window, zero-probability result")
  public void testWindowedValueProbabilityEmpty() {
    final StatisticsEventWindowedProbability windowed =
        StatisticsEventSummaryHelper.windowedValueProbability(List.of(), "x");
    Assertions.assertEquals(0, windowed.getWindowCount());
    Assertions.assertEquals(0, windowed.getPooledProbability().signum());
    Assertions.assertEquals(0, windowed.getMacroProbability().signum());
    Assertions.assertEquals(0, windowed.getMacroProbabilityStdDev().signum());
    // A null window list is treated the same as an empty one (no NPE).
    final StatisticsEventWindowedProbability fromNull =
        StatisticsEventSummaryHelper.windowedValueProbability(null, "x");
    Assertions.assertEquals(0, fromNull.getWindowCount());
    Assertions.assertEquals(0, fromNull.getMacroProbability().signum());
  }

  @Test
  @DisplayName("Naive joint over three dimensions multiplies the probabilities and sums their logs")
  public void testNaiveMultiDimensionProbabilityOverThreeDimensions() throws Exception {
    final StatisticsEventSummary summaryA =
        StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("A", Map.of("x", 2L, "y", 2L));
    final StatisticsEventSummary summaryB =
        StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("B", Map.of("m", 3L, "n", 1L));
    final StatisticsEventSummary summaryC =
        StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("C", Map.of("p", 1L, "q", 1L, "r", 2L));
    final StatisticsEventNaiveMultiDimensionProbability joint =
        StatisticsEventSummaryHelper.naiveMultiDimensionProbability(
            List.of(summaryA, summaryB, summaryC), List.of("x", "m", "p"));
    Assertions.assertEquals(3, joint.getIndividualProbabilities().size());
    Assertions.assertEquals("ctx", joint.getContext());
    // Raw: P(x)=2/4, P(m)=3/4, P(p)=1/4 -> joint 0.09375.
    Assertions.assertEquals(0.5 * 0.75 * 0.25, joint.getJointProbability().doubleValue(), 1e-9);
    // Smoothed: (2+1)/(4+2)=0.5, (3+1)/(4+2)=2/3, (1+1)/(4+3)=2/7.
    final double expectedSmoothed = 0.5 * (2.0 / 3.0) * (2.0 / 7.0);
    Assertions.assertEquals(expectedSmoothed, joint.getJointSmoothedProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(Math.log(expectedSmoothed), joint.getJointSmoothedLogProbability().doubleValue(), 1e-6);
  }

  @Test
  @DisplayName("Naive joint rejects empty, null and misaligned inputs with a BusinessException")
  public void testNaiveMultiDimensionProbabilityRejectsInvalidInput() {
    final StatisticsEventSummary summary =
        StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("A", Map.of("x", 2L, "y", 2L));
    // Empty summaries.
    Assertions.assertThrows(
        BusinessException.class,
        () -> StatisticsEventSummaryHelper.naiveMultiDimensionProbability(List.of(), List.of()));
    // Null summaries.
    Assertions.assertThrows(
        BusinessException.class,
        () -> StatisticsEventSummaryHelper.naiveMultiDimensionProbability(null, List.of("x")));
    // Size mismatch between summaries and values.
    Assertions.assertThrows(
        BusinessException.class,
        () -> StatisticsEventSummaryHelper.naiveMultiDimensionProbability(List.of(summary), List.of("x", "y")));
  }

  @Test
  @DisplayName("Descriptors away from 1/2: log-odds takes the value's side and an unseen value stays finite")
  public void testSingleDimensionProbabilityAsymmetricDescriptors() {
    // 9 of 10 are "x", 1 is "y"; two distinct values, default smoothing.
    final StatisticsEventSummary summary =
        StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of("x", 9L, "y", 1L));
    // Frequent value: raw 0.9, smoothed (9+1)/(10+2) = 10/12; log-odds positive, surprisal small.
    final StatisticsEventSingleDimensionProbability frequent =
        StatisticsEventSummaryHelper.singleDimensionProbability(summary, "x");
    Assertions.assertEquals(0.9, frequent.getProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(10.0 / 12.0, frequent.getSmoothedProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(Math.log((10.0 / 12.0) / (2.0 / 12.0)), frequent.getLogOdds().doubleValue(), 1e-6);
    Assertions.assertTrue(frequent.getLogOdds().signum() > 0, "log-odds positive for a value seen more than half the time");
    Assertions.assertEquals(-Math.log(10.0 / 12.0), frequent.getSurprisal().doubleValue(), 1e-6);
    // Unseen value: raw probability 0, but the smoothed estimate stays > 0 (never collapses), log-odds negative.
    final StatisticsEventSingleDimensionProbability unseen =
        StatisticsEventSummaryHelper.singleDimensionProbability(summary, "never-seen");
    Assertions.assertEquals(0, unseen.getProbability().signum());
    Assertions.assertEquals(1.0 / 12.0, unseen.getSmoothedProbability().doubleValue(), 1e-9);
    Assertions.assertTrue(unseen.getSmoothedProbability().signum() > 0, "smoothed probability never collapses to zero");
    Assertions.assertTrue(unseen.getLogOdds().signum() < 0, "log-odds negative for an unlikely value");
    Assertions.assertEquals(-Math.log(1.0 / 12.0), unseen.getSurprisal().doubleValue(), 1e-6);
    // Wilson lower bound on a zero-count proportion is clamped at zero (never negative).
    Assertions.assertTrue(unseen.getWilsonLowerBound().doubleValue() >= 0.0);
  }

  @Test
  @DisplayName("Concentration of a skewed mix sits strictly between fully concentrated and uniform")
  public void testDimensionConcentrationSkewed() {
    // 3 "a" and 1 "b": p = {0.75, 0.25}.
    final StatisticsEventDimensionConcentration skewed =
        StatisticsEventSummaryHelper.dimensionConcentration(
            StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("s", Map.of("a", 3L, "b", 1L)));
    final double expectedEntropy = -((0.75 * Math.log(0.75)) + (0.25 * Math.log(0.25)));
    Assertions.assertEquals(expectedEntropy, skewed.getEntropy().doubleValue(), 1e-6);
    Assertions.assertEquals(expectedEntropy / Math.log(2.0), skewed.getNormalizedEntropy().doubleValue(), 1e-6);
    Assertions.assertTrue(
        (skewed.getNormalizedEntropy().doubleValue() > 0.0) && (skewed.getNormalizedEntropy().doubleValue() < 1.0),
        "a skewed-but-not-degenerate split is between 0 and 1");
    // Gini-Simpson = 1 - (0.75^2 + 0.25^2) = 0.375.
    Assertions.assertEquals(0.375, skewed.getGiniSimpsonIndex().doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Windowed: a single window has zero dispersion and macro == pooled; empty windows are ignored")
  public void testWindowedValueProbabilitySingleAndZeroTotalWindows() {
    // One window x = 3/4: macro and pooled both 0.75, std dev 0.
    final StatisticsEventWindowedProbability single =
        StatisticsEventSummaryHelper.windowedValueProbability(
            List.of(StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of("x", 3L, "y", 1L))), "x");
    Assertions.assertEquals(1, single.getWindowCount());
    Assertions.assertEquals(0.75, single.getMacroProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(0.75, single.getPooledProbability().doubleValue(), 1e-9);
    Assertions.assertEquals(0, single.getMacroProbabilityStdDev().signum());
    // A window with no events is skipped, so only the populated window counts.
    final StatisticsEventWindowedProbability withEmpty =
        StatisticsEventSummaryHelper.windowedValueProbability(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of("x", 1L)),
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("city", Map.of())),
            "x");
    Assertions.assertEquals(1, withEmpty.getWindowCount());
    Assertions.assertEquals(1.0, withEmpty.getMacroProbability().doubleValue(), 1e-9);
  }

  @Test
  @DisplayName("Windowed cross-dimension aggregates: mean share-stability and mean share logit (null/empty -> null)")
  public void testWindowedShareAggregates() {
    // Dim A, value "x": windows 1/1 (1.0) and 1/10 (0.1) -> pooled 2/11, macro 0.55, std dev 0.45.
    final StatisticsEventWindowedProbability dimA =
        StatisticsEventSummaryHelper.windowedValueProbability(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("A", Map.of("x", 1L)),
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("A", Map.of("x", 1L, "y", 9L))),
            "x");
    // Dim B, value "m": both windows 2/4 (0.5) -> pooled 0.5, macro 0.5, std dev 0.
    final StatisticsEventWindowedProbability dimB =
        StatisticsEventSummaryHelper.windowedValueProbability(
            List.of(
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("B", Map.of("m", 2L, "n", 2L)),
                StatisticsEventSummaryServiceComponentZScoreTest.summaryOf("B", Map.of("m", 2L, "n", 2L))),
            "m");
    final List<StatisticsEventWindowedProbability> windowed = List.of(dimA, dimB);
    // Mean share-stability = (0.45 + 0.0) / 2 = 0.225.
    Assertions.assertEquals(
        0.225, StatisticsEventSummaryHelper.meanWindowedShareStdDev(windowed).doubleValue(), 1e-9);
    // Mean share logit = (logit(2/11) + logit(0.5)) / 2 = ln((2/11)/(9/11)) / 2 = ln(2/9) / 2.
    Assertions.assertEquals(
        Math.log(2.0 / 9.0) / 2.0,
        StatisticsEventSummaryHelper.meanWindowedShareLogit(windowed).doubleValue(),
        1e-6);
    // Null/empty inputs yield null on both aggregates.
    Assertions.assertNull(StatisticsEventSummaryHelper.meanWindowedShareStdDev(null));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanWindowedShareStdDev(List.of()));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanWindowedShareLogit(null));
    Assertions.assertNull(StatisticsEventSummaryHelper.meanWindowedShareLogit(List.of()));
  }
}
