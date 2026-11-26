package org.coldis.library.test.service.statistics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.statistics.StatisticsContextConfigurationServiceComponent;
import org.coldis.library.service.statistics.StatisticsEventSummary;
import org.coldis.library.service.statistics.StatisticsEventSummaryComparison;
import org.coldis.library.service.statistics.StatisticsEventSummaryHelper;
import org.coldis.library.service.statistics.StatisticsEventSummaryHelper.WindowSchedule;
import org.coldis.library.service.statistics.StatisticsEventSummaryServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * In bucket mode a comparison reads each distinct truncation bucket of its reference and sample
 * windows once and assembles the windows in memory. These tests prove that the comparisons are
 * identical on full content (sample size, count and weight statistics, averages, standard
 * deviations, z-scores — the comparison's {@code equals} only covers identity fields, so the lists are
 * compared as JSON trees) to two references: the library's range-query path ({@code
 * useTruncationBuckets = false}) and the previous per-window bucket path rebuilt from the library's
 * own {@code fetchPeriodCacheable} seam; and that each distinct bucket is read once. Buckets are
 * synthetic and deterministic, every fifth one empty.
 */
public class StatisticsEventSummaryServiceComponentFetchOnceUnitTest {

  /** Context. */
  private static final String CONTEXT = "fetch-once-test";

  /** Truncation interval in minutes. */
  private static final long TRUNCATION_MINUTES = 60L;

  /** Dimensions compared. */
  private static final List<String> DIMENSIONS = List.of("residentialState", "bankCode");

  /** Dimension that never has data. */
  private static final String EMPTY_DIMENSION = "neverSeen";

  /** Reference window start (on the truncation grid). */
  private static final LocalDateTime REFERENCE_START = LocalDateTime.of(2026, 11, 26, 10, 0);

  /** Mapper for full-content comparison. */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  /**
   * Component whose bucket/range seam answers from synthetic buckets and counts single-bucket reads.
   */
  private static class CountingComponent extends StatisticsEventSummaryServiceComponent {

    /** Single-bucket reads, keyed by dimension and bucket. */
    private final Map<String, Integer> bucketReads = new ConcurrentHashMap<>();

    /**
     * Builds one bucket's synthetic summaries (empty for every fifth bucket and for the empty
     * dimension).
     *
     * @param dimensionName Dimension name.
     * @param bucket Bucket.
     * @return The bucket's summaries.
     */
    private static List<StatisticsEventSummary> bucketSummaries(
        final String dimensionName, final LocalDateTime bucket) {
      final long hourIndex = ChronoUnit.HOURS.between(LocalDateTime.of(2026, 1, 1, 0, 0), bucket);
      final List<StatisticsEventSummary> summaries = new ArrayList<>();
      if (!EMPTY_DIMENSION.equals(dimensionName) && ((hourIndex % 5L) != 0L)) {
        final long firstCount = 1L + ((hourIndex * 7L) % 13L);
        final long secondCount = 1L + ((hourIndex * 3L + dimensionName.length()) % 11L);
        final StatisticsEventSummary summary =
            new StatisticsEventSummary(CONTEXT, dimensionName, bucket);
        summary.setValueCounts(Map.of("A", firstCount, "B", secondCount));
        summary.setTotalCount(firstCount + secondCount);
        summary.setValueWeights(
            Map.of("A", BigDecimal.valueOf(firstCount * 10L), "B", BigDecimal.valueOf(secondCount * 5L)));
        summary.setTotalWeight(BigDecimal.valueOf((firstCount * 10L) + (secondCount * 5L)));
        summaries.add(summary);
      }
      return summaries;
    }

    @Override
    protected List<StatisticsEventSummary> findByPeriodCacheable(
        final String context,
        final String dimensionName,
        final LocalDateTime startDateTime,
        final LocalDateTime endDateTime) {
      if (startDateTime.equals(endDateTime)) {
        this.bucketReads.merge(dimensionName + "|" + startDateTime, 1, Integer::sum);
      }
      final List<StatisticsEventSummary> summaries = new ArrayList<>();
      for (final LocalDateTime bucket :
          StatisticsEventSummaryHelper.bucketsBetween(startDateTime, endDateTime, TRUNCATION_MINUTES)) {
        summaries.addAll(CountingComponent.bucketSummaries(dimensionName, bucket));
      }
      return summaries;
    }

