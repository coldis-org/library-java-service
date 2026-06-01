package org.coldis.library.test.service.statistics;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.statistics.MetricComparisonStats;
import org.coldis.library.service.statistics.StatisticsEventNaiveMultiDimensionProbability;
import org.coldis.library.service.statistics.StatisticsEventSingleDimensionProbability;
import org.coldis.library.service.statistics.StatisticsEventSummary;
import org.coldis.library.service.statistics.StatisticsEventSummaryComparison;
import org.coldis.library.service.statistics.StatisticsEventSummaryHelper;
import org.coldis.library.service.statistics.StatisticsEventSummaryServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the cross-dimension z-score aggregators (static reductions on {@link
 * StatisticsEventSummaryHelper}) and for the probability entry points on {@link
 * StatisticsEventSummaryServiceComponent}. The aggregators come in three families that read three
 * different per-comparison maps off the count stats: <b>ratio</b> ({@code zScoreRatios} — per-value
 * share drift), <b>value</b> ({@code zScoreValues} — per-value raw-count drift) and <b>total</b>
 * ({@code zScoreTotal} — one overall-volume z per dimension). Each family has {@code |z|} and signed
 * variants. All reductions touch no component state, so they are fed hand-built comparisons — no
 * Spring context or database required; the component is instantiated directly only for the
 * probability methods.
 */
public class StatisticsEventSummaryServiceComponentZScoreTest {

  /** Component under test (only its pure probability methods are exercised). */
  private final StatisticsEventSummaryServiceComponent component =
      new StatisticsEventSummaryServiceComponent();

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
    Assertions.assertNull(StatisticsEventSummaryHelper.maxAbsSignedRatioZScore(input));
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
    Assertions.assertNull(StatisticsEventSummaryHelper.maxAbsSignedValueZScore(input));
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
  @DisplayName("Signed ratio aggregators preserve drift direction (max +3, min -4, mean 0, maxAbsSigned -4)")
  public void testSignedRatioAggregatorsPreserveDirection() {
    // signedFixture flat signed set is [1, -4, 3].
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.maxSignedRatioZScore(this.signedFixture).compareTo(BigDecimal.valueOf(3.0)));
    Assertions.assertEquals(
        0, StatisticsEventSummaryHelper.minSignedRatioZScore(this.signedFixture).compareTo(BigDecimal.valueOf(-4.0)));
    Assertions.assertEquals(
        0.0, StatisticsEventSummaryHelper.meanSignedRatioZScore(this.signedFixture).doubleValue(), 1e-9);
    // maxAbsSigned keeps the sign of the largest-magnitude z, which is -4 (not the +3 that maxSigned returns).
    Assertions.assertEquals(
        0,
        StatisticsEventSummaryHelper.maxAbsSignedRatioZScore(this.signedFixture).compareTo(BigDecimal.valueOf(-4.0)));
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
        this.component.singleDimensionProbability(summary, "anything");
    Assertions.assertEquals(0, probability.getProbability().signum());
    Assertions.assertEquals(0, probability.getSmoothedProbability().signum());
    Assertions.assertEquals(0, probability.getDistinctValueCount());
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
        this.component.naiveMultiDimensionProbability(
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
        () -> this.component.naiveMultiDimensionProbability(List.of(), List.of()));
    // Null summaries.
    Assertions.assertThrows(
        BusinessException.class,
        () -> this.component.naiveMultiDimensionProbability(null, List.of("x")));
    // Size mismatch between summaries and values.
    Assertions.assertThrows(
        BusinessException.class,
        () -> this.component.naiveMultiDimensionProbability(List.of(summary), List.of("x", "y")));
  }
}
