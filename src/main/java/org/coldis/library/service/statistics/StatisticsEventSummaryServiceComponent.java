package org.coldis.library.service.statistics;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.BufferedReducer;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics event summary service component. Contains the business logic for summary operations
 * (find, create, increment, decrement, comparison, probability).
 */
@Component
@ConditionalOnProperty(name = "org.coldis.configuration.service.statistics-enabled", matchIfMissing = false)
public class StatisticsEventSummaryServiceComponent {

  /** Logger. */
  private static final Logger LOGGER =
      LoggerFactory.getLogger(StatisticsEventSummaryServiceComponent.class);

  /** Math context for BigDecimal operations. */
  private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

  /** Maximum total window span (6 months). */
  private static final Duration MAX_TOTAL_WINDOW = Duration.ofDays(183);

  /** Statistics context configuration service component. */
  @Autowired
  private StatisticsContextConfigurationServiceComponent
      statisticsContextConfigurationServiceComponent;

  /** Statistics event summary repository. */
  @Autowired private StatisticsEventSummaryRepository statisticsEventSummaryRepository;

  /** JMS template. */
  @Autowired private JmsTemplate jmsTemplate;

  /** Internal queue for summary delta processing. */
  private static final String SUMMARY_DELTA_QUEUE = "statistics-event/summary/delta";

  /** Local buffer for summary deltas. */
  private final BufferedReducer<StatisticsEventSummaryKey, StatisticsEventSummaryDelta>
      summaryDeltaBuffer = new BufferedReducer<>();

  /**
   * Buffers a summary delta for deferred processing.
   *
   * @param key Summary key.
   * @param delta The delta.
   */
  public void bufferDelta(
      final StatisticsEventSummaryKey key, final StatisticsEventSummaryDelta delta) {
    this.summaryDeltaBuffer.reduce(key, delta);
  }

  /**
   * Flushes the summary delta buffer, sending each reduced delta to the internal JMS queue.
   */
  @PreDestroy
  @Scheduled(
      cron =
          "${org.coldis.library.service.statistics.summary.buffer.cron:0 * * * * *}")
  public void flushSummaryDeltaBuffer() {
    StatisticsEventSummaryServiceComponent.LOGGER.debug("Flushing summary delta buffer.");
    this.summaryDeltaBuffer.flushLocalBuffer(
        delta -> {
            StatisticsEventSummaryServiceComponent.LOGGER.debug(
                "Sending summary delta to JMS: context={}, dimension={}, dateTime={}",
                delta.getContext(), delta.getDimensionName(), delta.getDateTime());
            this.jmsTemplate.convertAndSend(
                StatisticsEventSummaryServiceComponent.SUMMARY_DELTA_QUEUE, delta);
        });
  }

  /**
   * Processes a summary delta from the internal JMS queue.
   *
   * @param delta The summary delta.
   */

  @Transactional(propagation = Propagation.REQUIRED)
  @JmsListener(
      destination = StatisticsEventSummaryServiceComponent.SUMMARY_DELTA_QUEUE,
      concurrency =
          "${org.coldis.library.service.statistics.summary.processsummarydelta.concurrency:1-10}",
      containerFactory =
          "${org.coldis.library.service.statistics.container-factory:jmsListenerContainerFactory}")

  public void processSummaryDelta(final StatisticsEventSummaryDelta delta) {
    StatisticsEventSummaryServiceComponent.LOGGER.debug(
        "Processing summary delta from JMS: context={}, dimension={}, dateTime={}",
        delta.getContext(), delta.getDimensionName(), delta.getDateTime());
    this.applyDelta(delta);
  }

  // ---- Validation helpers ----

  /**
   * Converts a (size, unit) pair to an approximate number of days.
   *
   * @param size Number of units.
   * @param unit Chrono unit.
   * @return Approximate number of days.
   */
  private static long toDays(final long size, final ChronoUnit unit) {
    return switch (unit) {
      case HOURS -> Math.max(size / 24, 1);
      case DAYS -> size;
      case WEEKS -> size * 7;
      case MONTHS -> size * 31;
      default -> size;
    };
  }