    /**
     * Runs the library comparison.
     *
     * @param dimensionNames Dimensions.
     * @param schedule Schedule.
     * @param stepUnit Step unit.
     * @param steps Steps.
     * @param useTruncationBuckets Bucket mode.
     * @param parallel Parallel reads.
     * @return The comparisons.
     * @throws BusinessException If no window has data.
     */
    public List<StatisticsEventSummaryComparison> compare(
        final List<String> dimensionNames,
        final WindowSchedule schedule,
        final ChronoUnit stepUnit,
        final Integer steps,
        final boolean useTruncationBuckets,
        final boolean parallel)
        throws BusinessException {
      return this.compareByPeriodCacheable(
          CONTEXT,
          dimensionNames,
          schedule,
          ChronoUnit.HOURS,
          1,
          stepUnit,
          steps,
          useTruncationBuckets,
          parallel);
    }

    /**
     * Rebuilds the previous per-window bucket comparison from the library's own window seam: the
     * reference and every sample window fetched through {@code fetchPeriodCacheable} in bucket mode,
     * then {@code computeComparison}.
     *
     * @param dimensionNames Dimensions.
     * @param schedule Schedule.
     * @param stepUnit Step unit.
     * @param steps Steps.
     * @return The comparisons.
     * @throws BusinessException If no window has data.
     */
    public List<StatisticsEventSummaryComparison> comparePerWindow(
        final List<String> dimensionNames,
        final WindowSchedule schedule,
        final ChronoUnit stepUnit,
        final Integer steps)
        throws BusinessException {
      final List<StatisticsEventSummaryComparison> comparisons = new ArrayList<>();
      for (final String dimensionName : dimensionNames) {
        final List<StatisticsEventSummary> referenceSummaries =
            this.fetchPeriodCacheable(
                CONTEXT,
                dimensionName,
                schedule.reference().start(),
                schedule.reference().end(),
                true,
                false);
        final List<List<StatisticsEventSummary>> perWindowSummaries = new ArrayList<>();
        for (final StatisticsEventSummaryHelper.Window window : schedule.samples()) {
          perWindowSummaries.add(
              this.fetchPeriodCacheable(
                  CONTEXT, dimensionName, window.start(), window.end(), true, false));
        }
        comparisons.add(
            StatisticsEventSummaryHelper.computeComparison(
                referenceSummaries,
                perWindowSummaries,
                CONTEXT,
                dimensionName,
                schedule.reference().start(),
                ChronoUnit.HOURS,
                1,
                stepUnit,
                steps));
      }
      return comparisons;
    }
  }

  /**
   * Builds a component with a fixed 60-minute truncation.
   *
   * @return The component.
   */
  private static CountingComponent component() {
    final CountingComponent component = new CountingComponent();
    ReflectionTestUtils.setField(
        component,
        "statisticsContextConfigurationServiceComponent",
        new StatisticsContextConfigurationServiceComponent() {
          @Override
          public Long getTruncationMinutes(final String context) {
            return TRUNCATION_MINUTES;
          }
        });
    return component;
  }

