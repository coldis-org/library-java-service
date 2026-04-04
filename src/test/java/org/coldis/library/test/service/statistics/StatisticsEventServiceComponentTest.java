package org.coldis.library.test.service.statistics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.cache.CacheHelper;
import org.coldis.library.service.statistics.StatisticsEvent;
import org.coldis.library.service.statistics.StatisticsEventKey;
import org.coldis.library.service.statistics.StatisticsEventNaiveMultiDimensionProbability;
import org.coldis.library.service.statistics.StatisticsEventServiceComponent;
import org.coldis.library.service.statistics.StatisticsEventSingleDimensionProbability;
import org.coldis.library.service.statistics.StatisticsEventSummary;
import org.coldis.library.service.statistics.StatisticsEventSummaryComparison;
import org.coldis.library.service.statistics.StatisticsEventSummaryKey;
import org.coldis.library.service.statistics.StatisticsEventSummaryServiceComponent;
import org.coldis.library.service.statistics.StatisticsValuedEventDimension;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/** Statistics event service component test. */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class StatisticsEventServiceComponentTest extends ContainerTestHelper {

  /** Math context for test assertions. */
  private static final MathContext MC = MathContext.DECIMAL64;

  /** Cache helper. */
  @Autowired
  private CacheHelper cacheHelper;

  /** Statistics event service component. */
  @Autowired
  private StatisticsEventServiceComponent statisticsEventServiceComponent;

  /** Statistics event summary service component. */
  @Autowired
  private StatisticsEventSummaryServiceComponent statisticsEventSummaryServiceComponent;

  /** Fixed date time for tests (truncated to 15-minute boundary). */
  private static final LocalDateTime TEST_DATE_TIME = LocalDateTime.of(2026, 1, 15, 10, 0, 0);

  /**
   * Asserts that two BigDecimal values are equal within a tolerance.
   *
   * @param expected Expected value.
   * @param actual Actual value.
   * @param tolerance Tolerance.
   */
  private static void assertBigDecimalEquals(
      final BigDecimal expected, final BigDecimal actual, final BigDecimal tolerance) {
    Assertions.assertNotNull(actual, "Actual BigDecimal value is null");
    Assertions.assertTrue(
        expected.subtract(actual).abs().compareTo(tolerance) <= 0,
        String.format("Expected %s but got %s (tolerance %s)", expected, actual, tolerance));
  }

  /** Tolerance for BigDecimal comparisons. */
  private static final BigDecimal TOLERANCE = new BigDecimal("0.001");

  /** Cleans statistics tables and clears caches before each test. */
  @BeforeEach
  public void setUp() {
    this.purgeAllArtemisQueues();
    this.truncateTables("statistics_event", "statistics_event_summary", "statistics_context_configuration");
    this.cacheHelper.clearCaches();
  }

  /**
   * Creates a statistics event with the given parameters.
   *
   * @param context Context.
   * @param ownerKey Owner key.
   * @param dateTime Date time.
   * @param dimensionName Dimension name.
   * @param dimensionValue Dimension value.
   * @return The statistics event.
   */
  private static StatisticsEvent createEvent(
      final String context,
      final String ownerKey,
      final LocalDateTime dateTime,
      final String dimensionName,
      final String dimensionValue) {
    return new StatisticsEvent(context, ownerKey, dateTime, dimensionName, dimensionValue);
  }

  /**
   * Creates a statistics event with the given parameters and weight.
   *
   * @param context Context.
   * @param ownerKey Owner key.
   * @param dateTime Date time.
   * @param dimensionName Dimension name.
   * @param dimensionValue Dimension value.
   * @param weight Weight.
   * @return The statistics event.
   */
  private static StatisticsEvent createEvent(
      final String context,
      final String ownerKey,
      final LocalDateTime dateTime,
      final String dimensionName,
      final String dimensionValue,
      final BigDecimal weight) {
    final StatisticsEvent event =
        new StatisticsEvent(context, ownerKey, dateTime, dimensionName, dimensionValue);
    event.setWeight(weight);
    return event;
  }

  /**
   * Waits until a summary meets the expected total count (handles eventual consistency from buffered
   * delta processing).
   */
  private StatisticsEventSummary waitForSummary(
      final String context,
      final String dimensionName,
      final LocalDateTime dateTime,
      final long expectedTotalCount) throws Exception {
    Assertions.assertTrue(
        TestHelper.waitUntilValid(
            () -> {
              try {
                this.statisticsEventSummaryServiceComponent.flushSummaryDeltaBuffer();
                this.cacheHelper.clearCaches();
                return this.statisticsEventSummaryServiceComponent.findById(
                    new StatisticsEventSummaryKey(context, dimensionName, dateTime), false);
              } catch (final BusinessException exception) {
                return null;
              }
            },
            summary -> summary != null && summary.getTotalCount() == expectedTotalCount,
            TestHelper.LONG_WAIT,
            TestHelper.SHORT_WAIT));
    this.cacheHelper.clearCaches();
    final StatisticsEventSummary summary =
        this.statisticsEventSummaryServiceComponent.findById(
            new StatisticsEventSummaryKey(context, dimensionName, dateTime), false);
    // Consistency check: totalCount must equal sum of valueCounts.
    final long sumOfCounts =
        summary.getValueCounts() != null
            ? summary.getValueCounts().values().stream().mapToLong(Long::longValue).sum()
            : 0L;
    Assertions.assertEquals(
        summary.getTotalCount(), sumOfCounts, "totalCount must equal sum of valueCounts");
    // Consistency check: totalWeight must equal sum of valueWeights.
    if (summary.getValueWeights() != null && summary.getTotalWeight() != null) {
      final BigDecimal sumOfWeights =
          summary.getValueWeights().values().stream()
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      assertBigDecimalEquals(summary.getTotalWeight(), sumOfWeights, TOLERANCE);
    }
    return summary;
  }

  /**
   * Waits until a summary meets the expected total weight (for weight-change assertions).
   */
  private StatisticsEventSummary waitForSummaryWeight(
      final String context,
      final String dimensionName,
      final LocalDateTime dateTime,
      final BigDecimal expectedTotalWeight) throws Exception {
    Assertions.assertTrue(
        TestHelper.waitUntilValid(
            () -> {
              try {
                this.statisticsEventSummaryServiceComponent.flushSummaryDeltaBuffer();
                this.cacheHelper.clearCaches();
                return this.statisticsEventSummaryServiceComponent.findById(
                    new StatisticsEventSummaryKey(context, dimensionName, dateTime), false);
              } catch (final BusinessException exception) {
                return null;
              }
            },
            summary ->
                summary != null
                    && summary.getTotalWeight() != null
                    && expectedTotalWeight.subtract(summary.getTotalWeight()).abs().compareTo(TOLERANCE) <= 0,
            TestHelper.LONG_WAIT,
            TestHelper.SHORT_WAIT));
    this.cacheHelper.clearCaches();
    return this.statisticsEventSummaryServiceComponent.findById(
        new StatisticsEventSummaryKey(context, dimensionName, dateTime), false);
  }

  /**
   * Tests synchronous upsert of a statistics event and verifies the event is persisted and the
   * summary is updated.
   */
  @Test
  public void testSyncUpsert() throws Exception {
    // Upserts a statistics event.
    final StatisticsEvent createdEvent =
        this.statisticsEventServiceComponent.upsertStatisticsEvent(
            createEvent("test-sync", "owner-1", TEST_DATE_TIME, "city", "sao-paulo"));

    // Verifies the event was persisted with the correct values.
    Assertions.assertNotNull(createdEvent);
    Assertions.assertEquals("sao-paulo", createdEvent.getDimensionValue());

    // Retrieves the event via findById and verifies it.
    final StatisticsEvent retrievedEvent =
        this.statisticsEventServiceComponent.findById(
            new StatisticsEventKey("test-sync", "owner-1", "city"), false);
    Assertions.assertEquals("sao-paulo", retrievedEvent.getDimensionValue());

    // Waits for the summary buffer to flush and verifies the summary.
    final StatisticsEventSummary summary = this.waitForSummary("test-sync", "city", TEST_DATE_TIME, 1L);
    Assertions.assertEquals(1L, summary.getValueCounts().get("sao-paulo"));
    assertBigDecimalEquals(BigDecimal.ONE, summary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(BigDecimal.ONE, summary.getValueWeights().get("sao-paulo"), TOLERANCE);
  }

  /**
   * Tests that upserting multiple events with different owners aggregates correctly in the summary.
   */
  @Test
  public void testSummaryAggregation() throws Exception {
    // Upserts events for three owners with different city values and weights.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-aggregation", "owner-a", TEST_DATE_TIME, "city", "rio",
            new BigDecimal("100.00")));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-aggregation", "owner-b", TEST_DATE_TIME, "city", "rio",
            new BigDecimal("50.25")));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-aggregation", "owner-c", TEST_DATE_TIME, "city", "belo-horizonte",
            new BigDecimal("30.00")));

    // Verifies the summary has the correct distribution.
    final StatisticsEventSummary summary =
        this.waitForSummary("test-aggregation", "city", TEST_DATE_TIME, 3L);
    Assertions.assertEquals(2L, summary.getValueCounts().get("rio"));
    Assertions.assertEquals(1L, summary.getValueCounts().get("belo-horizonte"));
    assertBigDecimalEquals(
        new BigDecimal("150.25"), summary.getValueWeights().get("rio"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("30.00"), summary.getValueWeights().get("belo-horizonte"), TOLERANCE);
    assertBigDecimalEquals(new BigDecimal("180.25"), summary.getTotalWeight(), TOLERANCE);
  }

  /**
   * Tests that changing a dimension value decrements the old count and increments the new count in
   * the summary.
   */
  @Test
  public void testDimensionValueChange() throws Exception {
    // Creates an initial event.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-change", "owner-x", TEST_DATE_TIME, "state", "SP"));

    // Verifies initial summary state.
    final StatisticsEventSummary summaryBeforeChange =
        this.waitForSummary("test-change", "state", TEST_DATE_TIME, 1L);
    Assertions.assertEquals(1L, summaryBeforeChange.getValueCounts().get("SP"));
    assertBigDecimalEquals(BigDecimal.ONE, summaryBeforeChange.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(BigDecimal.ONE, summaryBeforeChange.getValueWeights().get("SP"), TOLERANCE);

    // Changes the dimension value for the same owner/context/time/dimension.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-change", "owner-x", TEST_DATE_TIME, "state", "RJ"));

    // Verifies the summary reflects the change: SP decremented (removed), RJ incremented.
    // Wait for RJ to appear (total stays 1, but value distribution changes).
    Assertions.assertTrue(
        TestHelper.waitUntilValid(
            () -> {
              try {
                this.statisticsEventSummaryServiceComponent.flushSummaryDeltaBuffer();
                this.cacheHelper.clearCaches();
                return this.statisticsEventSummaryServiceComponent.findById(
                    new StatisticsEventSummaryKey("test-change", "state", TEST_DATE_TIME), false);
              } catch (final BusinessException exception) {
                return null;
              }
            },
            summary -> summary != null && summary.getValueCounts().containsKey("RJ"),
            TestHelper.LONG_WAIT,
            TestHelper.SHORT_WAIT));
    this.cacheHelper.clearCaches();
    final StatisticsEventSummary summaryAfterChange =
        this.statisticsEventSummaryServiceComponent.findById(
            new StatisticsEventSummaryKey("test-change", "state", TEST_DATE_TIME), false);
    Assertions.assertEquals(1L, summaryAfterChange.getTotalCount());
    Assertions.assertNull(summaryAfterChange.getValueCounts().get("SP"));
    Assertions.assertEquals(1L, summaryAfterChange.getValueCounts().get("RJ"));
    assertBigDecimalEquals(BigDecimal.ONE, summaryAfterChange.getTotalWeight(), TOLERANCE);
    Assertions.assertNull(summaryAfterChange.getValueWeights().get("SP"));
    assertBigDecimalEquals(BigDecimal.ONE, summaryAfterChange.getValueWeights().get("RJ"), TOLERANCE);

    // Verifies the event itself was updated.
    final StatisticsEvent updatedEvent =
        this.statisticsEventServiceComponent.findById(
            new StatisticsEventKey("test-change", "owner-x", "state"), false);
    Assertions.assertEquals("RJ", updatedEvent.getDimensionValue());
  }

  /**
   * Tests that upserting the same event with the same dimension value does not change the summary
   * counts (idempotent).
   */
  @Test
  public void testIdempotentUpsert() throws Exception {
    // Creates an event.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-idempotent", "owner-idem", TEST_DATE_TIME, "device", "mobile"));

    // Upserts the same event with the same value again.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-idempotent", "owner-idem", TEST_DATE_TIME, "device", "mobile"));

    // Verifies the summary count did not double.
    final StatisticsEventSummary summary =
        this.waitForSummary("test-idempotent", "device", TEST_DATE_TIME, 1L);
    Assertions.assertEquals(1L, summary.getValueCounts().get("mobile"));
    assertBigDecimalEquals(BigDecimal.ONE, summary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(BigDecimal.ONE, summary.getValueWeights().get("mobile"), TOLERANCE);
  }

  /**
   * Tests batch upsert of multiple events via upsertAllStatisticsEvents, and verifies the summary
   * reflects all events after the buffer is flushed.
   */
  @Test
  public void testBatchUpsert() throws Exception {
    // Prepares a batch of events.
    final List<StatisticsEvent> eventBatch =
        List.of(
            createEvent("test-batch", "batch-owner-1", TEST_DATE_TIME, "browser", "chrome"),
            createEvent("test-batch", "batch-owner-2", TEST_DATE_TIME, "browser", "firefox"),
            createEvent("test-batch", "batch-owner-3", TEST_DATE_TIME, "browser", "chrome"));

    // Sends the batch (goes through buffer -> flush -> JMS -> persistence).
    this.statisticsEventServiceComponent.upsertAllStatisticsEvents(eventBatch);

    // Waits for the buffer to flush and async processing to complete.
    final StatisticsEventSummary summary =
        this.waitForSummary("test-batch", "browser", TEST_DATE_TIME, 3L);
    Assertions.assertEquals(2L, summary.getValueCounts().get("chrome"));
    Assertions.assertEquals(1L, summary.getValueCounts().get("firefox"));
    assertBigDecimalEquals(new BigDecimal("3"), summary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(new BigDecimal("2"), summary.getValueWeights().get("chrome"), TOLERANCE);
    assertBigDecimalEquals(BigDecimal.ONE, summary.getValueWeights().get("firefox"), TOLERANCE);
  }

  /** Tests that findByPeriod aggregates summaries across multiple 15-minute buckets. */
  @Test
  public void testFindByPeriod() throws Exception {
    // Creates events across three different 15-minute intervals.
    final LocalDateTime time1 = LocalDateTime.of(2026, 1, 15, 10, 0, 0);
    final LocalDateTime time2 = LocalDateTime.of(2026, 1, 15, 10, 15, 0);
    final LocalDateTime time3 = LocalDateTime.of(2026, 1, 15, 10, 30, 0);

    // Bucket 1: two events.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-period", "owner-1", time1, "city", "sao-paulo",
            new BigDecimal("10.00")));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-period", "owner-2", time1, "city", "rio",
            new BigDecimal("20.00")));

    // Bucket 2: one event.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-period", "owner-3", time2, "city", "sao-paulo",
            new BigDecimal("15.00")));

    // Bucket 3: one event.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-period", "owner-4", time3, "city", "belo-horizonte",
            new BigDecimal("5.00")));

    // Waits for all three buckets to be populated.
    this.waitForSummary("test-period", "city", time1, 2L);
    this.waitForSummary("test-period", "city", time2, 1L);
    this.waitForSummary("test-period", "city", time3, 1L);

    // Queries the full period spanning all three buckets.
    this.cacheHelper.clearCaches();
    final StatisticsEventSummary periodSummary =
        this.statisticsEventSummaryServiceComponent.findByPeriod("test-period", "city", time1, time3);
    Assertions.assertNotNull(periodSummary);
    Assertions.assertEquals(4L, periodSummary.getTotalCount());
    Assertions.assertEquals(2L, periodSummary.getValueCounts().get("sao-paulo"));
    Assertions.assertEquals(1L, periodSummary.getValueCounts().get("rio"));
    Assertions.assertEquals(1L, periodSummary.getValueCounts().get("belo-horizonte"));
    assertBigDecimalEquals(new BigDecimal("50.00"), periodSummary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("25.00"), periodSummary.getValueWeights().get("sao-paulo"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("20.00"), periodSummary.getValueWeights().get("rio"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("5.00"), periodSummary.getValueWeights().get("belo-horizonte"), TOLERANCE);

    // Queries a partial period (only first two buckets).
    this.cacheHelper.clearCaches();
    final StatisticsEventSummary partialSummary =
        this.statisticsEventSummaryServiceComponent.findByPeriod("test-period", "city", time1, time2);
    Assertions.assertNotNull(partialSummary);
    Assertions.assertEquals(3L, partialSummary.getTotalCount());
    Assertions.assertEquals(2L, partialSummary.getValueCounts().get("sao-paulo"));
    Assertions.assertEquals(1L, partialSummary.getValueCounts().get("rio"));
    Assertions.assertNull(partialSummary.getValueCounts().get("belo-horizonte"));
    assertBigDecimalEquals(new BigDecimal("45.00"), partialSummary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("25.00"), partialSummary.getValueWeights().get("sao-paulo"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("20.00"), partialSummary.getValueWeights().get("rio"), TOLERANCE);
  }

  /** Tests that findByPeriod returns not found when no summaries exist in the period. */
  @Test
  public void testFindByPeriodNotFound() {
    Assertions.assertThrows(
        BusinessException.class,
        () ->
            this.statisticsEventSummaryServiceComponent.findByPeriod(
                "non-existent",
                "no-dimension",
                LocalDateTime.of(2026, 6, 1, 0, 0, 0),
                LocalDateTime.of(2026, 6, 1, 1, 0, 0)));
  }

  /**
   * Tests compareByPeriod: computes moving average and std dev of the same 1-hour window across
   * multiple days, and compares against the reference window with z-scores.
   */
  @Test
  public void testCompareByPeriod() throws Exception {
    // Creates events in the 10:00-11:00 window across 3 consecutive days.
    // Day 1 (Jan 12): 2 events, both "sao-paulo" -> totalCount=2, sao-paulo=2
    final LocalDateTime day1 = LocalDateTime.of(2026, 1, 12, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-1", day1, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-2", day1, "city", "sao-paulo"));

    // Day 2 (Jan 13): 4 events, 3 "sao-paulo" + 1 "rio" -> totalCount=4, sao-paulo=3, rio=1
    final LocalDateTime day2 = LocalDateTime.of(2026, 1, 13, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-3", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-4", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-5", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-6", day2, "city", "rio"));

    // Day 3 (Jan 14) -- reference day: 3 events, 1 "sao-paulo" + 2 "rio" -> totalCount=3
    final LocalDateTime day3 = LocalDateTime.of(2026, 1, 14, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-7", day3, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-8", day3, "city", "rio"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison", "owner-9", day3, "city", "rio"));

    // Waits for all summaries to be populated.
    this.waitForSummary("test-comparison", "city", day1, 2L);
    this.waitForSummary("test-comparison", "city", day2, 4L);
    this.waitForSummary("test-comparison", "city", day3, 3L);

    // Compares: reference=day3 10:00, window=1 HOUR, step=1 DAY, steps=2 (day1 and day2).
    this.cacheHelper.clearCaches();
    final StatisticsEventSummaryComparison comparison =
        this.statisticsEventSummaryServiceComponent.compareByPeriod(
            "test-comparison", "city", day3, ChronoUnit.HOURS, 1, ChronoUnit.DAYS, 2);

    Assertions.assertNotNull(comparison);
    Assertions.assertEquals(2, comparison.getSampleSize());

    // Historical totals: day1=2, day2=4 -> avg=3.0, stdDev=1.0
    assertBigDecimalEquals(new BigDecimal("3.0"), comparison.getAverageTotalCount(), TOLERANCE);
    assertBigDecimalEquals(new BigDecimal("1.0"), comparison.getStdDevTotalCount(), TOLERANCE);

    // Historical sao-paulo: day1=2, day2=3 -> avg=2.5, stdDev=0.5
    assertBigDecimalEquals(
        new BigDecimal("2.5"), comparison.getAverageValueCounts().get("sao-paulo"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("0.5"), comparison.getStdDevValueCounts().get("sao-paulo"), TOLERANCE);

    // Historical rio: day1=0, day2=1 -> avg=0.5, stdDev=0.5
    assertBigDecimalEquals(
        new BigDecimal("0.5"), comparison.getAverageValueCounts().get("rio"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("0.5"), comparison.getStdDevValueCounts().get("rio"), TOLERANCE);

    // Historical ratios sao-paulo: day1=2/2=1.0, day2=3/4=0.75 -> avg=0.875, stdDev=0.125
    assertBigDecimalEquals(
        new BigDecimal("0.875"), comparison.getAverageValueRatios().get("sao-paulo"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("0.125"), comparison.getStdDevValueRatios().get("sao-paulo"), TOLERANCE);

    // Historical ratios rio: day1=0/2=0.0, day2=1/4=0.25 -> avg=0.125, stdDev=0.125
    assertBigDecimalEquals(
        new BigDecimal("0.125"), comparison.getAverageValueRatios().get("rio"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("0.125"), comparison.getStdDevValueRatios().get("rio"), TOLERANCE);

    // Reference window: day3 -> totalCount=3, sao-paulo=1, rio=2
    Assertions.assertEquals(3L, comparison.getReferenceTotalCount());
    Assertions.assertEquals(1L, comparison.getReferenceValueCounts().get("sao-paulo"));
    Assertions.assertEquals(2L, comparison.getReferenceValueCounts().get("rio"));

    // Reference ratios: sao-paulo=1/3~0.333, rio=2/3~0.667
    assertBigDecimalEquals(
        BigDecimal.ONE.divide(BigDecimal.valueOf(3), MC),
        comparison.getReferenceValueRatios().get("sao-paulo"),
        TOLERANCE);
    assertBigDecimalEquals(
        BigDecimal.valueOf(2).divide(BigDecimal.valueOf(3), MC),
        comparison.getReferenceValueRatios().get("rio"),
        TOLERANCE);

    // Z-score total count: ref=3, avg=3.0, stdDev=1.0 -> z=(3-3)/1=0.0
    assertBigDecimalEquals(BigDecimal.ZERO, comparison.getZScoreTotalCount(), TOLERANCE);

    // Z-score sao-paulo count: ref=1, avg=2.5, stdDev=0.5 -> z=(1-2.5)/0.5=-3.0
    assertBigDecimalEquals(
        new BigDecimal("-3.0"), comparison.getZScoreValueCounts().get("sao-paulo"), TOLERANCE);

    // Z-score rio count: ref=2, avg=0.5, stdDev=0.5 -> z=(2-0.5)/0.5=3.0
    assertBigDecimalEquals(
        new BigDecimal("3.0"), comparison.getZScoreValueCounts().get("rio"), TOLERANCE);

    // Z-score sao-paulo ratio: ref=0.333, avg=0.875, stdDev=0.125 -> z=(0.333-0.875)/0.125~-4.333
    final BigDecimal expectedZScoreSpRatio =
        BigDecimal.ONE
            .divide(BigDecimal.valueOf(3), MC)
            .subtract(new BigDecimal("0.875"))
            .divide(new BigDecimal("0.125"), MC);
    assertBigDecimalEquals(
        expectedZScoreSpRatio, comparison.getZScoreValueRatios().get("sao-paulo"), TOLERANCE);

    // Z-score rio ratio: ref=0.667, avg=0.125, stdDev=0.125 -> z=(0.667-0.125)/0.125~4.333
    final BigDecimal expectedZScoreRioRatio =
        BigDecimal.valueOf(2)
            .divide(BigDecimal.valueOf(3), MC)
            .subtract(new BigDecimal("0.125"))
            .divide(new BigDecimal("0.125"), MC);
    assertBigDecimalEquals(
        expectedZScoreRioRatio, comparison.getZScoreValueRatios().get("rio"), TOLERANCE);
  }

  /** Tests compareByPeriod with WEEKS step unit -- comparing the same weekday across weeks. */
  @Test
  public void testCompareByPeriodWeeklyStep() throws Exception {
    // Week 1 (Jan 1, Thursday): 1 event
    final LocalDateTime week1 = LocalDateTime.of(2026, 1, 1, 14, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison-weekly", "owner-w1", week1, "device", "mobile"));

    // Week 2 (Jan 8, Thursday): 3 events
    final LocalDateTime week2 = LocalDateTime.of(2026, 1, 8, 14, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison-weekly", "owner-w2", week2, "device", "mobile"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison-weekly", "owner-w3", week2, "device", "desktop"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison-weekly", "owner-w4", week2, "device", "mobile"));

    // Reference (Jan 15, Thursday): 2 events
    final LocalDateTime ref = LocalDateTime.of(2026, 1, 15, 14, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison-weekly", "owner-w5", ref, "device", "desktop"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-comparison-weekly", "owner-w6", ref, "device", "desktop"));

    // Waits for all summaries to be populated.
    this.waitForSummary("test-comparison-weekly", "device", week1, 1L);
    this.waitForSummary("test-comparison-weekly", "device", week2, 3L);
    this.waitForSummary("test-comparison-weekly", "device", ref, 2L);

    // Compares: reference=Jan 15 14:00, window=2 HOURS, step=WEEKS, steps=2
    this.cacheHelper.clearCaches();
    final StatisticsEventSummaryComparison comparison =
        this.statisticsEventSummaryServiceComponent.compareByPeriod(
            "test-comparison-weekly", "device", ref, ChronoUnit.HOURS, 2, ChronoUnit.WEEKS, 2);

    Assertions.assertNotNull(comparison);
    Assertions.assertEquals(2, comparison.getSampleSize());

    // Historical totals: week1=1, week2=3 -> avg=2.0, stdDev=1.0
    assertBigDecimalEquals(new BigDecimal("2.0"), comparison.getAverageTotalCount(), TOLERANCE);
    assertBigDecimalEquals(new BigDecimal("1.0"), comparison.getStdDevTotalCount(), TOLERANCE);

    // Reference: totalCount=2, desktop=2
    Assertions.assertEquals(2L, comparison.getReferenceTotalCount());
    Assertions.assertEquals(2L, comparison.getReferenceValueCounts().get("desktop"));
  }

  /** Tests compareByPeriod returns an error when no historical data exists. */
  @Test
  public void testCompareByPeriodNoData() {
    Assertions.assertThrows(
        Exception.class,
        () ->
            this.statisticsEventSummaryServiceComponent.compareByPeriod(
                "non-existent",
                "no-dimension",
                LocalDateTime.of(2026, 6, 1, 10, 0, 0),
                ChronoUnit.HOURS,
                1,
                ChronoUnit.DAYS,
                7));
  }

  /**
   * Tests singleDimensionProbabilityByPeriod: computes probability of a dimension value based on
   * historical distribution including the reference period.
   */
  @Test
  public void testSingleDimensionProbabilityByPeriod() throws Exception {
    // Creates events in the 10:00-11:00 window across 3 consecutive days.
    // Day 1 (Jan 12): 2 events, both "sao-paulo" -> ratio sao-paulo=1.0, rio=0.0
    final LocalDateTime day1 = LocalDateTime.of(2026, 1, 12, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-1", day1, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-2", day1, "city", "sao-paulo"));

    // Day 2 (Jan 13): 4 events, 3 "sao-paulo" + 1 "rio" -> ratio sao-paulo=0.75, rio=0.25
    final LocalDateTime day2 = LocalDateTime.of(2026, 1, 13, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-3", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-4", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-5", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-6", day2, "city", "rio"));

    // Day 3 (Jan 14) -- reference day: 3 events, 1 "sao-paulo" + 2 "rio"
    // -> ratio sao-paulo=1/3~0.333, rio=2/3~0.667
    final LocalDateTime day3 = LocalDateTime.of(2026, 1, 14, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-7", day3, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-8", day3, "city", "rio"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-probability", "prob-owner-9", day3, "city", "rio"));

    // Waits for all summaries to be populated.
    this.waitForSummary("test-probability", "city", day1, 2L);
    this.waitForSummary("test-probability", "city", day2, 4L);
    this.waitForSummary("test-probability", "city", day3, 3L);

    // Probability for "sao-paulo": reference=day3, window=1 HOUR, step=1 DAY, steps=3
    // All 3 days are included (i=0,1,2 -> day3, day2, day1).
    // sao-paulo ratios: day3=1/3~0.333, day2=3/4=0.75, day1=2/2=1.0
    // avg = (0.333 + 0.75 + 1.0) / 3 ~ 0.694
    // sao-paulo counts: day3=1, day2=3, day1=2 -> avg=2.0
    this.cacheHelper.clearCaches();
    final StatisticsEventSingleDimensionProbability spProbability =
        this.statisticsEventSummaryServiceComponent.singleDimensionProbabilityByPeriod(
            "test-probability",
            new StatisticsValuedEventDimension("city", "sao-paulo"),
            day3,
            ChronoUnit.HOURS,
            1,
            ChronoUnit.DAYS,
            3);

    Assertions.assertNotNull(spProbability);
    Assertions.assertEquals("test-probability", spProbability.getContext());
    Assertions.assertEquals("city", spProbability.getDimensionName());
    Assertions.assertEquals("sao-paulo", spProbability.getDimensionValue());
    Assertions.assertEquals(3, spProbability.getSampleSize());

    // Average probability: (1/3 + 3/4 + 1.0) / 3
    final BigDecimal expectedSpProb =
        BigDecimal.ONE
            .divide(BigDecimal.valueOf(3), MC)
            .add(new BigDecimal("0.75"))
            .add(BigDecimal.ONE)
            .divide(BigDecimal.valueOf(3), MC);
    assertBigDecimalEquals(expectedSpProb, spProbability.getProbability(), TOLERANCE);
    Assertions.assertNotNull(spProbability.getStdDevProbability());

    // Average count: (1 + 3 + 2) / 3 = 2.0
    assertBigDecimalEquals(new BigDecimal("2.0"), spProbability.getAverageCount(), TOLERANCE);
    Assertions.assertNotNull(spProbability.getStdDevCount());

    // Probability for "rio": ratios day3=2/3~0.667, day2=1/4=0.25, day1=0/2=0.0
    // avg = (0.667 + 0.25 + 0.0) / 3 ~ 0.306
    this.cacheHelper.clearCaches();
    final StatisticsEventSingleDimensionProbability rioProbability =
        this.statisticsEventSummaryServiceComponent.singleDimensionProbabilityByPeriod(
            "test-probability",
            new StatisticsValuedEventDimension("city", "rio"),
            day3,
            ChronoUnit.HOURS,
            1,
            ChronoUnit.DAYS,
            3);

    final BigDecimal expectedRioProb =
        BigDecimal.valueOf(2)
            .divide(BigDecimal.valueOf(3), MC)
            .add(new BigDecimal("0.25"))
            .add(BigDecimal.ZERO)
            .divide(BigDecimal.valueOf(3), MC);
    assertBigDecimalEquals(expectedRioProb, rioProbability.getProbability(), TOLERANCE);

    // Average count: (2 + 1 + 0) / 3 = 1.0
    assertBigDecimalEquals(new BigDecimal("1.0"), rioProbability.getAverageCount(), TOLERANCE);
  }

  /** Tests singleDimensionProbabilityByPeriod returns an error when no data exists. */
  @Test
  public void testSingleDimensionProbabilityByPeriodNoData() {
    Assertions.assertThrows(
        Exception.class,
        () ->
            this.statisticsEventSummaryServiceComponent.singleDimensionProbabilityByPeriod(
                "non-existent",
                new StatisticsValuedEventDimension("no-dimension", "no-value"),
                LocalDateTime.of(2026, 6, 1, 10, 0, 0),
                ChronoUnit.HOURS,
                1,
                ChronoUnit.DAYS,
                7));
  }

  /**
   * Tests naiveMultiDimensionProbabilityByPeriod: computes joint probability of two dimensions
   * assuming independence (P(A ^ B) = P(A) x P(B)).
   */
  @Test
  public void testNaiveMultiDimensionProbabilityByPeriod() throws Exception {
    // Creates events in the 10:00-11:00 window across 3 consecutive days with two dimensions.
    // Day 1 (Jan 12): city events (2 sao-paulo), device events (1 mobile, 1 desktop)
    final LocalDateTime day1 = LocalDateTime.of(2026, 1, 12, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-1", day1, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-2", day1, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-1", day1, "device", "mobile"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-2", day1, "device", "desktop"));

    // Day 2 (Jan 13): city events (3 sao-paulo, 1 rio), device events (2 mobile, 2 desktop)
    final LocalDateTime day2 = LocalDateTime.of(2026, 1, 13, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-3", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-4", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-5", day2, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-6", day2, "city", "rio"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-3", day2, "device", "mobile"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-4", day2, "device", "mobile"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-5", day2, "device", "desktop"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-6", day2, "device", "desktop"));

    // Day 3 (Jan 14): city events (1 sao-paulo, 2 rio), device events (3 mobile)
    final LocalDateTime day3 = LocalDateTime.of(2026, 1, 14, 10, 0, 0);
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-7", day3, "city", "sao-paulo"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-8", day3, "city", "rio"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-9", day3, "city", "rio"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-7", day3, "device", "mobile"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-8", day3, "device", "mobile"));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-joint", "joint-owner-9", day3, "device", "mobile"));

    // Waits for all summaries to be populated.
    this.waitForSummary("test-joint", "city", day1, 2L);
    this.waitForSummary("test-joint", "city", day2, 4L);
    this.waitForSummary("test-joint", "city", day3, 3L);
    this.waitForSummary("test-joint", "device", day1, 2L);
    this.waitForSummary("test-joint", "device", day2, 4L);
    this.waitForSummary("test-joint", "device", day3, 3L);

    // Computes individual probabilities for verification.
    // city=sao-paulo: day1=2/2=1.0, day2=3/4=0.75, day3=1/3~0.333 -> avg~0.694
    // device=mobile: day1=1/2=0.5, day2=2/4=0.5, day3=3/3=1.0 -> avg~0.667
    this.cacheHelper.clearCaches();
    final StatisticsEventSingleDimensionProbability spProbability =
        this.statisticsEventSummaryServiceComponent.singleDimensionProbabilityByPeriod(
            "test-joint",
            new StatisticsValuedEventDimension("city", "sao-paulo"),
            day3,
            ChronoUnit.HOURS,
            1,
            ChronoUnit.DAYS,
            3);
    this.cacheHelper.clearCaches();
    final StatisticsEventSingleDimensionProbability mobileProbability =
        this.statisticsEventSummaryServiceComponent.singleDimensionProbabilityByPeriod(
            "test-joint",
            new StatisticsValuedEventDimension("device", "mobile"),
            day3,
            ChronoUnit.HOURS,
            1,
            ChronoUnit.DAYS,
            3);

    // Calls multi-dimension probability.
    this.cacheHelper.clearCaches();
    final StatisticsEventNaiveMultiDimensionProbability jointResult =
        this.statisticsEventSummaryServiceComponent.naiveMultiDimensionProbabilityByPeriod(
            "test-joint",
            List.of(
                new StatisticsValuedEventDimension("city", "sao-paulo"),
                new StatisticsValuedEventDimension("device", "mobile")),
            day3,
            ChronoUnit.HOURS,
            1,
            ChronoUnit.DAYS,
            3);

    Assertions.assertNotNull(jointResult);
    Assertions.assertEquals("test-joint", jointResult.getContext());
    Assertions.assertEquals(2, jointResult.getIndividualProbabilities().size());

    // Verifies individual probabilities match.
    assertBigDecimalEquals(
        spProbability.getProbability(),
        jointResult.getIndividualProbabilities().get(0).getProbability(),
        TOLERANCE);
    assertBigDecimalEquals(
        mobileProbability.getProbability(),
        jointResult.getIndividualProbabilities().get(1).getProbability(),
        TOLERANCE);

    // Verifies joint probability = P(city=sao-paulo) x P(device=mobile).
    final BigDecimal expectedJoint =
        spProbability.getProbability().multiply(mobileProbability.getProbability(), MC);
    assertBigDecimalEquals(expectedJoint, jointResult.getJointProbability(), TOLERANCE);
  }

  /**
   * Tests that upsertAllStatisticsEvents sends events through the buffered path and they are
   * eventually persisted and the summary is updated.
   */
  @Test
  public void testBufferedUpsert() throws Exception {
    final StatisticsEvent event =
        createEvent("test-buffered", "buffered-owner-1", TEST_DATE_TIME, "channel", "web");

    // Sends the event through the buffered path.
    this.statisticsEventServiceComponent.upsertAllStatisticsEvents(List.of(event));

    // Waits for the event to be persisted by the buffer flush.
    Assertions.assertTrue(
        TestHelper.waitUntilValid(
            () -> {
              try {
                return this.statisticsEventServiceComponent.findById(
                    new StatisticsEventKey("test-buffered", "buffered-owner-1", "channel"), false);
              } catch (final BusinessException exception) {
                return null;
              }
            },
            result -> result != null && "web".equals(result.getDimensionValue()),
            TestHelper.LONG_WAIT,
            TestHelper.SHORT_WAIT));

    // Verifies the summary was also updated.
    final StatisticsEventSummary summary =
        this.waitForSummary("test-buffered", "channel", TEST_DATE_TIME, 1L);
    Assertions.assertEquals(1L, summary.getValueCounts().get("web"));
  }

  /** Tests that a non-existent event returns a not-found error. */
  @Test
  public void testFindNonExistentEvent() {
    Assertions.assertThrows(
        BusinessException.class,
        () ->
            this.statisticsEventServiceComponent.findById(
                new StatisticsEventKey("non-existent", "no-owner", "no-dimension"), false));
  }

  /**
   * Tests that deleting a statistics event removes it and decrements the corresponding summary.
   */
  @Test
  public void testDeleteStatisticsEvent() throws Exception {
    // Creates events for the same context/dimension/time but different owners and weights.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-delete", "owner-1", TEST_DATE_TIME, "city", "sao-paulo",
            new BigDecimal("100.50")));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-delete", "owner-2", TEST_DATE_TIME, "city", "sao-paulo",
            new BigDecimal("200.00")));
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-delete", "owner-3", TEST_DATE_TIME, "city", "rio",
            new BigDecimal("50.00")));

    // Verifies the summary has 3 total (2 sao-paulo, 1 rio).
    StatisticsEventSummary summary =
        this.waitForSummary("test-delete", "city", TEST_DATE_TIME, 3L);
    Assertions.assertEquals(2L, summary.getValueCounts().get("sao-paulo"));
    Assertions.assertEquals(1L, summary.getValueCounts().get("rio"));
    assertBigDecimalEquals(new BigDecimal("350.50"), summary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("300.50"), summary.getValueWeights().get("sao-paulo"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("50.00"), summary.getValueWeights().get("rio"), TOLERANCE);

    // Deletes one sao-paulo event (find with lock, then delete).
    final StatisticsEvent eventToDelete =
        this.statisticsEventServiceComponent.findById(
            new StatisticsEventKey("test-delete", "owner-1", "city"), true);
    this.statisticsEventServiceComponent.deleteStatisticsEvent(eventToDelete);

    // Verifies the event is gone.
    Assertions.assertThrows(
        BusinessException.class,
        () ->
            this.statisticsEventServiceComponent.findById(
                new StatisticsEventKey("test-delete", "owner-1", "city"), false));

    // Verifies the summary was decremented.
    summary = this.waitForSummary("test-delete", "city", TEST_DATE_TIME, 2L);
    Assertions.assertEquals(1L, summary.getValueCounts().get("sao-paulo"));
    Assertions.assertEquals(1L, summary.getValueCounts().get("rio"));
    assertBigDecimalEquals(new BigDecimal("250.00"), summary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("200.00"), summary.getValueWeights().get("sao-paulo"), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("50.00"), summary.getValueWeights().get("rio"), TOLERANCE);
  }

  /**
   * Tests that when an event moves to a different time bucket, the old summary is decremented and
   * the new summary is incremented.
   */
  @Test
  public void testDateTimeChangeMovesSummaryBucket() throws Exception {
    final LocalDateTime bucket1 = LocalDateTime.of(2026, 2, 1, 10, 0, 0);
    final LocalDateTime bucket2 = LocalDateTime.of(2026, 2, 1, 10, 15, 0);

    // Creates an event in bucket 1.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-bucket-move", "owner-1", bucket1, "city", "sao-paulo",
            new BigDecimal("100.00")));

    // Verifies bucket 1 has the event.
    StatisticsEventSummary summary1 =
        this.waitForSummary("test-bucket-move", "city", bucket1, 1L);
    assertBigDecimalEquals(new BigDecimal("100.00"), summary1.getTotalWeight(), TOLERANCE);

    // Upserts the same event (same owner/context/dimension) but with a different dateTime (bucket 2).
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-bucket-move", "owner-1", bucket2, "city", "sao-paulo",
            new BigDecimal("200.00")));

    // Verifies bucket 2 has the event.
    final StatisticsEventSummary summary2 =
        this.waitForSummary("test-bucket-move", "city", bucket2, 1L);
    assertBigDecimalEquals(new BigDecimal("200.00"), summary2.getTotalWeight(), TOLERANCE);

    // Verifies bucket 1 was decremented (event moved out).
    summary1 = this.waitForSummary("test-bucket-move", "city", bucket1, 0L);
    assertBigDecimalEquals(BigDecimal.ZERO, summary1.getTotalWeight(), TOLERANCE);
  }

  /**
   * Tests that changing only the weight (same dimensionValue, same dateTime) updates the summary
   * weight accordingly.
   */
  @Test
  public void testWeightOnlyChangeUpdatesSummary() throws Exception {
    // Creates an event with weight 100.
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-weight-change", "owner-1", TEST_DATE_TIME, "amount", "purchase",
            new BigDecimal("100.00")));

    // Verifies initial summary.
    StatisticsEventSummary summary =
        this.waitForSummary("test-weight-change", "amount", TEST_DATE_TIME, 1L);
    assertBigDecimalEquals(new BigDecimal("100.00"), summary.getTotalWeight(), TOLERANCE);
    assertBigDecimalEquals(
        new BigDecimal("100.00"), summary.getValueWeights().get("purchase"), TOLERANCE);

    // Upserts the same event with a different weight (same dimensionValue, same dateTime).
    this.statisticsEventServiceComponent.upsertStatisticsEvent(
        createEvent("test-weight-change", "owner-1", TEST_DATE_TIME, "amount", "purchase",
            new BigDecimal("250.00")));

    // Verifies the summary weight was updated (count stays 1, weight changes to 250).
    summary =
        this.waitForSummaryWeight("test-weight-change", "amount", TEST_DATE_TIME, new BigDecimal("250.00"));
    Assertions.assertEquals(1L, summary.getTotalCount());
    assertBigDecimalEquals(
        new BigDecimal("250.00"), summary.getValueWeights().get("purchase"), TOLERANCE);
  }
}