  /**
   * Validates that the total window (windowSize × windowUnit + steps × stepUnit) does not exceed
   * the maximum allowed span.
   *
   * @param windowUnit Unit defining the window size.
   * @param windowSize Number of window units per window.
   * @param stepUnit Unit defining how far back each sample is.
   * @param steps Number of periods to sample.
   * @throws BusinessException If the total window exceeds the maximum.
   */
  private static void validateTotalWindow(
      final ChronoUnit windowUnit,
      final Integer windowSize,
      final ChronoUnit stepUnit,
      final Integer steps)
      throws BusinessException {
    final long totalDays = toDays(windowSize, windowUnit) + toDays(steps, stepUnit);
    if (totalDays > MAX_TOTAL_WINDOW.toDays()) {
      throw new BusinessException(
          new SimpleMessage("statistics.event.summary.window.too.large"),
          HttpStatus.BAD_REQUEST.value());
    }
  }

  // ---- Math helpers ----

  /**
   * Computes the average of an array of BigDecimal values.
   *
   * @param values Values.
   * @return The average.
   */
  private static BigDecimal computeAverage(final BigDecimal[] values) {
    if (values.length == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (final BigDecimal value : values) {
      sum = sum.add(value, MATH_CONTEXT);
    }
    return sum.divide(BigDecimal.valueOf(values.length), MATH_CONTEXT);
  }

  /**
   * Computes the population standard deviation given pre-computed mean.
   *
   * @param values Values.
   * @param mean Pre-computed mean.
   * @return The standard deviation.
   */
  private static BigDecimal computeStdDev(final BigDecimal[] values, final BigDecimal mean) {
    if (values.length == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal sumSquaredDiff = BigDecimal.ZERO;
    for (final BigDecimal value : values) {
      final BigDecimal diff = value.subtract(mean, MATH_CONTEXT);
      sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff, MATH_CONTEXT), MATH_CONTEXT);
    }
    final BigDecimal variance =
        sumSquaredDiff.divide(BigDecimal.valueOf(values.length), MATH_CONTEXT);
    return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).round(MATH_CONTEXT);
  }

  /**
   * Computes the z-score (number of standard deviations from the mean). Returns null if stdDev is
   * zero.
   *
   * @param observed Observed value.
   * @param mean Mean.
   * @param stdDev Standard deviation.
   * @return The z-score, or null if stdDev is zero.
   */
  private static BigDecimal computeZScore(
      final BigDecimal observed, final BigDecimal mean, final BigDecimal stdDev) {
    return stdDev.compareTo(BigDecimal.ZERO) > 0
        ? observed.subtract(mean, MATH_CONTEXT).divide(stdDev, MATH_CONTEXT)
        : null;
  }

  // ---- Find / create / update ----

  /**
   * Finds a statistics event summary by its composite key.
   *
   * @param id Composite key.
   * @param forUpdate If a pessimistic write lock is required.
   * @return The statistics event summary.
   * @throws BusinessException If the summary cannot be found.
   */

  public StatisticsEventSummary findById(
      final StatisticsEventSummaryKey id, final Boolean forUpdate) throws BusinessException {
    final LocalDateTime truncatedDateTime =
        this.statisticsContextConfigurationServiceComponent.truncateDateTime(
            id.getContext(), id.getDateTime());
    final StatisticsEventSummary summary =
        Boolean.TRUE.equals(forUpdate)
            ? this.statisticsEventSummaryRepository
                .findByIdForUpdate(id.getContext(), id.getDimensionName(), truncatedDateTime)
                .orElse(null)
            : this.statisticsEventSummaryRepository
                .findById(
                    new StatisticsEventSummaryKey(
                        id.getContext(), id.getDimensionName(), truncatedDateTime))
                .orElse(null);
    if (summary == null) {
      throw new BusinessException(
          new SimpleMessage("statistics.event.summary.notfound"), HttpStatus.NOT_FOUND.value());
    }
    return summary;
  }

  /**
   * Creates a statistics event summary.
   *
   * @param summary Statistics event summary.
   * @return The created statistics event summary.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected StatisticsEventSummary create(final StatisticsEventSummary summary) {
    return this.statisticsEventSummaryRepository.saveAndFlush(summary);
  }

  /**
   * Finds or creates a statistics event summary. Uses pessimistic locking to prevent duplicates.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param dateTime Date time (already truncated).
   * @return Found or created statistics event summary.
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      noRollbackFor = DataIntegrityViolationException.class)
  public StatisticsEventSummary findOrCreate(
      final String context, final String dimensionName, final LocalDateTime dateTime) {
    // Tries to find the summary.
    StatisticsEventSummary actual =
        this.statisticsEventSummaryRepository
            .findByIdForUpdate(context, dimensionName, dateTime)
            .orElse(null);
    // If there is no summary.
    if (actual == null) {
      // Tries creating the summary.
      try {
        actual = this.create(new StatisticsEventSummary(context, dimensionName, dateTime));
        StatisticsEventSummaryServiceComponent.LOGGER.debug(
            "Created summary successfully: context={}, dimension={}, dateTime={}, id={}",
            context, dimensionName, dateTime, actual != null ? actual.getDateTime() : "null");
      } catch (final Exception exception) {
        StatisticsEventSummaryServiceComponent.LOGGER.warn(
            "Could not create statistics event summary: {}", exception.getLocalizedMessage());
        StatisticsEventSummaryServiceComponent.LOGGER.debug(
            "Could not create statistics event summary.", exception);
      }
      // Tries to find the summary again.
      actual =
          this.statisticsEventSummaryRepository
              .findByIdForUpdate(context, dimensionName, dateTime)
              .orElse(null);
      StatisticsEventSummaryServiceComponent.LOGGER.debug(
          "Retry findByIdForUpdate: context={}, dimension={}, dateTime={}, found={}",
          context, dimensionName, dateTime, actual != null);
    }
    // If the summary was not created.
    if (actual == null) {
      throw new IntegrationException(new SimpleMessage("statistics.event.summary.creation.error"));
    }
    return actual;
  }



  /**
   * Applies a buffered delta to a summary. Finds or creates the summary with pessimistic locking,
   * then applies all count and weight changes atomically.
   *
   * @param delta The delta to apply.
   */

  @Transactional(propagation = Propagation.REQUIRED)
  public void applyDelta(final StatisticsEventSummaryDelta delta) {
    final StatisticsEventSummary summary =
        this.findOrCreate(delta.getContext(), delta.getDimensionName(), delta.getDateTime());
    // Apply count deltas.
    final Map<String, Long> counts = summary.getValueCounts();
    for (final Map.Entry<String, Long> entry : delta.getCountDeltas().entrySet()) {
      final long current = counts.getOrDefault(entry.getKey(), 0L);
      final long newValue = current + entry.getValue();
      if (newValue <= 0) {
        counts.remove(entry.getKey());
      } else {
        counts.put(entry.getKey(), newValue);
      }
    }
    // Apply weight deltas.
    final Map<String, BigDecimal> weights = summary.getValueWeights();
    for (final Map.Entry<String, BigDecimal> entry : delta.getWeightDeltas().entrySet()) {
      final BigDecimal current = weights.getOrDefault(entry.getKey(), BigDecimal.ZERO);
      final BigDecimal newValue = current.add(entry.getValue());
      if (newValue.compareTo(BigDecimal.ZERO) <= 0) {
        weights.remove(entry.getKey());
      } else {
        weights.put(entry.getKey(), newValue);
      }
    }
    // Recompute totals from maps to prevent drift.
    summary.setTotalCount(counts.values().stream().mapToLong(Long::longValue).sum());
    summary.setTotalWeight(
        weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    this.statisticsEventSummaryRepository.save(summary);
  }

  // ---- Period queries ----

  /**
   * Finds all summaries for a context and dimension within a date range and merges them into a
   * single aggregated summary.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param startDateTime Start date time.
   * @param endDateTime End date time.
   * @return The aggregated statistics event summary.
   * @throws BusinessException If no summaries are found in the period.
   */

  public StatisticsEventSummary findByPeriod(
      final String context,
      final String dimensionName,
      final LocalDateTime startDateTime,
      final LocalDateTime endDateTime)
      throws BusinessException {
    final List<StatisticsEventSummary> summaries =
        this.statisticsEventSummaryRepository.findByPeriod(
            context,
            dimensionName,
            this.statisticsContextConfigurationServiceComponent.truncateDateTime(
                context, startDateTime),
            this.statisticsContextConfigurationServiceComponent.truncateDateTime(
                context, endDateTime));
    if (summaries == null || summaries.isEmpty()) {
      throw new BusinessException(
          new SimpleMessage("statistics.event.summary.notfound"), HttpStatus.NOT_FOUND.value());
    }
    final StatisticsEventSummary merged =
        new StatisticsEventSummary(
            context,
            dimensionName,
            this.statisticsContextConfigurationServiceComponent.truncateDateTime(
                context, startDateTime));
    for (final StatisticsEventSummary summary : summaries) {
      merged.setTotalCount(merged.getTotalCount() + summary.getTotalCount());
      summary
          .getValueCounts()
          .forEach((key, value) -> merged.getValueCounts().merge(key, value, Long::sum));
      merged.setTotalWeight(merged.getTotalWeight().add(summary.getTotalWeight()));
      summary
          .getValueWeights()
          .forEach((key, value) -> merged.getValueWeights().merge(key, value, BigDecimal::add));
    }
    return merged;
  }

  // ---- Historical aggregation ----

  /** Internal holder for aggregated period data used by comparison and probability methods. */
  private static class PeriodAggregation {
    final List<Long> totalCounts = new ArrayList<>();
    final List<Map<String, Long>> allValueCounts = new ArrayList<>();
    final Set<String> allKeys = new HashSet<>();
    BigDecimal avgTotal;
    BigDecimal stdDevTotal;
    final Map<String, BigDecimal> avgValueCounts = new HashMap<>();
    final Map<String, BigDecimal> stdDevValueCounts = new HashMap<>();
    final Map<String, BigDecimal> avgValueRatios = new HashMap<>();
    final Map<String, BigDecimal> stdDevValueRatios = new HashMap<>();
  }

  /**
   * Aggregates summaries for a series of historical windows and computes avg/stdDev for total
   * counts, value counts, and value ratios.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param windowStarts List of window start times.
   * @param windowEnds List of window end times (matching windowStarts by index).
   * @return The aggregation, or null if no periods had data.
   */
  private PeriodAggregation aggregatePeriods(
      final String context,
      final String dimensionName,
      final List<LocalDateTime> windowStarts,
      final List<LocalDateTime> windowEnds) {
    final PeriodAggregation agg = new PeriodAggregation();
    for (int windowIndex = 0; windowIndex < windowStarts.size(); windowIndex++) {
      final List<StatisticsEventSummary> summaries =
          this.statisticsEventSummaryRepository.findByPeriod(
              context, dimensionName, windowStarts.get(windowIndex), windowEnds.get(windowIndex));
      if (summaries != null && !summaries.isEmpty()) {
        long total = 0L;
        final Map<String, Long> merged = new HashMap<>();
        for (final StatisticsEventSummary summary : summaries) {
          total += summary.getTotalCount();
          summary.getValueCounts().forEach((key, value) -> merged.merge(key, value, Long::sum));
        }
        agg.totalCounts.add(total);
        agg.allValueCounts.add(merged);
        agg.allKeys.addAll(merged.keySet());
      }
    }
    if (agg.totalCounts.isEmpty()) {
      return null;
    }
    final int size = agg.totalCounts.size();
    // Total count stats.
    final BigDecimal[] totalValues = new BigDecimal[size];
    for (int sampleIndex = 0; sampleIndex < size; sampleIndex++) {
      totalValues[sampleIndex] = BigDecimal.valueOf(agg.totalCounts.get(sampleIndex));
    }
    agg.avgTotal = computeAverage(totalValues);
    agg.stdDevTotal = computeStdDev(totalValues, agg.avgTotal);
    // Per-value count and ratio stats.
    for (final String key : agg.allKeys) {
      final BigDecimal[] values = new BigDecimal[size];
      final BigDecimal[] ratios = new BigDecimal[size];
      for (int sampleIndex = 0; sampleIndex < size; sampleIndex++) {
        values[sampleIndex] =
            BigDecimal.valueOf(agg.allValueCounts.get(sampleIndex).getOrDefault(key, 0L));
        final long total = agg.totalCounts.get(sampleIndex);
        ratios[sampleIndex] =
            total > 0
                ? values[sampleIndex].divide(BigDecimal.valueOf(total), MATH_CONTEXT)
                : BigDecimal.ZERO;
      }
      final BigDecimal avgCount = computeAverage(values);
      agg.avgValueCounts.put(key, avgCount);
      agg.stdDevValueCounts.put(key, computeStdDev(values, avgCount));
      final BigDecimal avgRatio = computeAverage(ratios);
      agg.avgValueRatios.put(key, avgRatio);
      agg.stdDevValueRatios.put(key, computeStdDev(ratios, avgRatio));
    }
    return agg;
  }

  /**
   * Computes window start/end pairs for historical periods stepping back from a reference point.
   * The reference window is NOT included.
   */
  private void computeHistoricalWindows(
      final LocalDateTime refStart,
      final LocalDateTime refEnd,
      final ChronoUnit stepUnit,
      final int steps,
      final long truncationMinutes,
      final List<LocalDateTime> starts,
      final List<LocalDateTime> ends) {
    for (int stepIndex = 1; stepIndex <= steps; stepIndex++) {
      starts.add(
          StatisticsEvent.truncateDateTime(refStart.minus(stepIndex, stepUnit), truncationMinutes));
      ends.add(
          StatisticsEvent.truncateDateTime(refEnd.minus(stepIndex, stepUnit), truncationMinutes));
    }
  }

  /**
   * Compares a reference window against historical periods, providing averages, standard
   * deviations, value ratios, and z-scores.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param referenceDateTime Start of the reference window.
   * @param windowUnit Unit defining the window size.
   * @param windowSize Number of window units per window.
   * @param stepUnit Unit defining how far back each sample is.
   * @param steps Number of past periods to sample (excluding reference).
   * @return The comparison with averages, std devs, z-scores, and reference values.
   * @throws BusinessException If no data is found in any of the sampled periods.
   */

  public StatisticsEventSummaryComparison compareByPeriod(
      final String context,
      final String dimensionName,
      final LocalDateTime referenceDateTime,
      final ChronoUnit windowUnit,
      final Integer windowSize,
      final ChronoUnit stepUnit,
      final Integer steps)
      throws BusinessException {
    validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
    final long truncationMinutes =
        this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
    final LocalDateTime refStart =
        StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
    final LocalDateTime refEnd =
        StatisticsEvent.truncateDateTime(
            referenceDateTime.plus(windowSize, windowUnit), truncationMinutes);

    // Aggregates historical periods (excluding reference).
    final List<LocalDateTime> starts = new ArrayList<>();
    final List<LocalDateTime> ends = new ArrayList<>();
    this.computeHistoricalWindows(
        refStart, refEnd, stepUnit, steps, truncationMinutes, starts, ends);
    final PeriodAggregation agg = this.aggregatePeriods(context, dimensionName, starts, ends);
    if (agg == null) {
      throw new BusinessException(
          new SimpleMessage("statistics.event.summary.comparison.nodata"),
          HttpStatus.NOT_FOUND.value());
    }

    // Queries reference window.
    Long referenceTotalCount = null;
    Map<String, Long> referenceValueCounts = null;
    final List<StatisticsEventSummary> refSummaries =
        this.statisticsEventSummaryRepository.findByPeriod(
            context, dimensionName, refStart, refEnd);
    if (refSummaries != null && !refSummaries.isEmpty()) {
      referenceTotalCount = 0L;
      referenceValueCounts = new HashMap<>();
      for (final StatisticsEventSummary summary : refSummaries) {
        referenceTotalCount += summary.getTotalCount();
        final Map<String, Long> refCounts = referenceValueCounts;
        summary.getValueCounts().forEach((key, value) -> refCounts.merge(key, value, Long::sum));
      }
    }

    // Builds the result.
    final StatisticsEventSummaryComparison comparison = new StatisticsEventSummaryComparison();
    comparison.setContext(context);
    comparison.setDimensionName(dimensionName);
    comparison.setReferenceDateTime(refStart);
    comparison.setWindowUnit(windowUnit);
    comparison.setWindowSize(windowSize);
    comparison.setStepUnit(stepUnit);
    comparison.setSteps(steps);
    comparison.setSampleSize(agg.totalCounts.size());
    comparison.setAverageTotalCount(agg.avgTotal);
    comparison.setStdDevTotalCount(agg.stdDevTotal);
    comparison.setAverageValueCounts(agg.avgValueCounts);
    comparison.setStdDevValueCounts(agg.stdDevValueCounts);
    comparison.setAverageValueRatios(agg.avgValueRatios);
    comparison.setStdDevValueRatios(agg.stdDevValueRatios);
    comparison.setReferenceTotalCount(referenceTotalCount);
    comparison.setReferenceValueCounts(referenceValueCounts);
    // Computes reference value ratios and z-scores.
    if (referenceTotalCount != null && referenceTotalCount > 0 && referenceValueCounts != null) {
      final Map<String, BigDecimal> refRatios = new HashMap<>();
      final BigDecimal refTotal = BigDecimal.valueOf(referenceTotalCount);
      referenceValueCounts.forEach(
          (key, value) ->
              refRatios.put(key, BigDecimal.valueOf(value).divide(refTotal, MATH_CONTEXT)));
      comparison.setReferenceValueRatios(refRatios);
    }
    if (referenceTotalCount != null) {
      comparison.setZScoreTotalCount(
          computeZScore(BigDecimal.valueOf(referenceTotalCount), agg.avgTotal, agg.stdDevTotal));
      if (referenceValueCounts != null) {
        final Map<String, BigDecimal> zScoreCounts = new HashMap<>();
        for (final String key : agg.allKeys) {
          final BigDecimal refCount =
              BigDecimal.valueOf(referenceValueCounts.getOrDefault(key, 0L));
          zScoreCounts.put(
              key,
              computeZScore(
                  refCount,
                  agg.avgValueCounts.getOrDefault(key, BigDecimal.ZERO),
                  agg.stdDevValueCounts.getOrDefault(key, BigDecimal.ZERO)));
        }
        comparison.setZScoreValueCounts(zScoreCounts);
      }
      if (comparison.getReferenceValueRatios() != null
          && !comparison.getReferenceValueRatios().isEmpty()) {
        final Map<String, BigDecimal> zScoreRatios = new HashMap<>();
        for (final String key : agg.allKeys) {
          final BigDecimal refRatio =
              comparison.getReferenceValueRatios().getOrDefault(key, BigDecimal.ZERO);
          zScoreRatios.put(
              key,
              computeZScore(
                  refRatio,
                  agg.avgValueRatios.getOrDefault(key, BigDecimal.ZERO),
                  agg.stdDevValueRatios.getOrDefault(key, BigDecimal.ZERO)));
        }
        comparison.setZScoreValueRatios(zScoreRatios);
      }
    }
    return comparison;
  }

  /**
   * Computes the probability of a single dimension value based on historical distribution. The
   * reference period is included in the computation (unlike comparison, which excludes it).
   *
   * @param context Context.
   * @param dimension Dimension (name and value to evaluate).
   * @param referenceDateTime Reference date time (included in the sample).
   * @param windowUnit Unit defining the window size.
   * @param windowSize Number of window units per window.
   * @param stepUnit Unit defining how far back each sample is.
   * @param steps Number of periods to sample (including reference).
   * @return The single-dimension probability analysis for the dimension value.
   * @throws BusinessException If no data is found in any of the sampled periods.
   */

  public StatisticsEventSingleDimensionProbability singleDimensionProbabilityByPeriod(
      final String context,
      final StatisticsValuedEventDimension dimension,
      final LocalDateTime referenceDateTime,
      final ChronoUnit windowUnit,
      final Integer windowSize,
      final ChronoUnit stepUnit,
      final Integer steps)
      throws BusinessException {
    validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
    final long truncationMinutes =
        this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
    final String dimensionName = dimension.getDimensionName();
    final String dimensionValue = dimension.getDimensionValue();
    final LocalDateTime refStart =
        StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
    final LocalDateTime refEnd =
        StatisticsEvent.truncateDateTime(
            referenceDateTime.plus(windowSize, windowUnit), truncationMinutes);

    // Builds windows including the reference and stepping back.
    final List<LocalDateTime> starts = new ArrayList<>();
    final List<LocalDateTime> ends = new ArrayList<>();
    for (int stepIndex = 0; stepIndex < steps; stepIndex++) {
      starts.add(
          StatisticsEvent.truncateDateTime(refStart.minus(stepIndex, stepUnit), truncationMinutes));
      ends.add(
          StatisticsEvent.truncateDateTime(refEnd.minus(stepIndex, stepUnit), truncationMinutes));
    }

    final PeriodAggregation agg = this.aggregatePeriods(context, dimensionName, starts, ends);
    if (agg == null) {
      throw new BusinessException(
          new SimpleMessage("statistics.event.probability.nodata"), HttpStatus.NOT_FOUND.value());
    }

    final StatisticsEventSingleDimensionProbability probability =
        new StatisticsEventSingleDimensionProbability();
    probability.setContext(context);
    probability.setDimensionName(dimensionName);
    probability.setDimensionValue(dimensionValue);
    probability.setReferenceDateTime(refStart);
    probability.setWindowUnit(windowUnit);
    probability.setWindowSize(windowSize);
    probability.setStepUnit(stepUnit);
    probability.setSteps(steps);
    probability.setSampleSize(agg.totalCounts.size());
    probability.setProbability(agg.avgValueRatios.getOrDefault(dimensionValue, BigDecimal.ZERO));
    probability.setStdDevProbability(
        agg.stdDevValueRatios.getOrDefault(dimensionValue, BigDecimal.ZERO));
    probability.setAverageCount(agg.avgValueCounts.getOrDefault(dimensionValue, BigDecimal.ZERO));
    probability.setStdDevCount(agg.stdDevValueCounts.getOrDefault(dimensionValue, BigDecimal.ZERO));
    return probability;
  }

  /**
   * Computes the naive multi-dimension joint probability assuming independence: P(A ∩ B) = P(A) ×
   * P(B). For each dimension, calls {@link #singleDimensionProbabilityByPeriod} and multiplies
   * individual probabilities.
   *
   * @param context Context.
   * @param dimensions List of dimensions (name and value pairs).
   * @param referenceDateTime Reference date time (included in the sample).
   * @param windowUnit Unit defining the window size.
   * @param windowSize Number of window units per window.
   * @param stepUnit Unit defining how far back each sample is.
   * @param steps Number of periods to sample (including reference).
   * @return The naive multi-dimension probability with joint and individual probabilities.
   * @throws BusinessException If no data is found for any dimension.
   */

  public StatisticsEventNaiveMultiDimensionProbability naiveMultiDimensionProbabilityByPeriod(
      final String context,
      final List<StatisticsValuedEventDimension> dimensions,
      final LocalDateTime referenceDateTime,
      final ChronoUnit windowUnit,
      final Integer windowSize,
      final ChronoUnit stepUnit,
      final Integer steps)
      throws BusinessException {
    validateTotalWindow(windowUnit, windowSize, stepUnit, steps);

    // Computes individual probabilities.
    final List<StatisticsEventSingleDimensionProbability> individualProbabilities =
        new ArrayList<>();
    BigDecimal jointProbability = BigDecimal.ONE;
    for (final StatisticsValuedEventDimension dimension : dimensions) {
      final StatisticsEventSingleDimensionProbability individual =
          this.singleDimensionProbabilityByPeriod(
              context, dimension, referenceDateTime, windowUnit, windowSize, stepUnit, steps);
      individualProbabilities.add(individual);
      jointProbability = jointProbability.multiply(individual.getProbability(), MATH_CONTEXT);
    }

    // Builds result.
    final StatisticsEventNaiveMultiDimensionProbability result =
        new StatisticsEventNaiveMultiDimensionProbability();
    result.setContext(context);
    result.setReferenceDateTime(
        this.statisticsContextConfigurationServiceComponent.truncateDateTime(
            context, referenceDateTime));
    result.setWindowUnit(windowUnit);
    result.setWindowSize(windowSize);
    result.setStepUnit(stepUnit);
    result.setSteps(steps);
    result.setJointProbability(jointProbability);
    result.setIndividualProbabilities(individualProbabilities);
    return result;
  }
}