  /**
   * Converts comparisons to a JSON tree, so every field (not only the identity ones) is compared.
   *
   * @param comparisons Comparisons.
   * @return The JSON tree.
   * @throws Exception If serialization fails.
   */
  private static JsonNode content(final List<StatisticsEventSummaryComparison> comparisons)
      throws Exception {
    return OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(comparisons));
  }

  /**
   * Asserts the bucket-once comparison equals both references on full content, sequential and
   * parallel, for one schedule.
   *
   * @param stepUnit Step unit.
   * @param steps Steps.
   * @throws Exception If the comparison or serialization fails.
   */
  private static void assertIdenticalComparisons(final ChronoUnit stepUnit, final int steps)
      throws Exception {
    final CountingComponent component = StatisticsEventSummaryServiceComponentFetchOnceUnitTest.component();
    final WindowSchedule schedule =
        StatisticsEventSummaryHelper.historicalSchedule(
            REFERENCE_START, ChronoUnit.HOURS, 1, stepUnit, steps, TRUNCATION_MINUTES);
    final JsonNode rangePath =
        content(component.compare(DIMENSIONS, schedule, stepUnit, steps, false, false));
    final JsonNode perWindowPath =
        content(component.comparePerWindow(DIMENSIONS, schedule, stepUnit, steps));
    final JsonNode sequential =
        content(component.compare(DIMENSIONS, schedule, stepUnit, steps, true, false));
    final JsonNode parallel =
        content(component.compare(DIMENSIONS, schedule, stepUnit, steps, true, true));
    Assertions.assertTrue(
        sequential.get(0).hasNonNull("countStats") && (sequential.get(0).get("countStats").size() > 0),
        "The comparison statistics must be serialized, or the content check proves nothing");
    Assertions.assertEquals(perWindowPath, rangePath, "The two reference paths must agree");
    Assertions.assertEquals(perWindowPath, sequential, "Bucket-once (sequential) must equal the per-window path");
    Assertions.assertEquals(perWindowPath, parallel, "Bucket-once (parallel) must equal the per-window path");
  }

  /**
   * Hourly two-week schedule.
   *
   * @throws Exception If the comparison fails.
   */
  @Test
  @DisplayName("A two-week hourly comparison reading each bucket once equals the per-window comparison")
  public void testHourlyTwoWeeksIdentical() throws Exception {
    StatisticsEventSummaryServiceComponentFetchOnceUnitTest.assertIdenticalComparisons(ChronoUnit.HOURS, 336);
  }

  /**
   * Same-hour twelve-week schedule.
   *
   * @throws Exception If the comparison fails.
   */
  @Test
  @DisplayName("A twelve-week same-hour comparison reading each bucket once equals the per-window comparison")
  public void testSameHourTwelveWeeksIdentical() throws Exception {
    StatisticsEventSummaryServiceComponentFetchOnceUnitTest.assertIdenticalComparisons(ChronoUnit.DAYS, 84);
  }

  /**
   * Each distinct bucket is read once.
   *
   * @throws Exception If the comparison fails.
   */
  @Test
  @DisplayName("A comparison in bucket mode reads each distinct bucket exactly once")
  public void testEachBucketReadOnce() throws Exception {
    for (final boolean parallel : List.of(false, true)) {
      final CountingComponent component = StatisticsEventSummaryServiceComponentFetchOnceUnitTest.component();
      final WindowSchedule schedule =
          StatisticsEventSummaryHelper.historicalSchedule(
              REFERENCE_START, ChronoUnit.HOURS, 1, ChronoUnit.HOURS, 336, TRUNCATION_MINUTES);
      component.compare(DIMENSIONS, schedule, ChronoUnit.HOURS, 336, true, parallel);
      // Reference 10:00-11:00 plus 336 one-hour windows stepped back an hour each: 338 distinct
      // buckets per dimension (the per-window path reads 674).
      Assertions.assertEquals(338 * DIMENSIONS.size(), component.bucketReads.size(), "parallel: " + parallel);
      Assertions.assertTrue(
          component.bucketReads.values().stream().allMatch(reads -> reads == 1),
          "Every bucket must be read once, parallel: " + parallel);
    }
  }

  /**
   * A dimension without data still fails the same way on both paths.
   *
   * @throws Exception Never expected.
   */
  @Test
  @DisplayName("A dimension without data fails with the same business error in bucket-once mode")
  public void testNoDataStillFails() throws Exception {
    final CountingComponent component = StatisticsEventSummaryServiceComponentFetchOnceUnitTest.component();
    final WindowSchedule schedule =
        StatisticsEventSummaryHelper.historicalSchedule(
            REFERENCE_START, ChronoUnit.HOURS, 1, ChronoUnit.DAYS, 7, TRUNCATION_MINUTES);
    final BusinessException perWindow =
        Assertions.assertThrows(
            BusinessException.class,
            () -> component.comparePerWindow(List.of(EMPTY_DIMENSION), schedule, ChronoUnit.DAYS, 7));
    final BusinessException bucketsOnce =
        Assertions.assertThrows(
            BusinessException.class,
            () -> component.compare(List.of(EMPTY_DIMENSION), schedule, ChronoUnit.DAYS, 7, true, false));
    Assertions.assertEquals(perWindow.getMessage(), bucketsOnce.getMessage());
    Assertions.assertEquals(perWindow.getStatusCode(), bucketsOnce.getStatusCode());
  }
}
