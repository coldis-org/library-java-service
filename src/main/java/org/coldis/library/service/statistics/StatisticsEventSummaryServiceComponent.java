package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.BufferedReducer;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;

/**
 * Statistics event summary service component. Contains the business logic for
 * summary operations (find, create, increment, decrement, comparison,
 * probability).
 */
@Component
@ConditionalOnProperty(
		name = "org.coldis.configuration.service.statistics-enabled",
		matchIfMissing = false
)
public class StatisticsEventSummaryServiceComponent {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsEventSummaryServiceComponent.class);

	/** Statistics context configuration service component. */
	@Autowired
	private StatisticsContextConfigurationServiceComponent statisticsContextConfigurationServiceComponent;

	/** Statistics event summary repository. */
	@Autowired
	private StatisticsEventSummaryRepository statisticsEventSummaryRepository;

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** Internal queue for buffered summary delta batches. */
	private static final String SUMMARY_DELTA_BATCH_QUEUE = "statistics-event/summary/delta/batch";

	/** Maximum number of deltas sent in a single batch message. */
	@org.springframework.beans.factory.annotation.Value("${org.coldis.library.service.statistics.summary.buffer.batch-size:100}")
	private int deltaBatchSize;

	/** Local buffer for summary deltas. */
	private final BufferedReducer<StatisticsEventSummaryKey, StatisticsEventSummaryDelta> summaryDeltaBuffer = new BufferedReducer<>();

	/**
	 * Buffers a summary delta for deferred processing.
	 *
	 * @param key   Summary key.
	 * @param delta The delta.
	 */
	public void bufferDelta(
			final StatisticsEventSummaryKey key,
			final StatisticsEventSummaryDelta delta) {
		this.summaryDeltaBuffer.reduce(key, delta);
	}

	/**
	 * Drains the summary delta buffer and dispatches batches to the delta batch
	 * queue. Runs on a schedule and on shutdown.
	 */
	@PreDestroy
	@Scheduled(cron = "${org.coldis.library.service.statistics.summary.buffer.cron:0 */5 * * * *}")
	public void flushSummaryDeltaBuffer() {
		StatisticsEventSummaryServiceComponent.LOGGER.debug("Flushing summary delta buffer.");
		final List<StatisticsEventSummaryDelta> drained = new ArrayList<>();
		this.summaryDeltaBuffer.flushLocalBuffer(drained::add);
		if (!drained.isEmpty()) {
			final int batchSize = Math.max(1, this.deltaBatchSize);
			for (int from = 0; from < drained.size(); from += batchSize) {
				final int to = Math.min(from + batchSize, drained.size());
				final ArrayList<StatisticsEventSummaryDelta> chunk = new ArrayList<>(drained.subList(from, to));
				this.jmsTemplate.convertAndSend(StatisticsEventSummaryServiceComponent.SUMMARY_DELTA_BATCH_QUEUE, chunk);
			}
		}
	}

	/**
	 * Processes a buffered summary delta batch from the internal JMS queue. Each
	 * delta is applied within the same transaction; per-row locking is handled by
	 * {@link #applyDelta}.
	 *
	 * @param deltas The batch of deltas to apply.
	 */

	@Transactional(propagation = Propagation.REQUIRED)
	@JmsListener(
			destination = StatisticsEventSummaryServiceComponent.SUMMARY_DELTA_BATCH_QUEUE,
			concurrency = "${org.coldis.library.service.statistics.summary.processsummarydelta.concurrency:1}",
			containerFactory = "${org.coldis.library.service.statistics.summary.container-factory:jmsListenerContainerFactory}"
	)

	public void processSummaryDeltaBatch(
			final List<StatisticsEventSummaryDelta> deltas) {
		if ((deltas != null) && !deltas.isEmpty()) {
			for (final StatisticsEventSummaryDelta delta : deltas) {
				StatisticsEventSummaryServiceComponent.LOGGER.debug("Processing summary delta from JMS batch: context={}, dimension={}, dateTime={}",
						delta.getContext(), delta.getDimensionName(), delta.getDateTime());
				this.applyDelta(delta);
			}
		}
	}

	// ---- Find / create / update ----

	/**
	 * Finds a statistics event summary by its composite key.
	 *
	 * @param  id                Composite key.
	 * @param  forUpdate         If a pessimistic write lock is required.
	 * @return                   The statistics event summary.
	 * @throws BusinessException If the summary cannot be found.
	 */

	public StatisticsEventSummary findById(
			final StatisticsEventSummaryKey id,
			final Boolean forUpdate) throws BusinessException {
		final LocalDateTime truncatedDateTime = this.statisticsContextConfigurationServiceComponent.truncateDateTime(id.getContext(), id.getDateTime());
		final StatisticsEventSummary summary = Boolean.TRUE.equals(forUpdate)
				? this.statisticsEventSummaryRepository
						.findByIdForUpdateWait(new StatisticsEventSummaryKey(id.getContext(), id.getDimensionName(), truncatedDateTime), Duration.ofSeconds(11))
						.orElse(null)
				: this.statisticsEventSummaryRepository.findById(new StatisticsEventSummaryKey(id.getContext(), id.getDimensionName(), truncatedDateTime))
						.orElse(null);
		if (summary == null) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.notfound"), HttpStatus.NOT_FOUND.value());
		}
		return summary;
	}

	/**
	 * Finds or creates a statistics event summary. Hot path: a single
	 * {@code FOR UPDATE} read returns when the row already exists (the common
	 * case). Only on first-write does it fall back to
	 * {@code INSERT ... ON CONFLICT DO NOTHING} followed by a second read — atomic
	 * against concurrent inserters, no nested transactions, no exception bouncing.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  dateTime      Date time (already truncated).
	 * @return               Found or created statistics event summary, locked for
	 *                       update.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public StatisticsEventSummary findOrCreate(
			final String context,
			final String dimensionName,
			final LocalDateTime dateTime) {
		return this.statisticsEventSummaryRepository.findByIdForUpdateOrCreate(
				new StatisticsEventSummaryKey(context, dimensionName, dateTime),
				() -> this.statisticsEventSummaryRepository.insertIfAbsent(context, dimensionName, dateTime));
	}

	/**
	 * Applies a buffered delta to a summary. Finds or creates the summary with
	 * pessimistic locking, then applies all count and weight changes atomically.
	 *
	 * @param delta The delta to apply.
	 */

	@Transactional(propagation = Propagation.REQUIRED)
	public void applyDelta(
			final StatisticsEventSummaryDelta delta) {
		final StatisticsEventSummary summary = this.findOrCreate(delta.getContext(), delta.getDimensionName(), delta.getDateTime());
		// Apply count deltas.
		final Map<String, Long> counts = summary.getValueCounts();
		for (final Map.Entry<String, Long> entry : delta.getCountDeltas().entrySet()) {
			final long current = counts.getOrDefault(entry.getKey(), 0L);
			final long newValue = current + entry.getValue();
			if (newValue <= 0) {
				counts.remove(entry.getKey());
			}
			else {
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
			}
			else {
				weights.put(entry.getKey(), newValue);
			}
		}
		// Recompute totals from maps to prevent drift.
		summary.setTotalCount(counts.values().stream().mapToLong(Long::longValue).sum());
		summary.setTotalWeight(weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
		this.statisticsEventSummaryRepository.save(summary);
	}

	// ---- Period queries ----

	/**
	 * Fetches the raw list of summaries for a context and dimension within a date
	 * range. Both date-time parameters must already be truncated to the configured
	 * interval. Result is cached.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  startDateTime Start date time (truncated).
	 * @param  endDateTime   End date time (truncated).
	 * @return               The list of summaries in the period.
	 */
	@Cacheable(
			cacheManager = "secondsExpirationLocalCacheManager",
			value = "StatisticsEventSummaryServiceComponent.findSummariesByPeriod"
	)
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	public List<StatisticsEventSummary> findSummariesByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) {
		return this.statisticsEventSummaryRepository.findByPeriod(context, dimensionName, startDateTime, endDateTime);
	}

	/**
	 * Finds all summaries for a context and dimension within a date range and
	 * merges them into a single aggregated summary.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  startDateTime     Start date time.
	 * @param  endDateTime       End date time.
	 * @return                   The aggregated statistics event summary.
	 * @throws BusinessException If no summaries are found in the period.
	 */
	@Cacheable(
			cacheManager = "secondsExpirationLocalCacheManager",
			value = "StatisticsEventSummaryServiceComponent.findByPeriod"
	)
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	public StatisticsEventSummary findByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) throws BusinessException {
		final List<StatisticsEventSummary> summaries = this.findSummariesByPeriod(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime),
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, endDateTime));
		if ((summaries == null) || summaries.isEmpty()) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.notfound"), HttpStatus.NOT_FOUND.value());
		}
		final StatisticsEventSummary merged = new StatisticsEventSummary(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime));
		for (final StatisticsEventSummary summary : summaries) {
			merged.setTotalCount(merged.getTotalCount() + summary.getTotalCount());
			summary.getValueCounts().forEach((
					key,
					value) -> merged.getValueCounts().merge(key, value, Long::sum));
			merged.setTotalWeight(merged.getTotalWeight().add(summary.getTotalWeight()));
			summary.getValueWeights().forEach((
					key,
					value) -> merged.getValueWeights().merge(key, value, BigDecimal::add));
		}
		return merged;
	}

	/**
	 * Compares a reference window against historical periods, providing averages,
	 * standard deviations, value ratios, and z-scores.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  referenceDateTime Start of the reference window.
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of past periods to sample (excluding
	 *                               reference).
	 * @return                   The comparison with averages, std devs, z-scores,
	 *                           and reference values.
	 * @throws BusinessException If no data is found in any of the sampled periods.
	 */

	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = "StatisticsEventSummaryServiceComponent.compareByPeriod"
	)
	private StatisticsEventSummaryComparison compareByPeriodCached(
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final long truncationMinutes = this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
		final LocalDateTime referenceStart = referenceDateTime; // already truncated by caller
		final LocalDateTime referenceEnd = StatisticsEvent.truncateDateTime(referenceDateTime.plus(windowSize, windowUnit), truncationMinutes);
		final List<LocalDateTime> starts = new ArrayList<>();
		final List<LocalDateTime> ends = new ArrayList<>();
		StatisticsEventSummaryHelper.historicalWindows(referenceStart, referenceEnd, stepUnit, steps, truncationMinutes, starts, ends);
		final List<List<StatisticsEventSummary>> perWindowSummaries = new ArrayList<>();
		for (int windowIndex = 0; windowIndex < starts.size(); windowIndex++) {
			perWindowSummaries.add(this.findSummariesByPeriod(context, dimensionName, starts.get(windowIndex), ends.get(windowIndex)));
		}
		final List<StatisticsEventSummary> referenceSummaries = this.findSummariesByPeriod(context, dimensionName, referenceStart, referenceEnd);
		return StatisticsEventSummaryHelper.computeComparison(referenceSummaries, perWindowSummaries, context, dimensionName, referenceStart, windowUnit,
				windowSize, stepUnit, steps);
	}

	/**
	 * Truncates {@code referenceDateTime} to the context's configured truncation
	 * interval before delegating to the cached implementation. This ensures the
	 * cache key is always the effective (post-truncation) datetime so callers that
	 * pass slightly different raw values within the same truncation bucket share
	 * the same cache entry.
	 */
	public StatisticsEventSummaryComparison compareByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		StatisticsEventSummaryHelper.validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
		final long truncationMinutes = this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
		final LocalDateTime truncatedReferenceDateTime = StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
		return this.compareByPeriodCached(context, dimensionName, truncatedReferenceDateTime, windowUnit, windowSize, stepUnit, steps);
	}

	/**
	 * Builds the value-independent per-dimension distribution over the sampled period (reference
	 * included). Cached on the value-independent key {@code (context, dimension, reference, window)}
	 * so every applicant value evaluated against the same population shares one aggregation — the
	 * heavy fetch-and-aggregate is paid once per period, not per value.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  referenceDateTime Reference date time (already truncated by caller; included in sample).
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of periods to sample (including reference).
	 * @return                   The dimension distribution.
	 * @throws BusinessException If no data is found in any of the sampled periods.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = "StatisticsEventSummaryServiceComponent.singleDimensionDistributionByPeriod"
	)
	private StatisticsEventDimensionDistribution singleDimensionDistributionByPeriodCached(
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final long truncationMinutes = this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
		final LocalDateTime referenceStart = referenceDateTime; // already truncated by caller
		final LocalDateTime referenceEnd = StatisticsEvent.truncateDateTime(referenceDateTime.plus(windowSize, windowUnit), truncationMinutes);
		final List<LocalDateTime> starts = new ArrayList<>();
		final List<LocalDateTime> ends = new ArrayList<>();
		StatisticsEventSummaryHelper.referenceInclusiveWindows(referenceStart, referenceEnd, stepUnit, steps, truncationMinutes, starts, ends);
		final List<List<StatisticsEventSummary>> perWindowSummaries = new ArrayList<>();
		for (int windowIndex = 0; windowIndex < starts.size(); windowIndex++) {
			perWindowSummaries.add(this.findSummariesByPeriod(context, dimensionName, starts.get(windowIndex), ends.get(windowIndex)));
		}
		return StatisticsEventSummaryHelper.computeDistribution(perWindowSummaries, context, dimensionName, referenceStart, windowUnit, windowSize, stepUnit,
				steps);
	}

	/**
	 * Truncates {@code referenceDateTime} before delegating to the cached implementation so the cache
	 * key is always the effective (post-truncation) datetime.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  referenceDateTime Reference date time (included in the sample).
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of periods to sample (including reference).
	 * @return                   The dimension distribution.
	 * @throws BusinessException If no data is found in any of the sampled periods.
	 */
	public StatisticsEventDimensionDistribution singleDimensionDistributionByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		StatisticsEventSummaryHelper.validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
		final long truncationMinutes = this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
		final LocalDateTime truncatedReferenceDateTime = StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
		return this.singleDimensionDistributionByPeriodCached(context, dimensionName, truncatedReferenceDateTime, windowUnit, windowSize, stepUnit, steps);
	}

	/**
	 * Computes the probability of a single dimension value based on historical distribution (the
	 * reference period is included, unlike comparison). A cheap lookup-plus-Laplace derivation on top
	 * of the cached {@link #singleDimensionDistributionByPeriod}.
	 *
	 * @param  context           Context.
	 * @param  dimension         Dimension (name and value to evaluate).
	 * @param  referenceDateTime Reference date time (included in the sample).
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of periods to sample (including reference).
	 * @return                   The single-dimension probability for the dimension value.
	 * @throws BusinessException If no data is found in any of the sampled periods.
	 */
	public StatisticsEventSingleDimensionProbability singleDimensionProbabilityByPeriod(
			final String context,
			final StatisticsValuedEventDimension dimension,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final StatisticsEventDimensionDistribution distribution = this.singleDimensionDistributionByPeriod(context, dimension.getDimensionName(),
				referenceDateTime, windowUnit, windowSize, stepUnit, steps);
		return StatisticsEventSummaryHelper.singleDimensionProbability(distribution, dimension.getDimensionValue(),
				StatisticsEventSummaryHelper.DEFAULT_SMOOTHING_FACTOR);
	}

	/**
	 * Computes the naive multi-dimension joint probability assuming independence:
	 * P(A ∩ B) = P(A) × P(B). For each dimension, calls
	 * {@link #singleDimensionProbabilityByPeriod} and multiplies individual
	 * probabilities.
	 *
	 * @param  context           Context.
	 * @param  dimensions        List of dimensions (name and value pairs).
	 * @param  referenceDateTime Reference date time (included in the sample).
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of periods to sample (including reference).
	 * @return                   The naive multi-dimension probability with joint
	 *                           and individual probabilities.
	 * @throws BusinessException If no data is found for any dimension.
	 */
	public StatisticsEventNaiveMultiDimensionProbability naiveMultiDimensionProbabilityByPeriod(
			final String context,
			final List<StatisticsValuedEventDimension> dimensions,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		StatisticsEventSummaryHelper.validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
		final List<StatisticsEventSingleDimensionProbability> individualProbabilities = new ArrayList<>();
		for (final StatisticsValuedEventDimension dimension : dimensions) {
			individualProbabilities.add(this.singleDimensionProbabilityByPeriod(context, dimension, referenceDateTime, windowUnit, windowSize, stepUnit, steps));
		}
		return StatisticsEventSummaryHelper.naiveMultiDimensionProbability(individualProbabilities, context,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, referenceDateTime), windowUnit, windowSize, stepUnit, steps);
	}

	// Cross-dimension z-score aggregators over a list of comparison results — pure reductions
	// delegated to StatisticsEventSummaryHelper. Kept on the component so existing callers (which
	// inject this bean) need no change.

	/** Maximum {@code |z|} across every dimension's value-level ratio z-scores. */
	public BigDecimal maxAbsRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.maxAbsRatioZScore(comparisons);
	}

	/** Minimum {@code |z|} across every dimension's value-level ratio z-scores. */
	public BigDecimal minAbsRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.minAbsRatioZScore(comparisons);
	}

	/** Mean {@code |z|} across every dimension's value-level ratio z-scores. */
	public BigDecimal meanAbsRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanAbsRatioZScore(comparisons);
	}

	/** Count of dimension-value z-scores whose {@code |z|} strictly exceeds {@code threshold}. */
	public BigDecimal countAbsRatioZScoreAbove(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countAbsRatioZScoreAbove(comparisons, threshold);
	}

	/** Raw {@code sqrt(Σ z²)} — the z-score vector's Euclidean length ("total drift energy"). */
	public BigDecimal rootSumSquareRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.rootSumSquareRatioZScore(comparisons);
	}

	/** Standardized chi-square {@code (Σ z² − k) / sqrt(2k)} — mean 0 / variance 1 across populations. */
	public BigDecimal standardizedChiSquareRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.standardizedChiSquareRatioZScore(comparisons);
	}

	/** Raw Fisher combined surprise {@code Σ −log(2·(1−Φ(|z|)))} (Fisher's combined-probability method). */
	public BigDecimal fisherCombinedRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.fisherCombinedRatioZScore(comparisons);
	}

	/** Standardized Fisher {@code (S − k) / sqrt(k)} — mean 0 / variance 1 across populations. */
	public BigDecimal standardizedFisherRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.standardizedFisherRatioZScore(comparisons);
	}
}
