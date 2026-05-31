package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.statistics.StatisticsEventSummaryHelper.WindowSchedule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics event summary computation service component. Read/query side:
 * period aggregation, drift comparison, per-dimension distribution, probability,
 * and the cross-dimension z-score aggregators. The write path (buffering,
 * flushing, applying deltas, single-key lookup/creation) lives in
 * {@link StatisticsEventSummaryBufferServiceComponent}.
 *
 * <p>Each public entry point validates its inputs and resolves all date-time math
 * up front — it truncates the reference and builds the fully-truncated
 * {@link WindowSchedule} — then delegates to a {@code protected ...Cacheable}
 * sibling. The {@code ...Cacheable} methods do no truncation at all: they receive
 * the ready-made schedule and only fetch, bucket and compute. That keeps them
 * usable as caching seams — an extended bean can {@code @Override} one and add
 * {@code @Cacheable} on a key built from the (already-truncated) schedule. The base
 * bean does not cache.
 *
 * <p>The heavy DB work is funneled through {@link #findSummariesByPeriodCacheable},
 * one query <em>per dimension</em> covering the whole schedule's date range
 * (instead of one query per sample window). The multi-dimension entry points
 * (comparison over a set of dimensions, naive multi-dimension probability) issue
 * one such query per dimension rather than a single wide {@code IN (...)} scan —
 * each per-dimension fetch is the cache seam, so repeated dimensions/windows reuse
 * one entry, and no single query has to scan every dimension's buckets at once.
 * The returned rows are sliced back into the per-window samples in memory via
 * {@link StatisticsEventSummaryHelper#bucketByWindows} — the schedule is identical
 * for every dimension, and empty windows are kept as zero samples by
 * {@link StatisticsEventSummaryHelper}.
 *
 * <p>Methods are ordered callees-before-callers: the raw fetch first, then each
 * {@code ...Cacheable} builder ahead of the public entry point that wraps it.
 */
@Component
@ConditionalOnProperty(
		name = "org.coldis.configuration.service.statistics-enabled",
		matchIfMissing = false
)
public class StatisticsEventSummaryServiceComponent {

	/** Statistics context configuration service component. */
	@Autowired
	private StatisticsContextConfigurationServiceComponent statisticsContextConfigurationServiceComponent;

	/** Statistics event summary repository. */
	@Autowired
	private StatisticsEventSummaryRepository statisticsEventSummaryRepository;

	// ---- Raw fetch ----

	/**
	 * Single fetch over {@code (context, dimension_name, date_time)} for one dimension across the whole
	 * date range — the one DB round-trip per dimension that every higher-level computation is built on.
	 * <strong>Prime cache candidate:</strong> keyed on the value-independent
	 * {@code (context, dimensionName, startDateTime, endDateTime)}, and every comparison /
	 * distribution / probability call over the same context, dimension and (truncated) window funnels
	 * through here, so a single cache entry serves them all. Truncation is always done by the caller
	 * <em>before</em> this method is reached (the public entry points truncate, and the schedule's
	 * bounds are truncated by {@link StatisticsEventSummaryHelper}), so the arguments — and therefore
	 * an extended bean's {@code @Cacheable} key — are already on the context interval.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  startDateTime Start date time (truncated before the call).
	 * @param  endDateTime   End date time (truncated before the call).
	 * @return               The list of summaries in the period for the dimension.
	 */
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	protected List<StatisticsEventSummary> findSummariesByPeriodCacheable(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) {
		return this.statisticsEventSummaryRepository.findByPeriod(context, dimensionName, startDateTime, endDateTime);
	}

	/**
	 * Fetches the raw summaries for a context and dimension within a date range, truncating the bounds
	 * to the context interval before delegating to {@link #findSummariesByPeriodCacheable}.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  startDateTime Start date time.
	 * @param  endDateTime   End date time.
	 * @return               The list of summaries in the period for the dimension.
	 */
	public List<StatisticsEventSummary> findSummariesByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) {
		return this.findSummariesByPeriodCacheable(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime),
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, endDateTime));
	}

	// ---- Period aggregation ----

	/**
	 * Merges every summary for a context and dimension across the (already truncated) range into one
	 * aggregated summary. <strong>Good cache candidate:</strong> keyed on the value-independent
	 * {@code (context, dimensionName, startDateTime, endDateTime)}.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  startDateTime     Start date time (truncated before the call).
	 * @param  endDateTime       End date time (truncated before the call).
	 * @return                   The aggregated statistics event summary.
	 * @throws BusinessException If no summaries are found in the period.
	 */
	protected StatisticsEventSummary findByPeriodCacheable(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) throws BusinessException {
		final List<StatisticsEventSummary> summaries = this.findSummariesByPeriodCacheable(context, dimensionName, startDateTime, endDateTime);
		if ((summaries == null) || summaries.isEmpty()) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.notfound"), HttpStatus.NOT_FOUND.value());
		}
		final StatisticsEventSummary merged = new StatisticsEventSummary(context, dimensionName, startDateTime);
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
	 * Finds all summaries for a context and dimension within a date range and merges them into a single
	 * aggregated summary, truncating the bounds before delegating to {@link #findByPeriodCacheable}.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  startDateTime     Start date time.
	 * @param  endDateTime       End date time.
	 * @return                   The aggregated statistics event summary.
	 * @throws BusinessException If no summaries are found in the period.
	 */
	public StatisticsEventSummary findByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) throws BusinessException {
		return this.findByPeriodCacheable(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime),
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, endDateTime));
	}

	// ---- Comparison ----

	/**
	 * Builds the drift comparison for every requested dimension, querying <strong>one dimension at a
	 * time</strong> (each over the whole schedule's range) and bucketing the rows in memory. Returns
	 * one comparison per distinct dimension, in request order. Does no truncation — the schedule is
	 * already resolved and truncated by the caller. <strong>Good cache candidate:</strong> the
	 * per-dimension fetch underneath is keyed on the value-independent
	 * {@code (context, dimension, schedule-range)}.
	 *
	 * @param  context           Context.
	 * @param  dimensionNames    Dimension names to compare.
	 * @param  schedule          Resolved (truncated) sampling schedule.
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of past periods sampled (excluding reference).
	 * @return                   One comparison per distinct dimension, in request order.
	 * @throws BusinessException If no data is found in any of the sampled periods for any dimension.
	 */
	protected List<StatisticsEventSummaryComparison> compareByPeriodCacheable(
			final String context,
			final Collection<String> dimensionNames,
			final WindowSchedule schedule,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final List<StatisticsEventSummaryComparison> comparisons = new ArrayList<>();
		for (final String dimensionName : dimensionNames.stream().distinct().toList()) {
			final List<StatisticsEventSummary> dimensionSummaries = this.findSummariesByPeriodCacheable(context, dimensionName, schedule.overallStart(),
					schedule.overallEnd());
			final List<List<StatisticsEventSummary>> perWindowSummaries = StatisticsEventSummaryHelper.bucketByWindows(dimensionSummaries, schedule.samples());
			final List<StatisticsEventSummary> referenceSummaries = StatisticsEventSummaryHelper.bucketByWindows(dimensionSummaries,
					List.of(schedule.reference())).get(0);
			comparisons.add(StatisticsEventSummaryHelper.computeComparison(referenceSummaries, perWindowSummaries, context, dimensionName,
					schedule.reference().start(), windowUnit, windowSize, stepUnit, steps));
		}
		return comparisons;
	}

	/**
	 * Compares a reference window against historical periods for every requested dimension, providing
	 * averages, standard deviations, value ratios, and z-scores per dimension. Validates the total
	 * window, truncates the reference and builds the sampling schedule, then delegates to
	 * {@link #compareByPeriodCacheable}. The returned list feeds the cross-dimension z-score
	 * aggregators directly.
	 *
	 * @param  context           Context.
	 * @param  dimensionNames    Dimension names to compare.
	 * @param  referenceDateTime Start of the reference window.
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of past periods to sample (excluding reference).
	 * @return                   One comparison per distinct dimension, in request order.
	 * @throws BusinessException If no data is found in any of the sampled periods for any dimension.
	 */
	public List<StatisticsEventSummaryComparison> compareByPeriod(
			final String context,
			final Collection<String> dimensionNames,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		StatisticsEventSummaryHelper.validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
		final long truncationMinutes = this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
		final LocalDateTime referenceStart = StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
		final WindowSchedule schedule = StatisticsEventSummaryHelper.historicalSchedule(referenceStart, windowUnit, windowSize, stepUnit, steps,
				truncationMinutes);
		return this.compareByPeriodCacheable(context, dimensionNames, schedule, windowUnit, windowSize, stepUnit, steps);
	}

	/**
	 * Single-dimension convenience over {@link #compareByPeriod(String, Collection, LocalDateTime,
	 * ChronoUnit, Integer, ChronoUnit, Integer)}: compares one dimension and returns its single
	 * comparison.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  referenceDateTime Start of the reference window.
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of past periods to sample (excluding reference).
	 * @return                   The comparison with averages, std devs, z-scores, and reference values.
	 * @throws BusinessException If no data is found in any of the sampled periods.
	 */
	public StatisticsEventSummaryComparison compareByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		return this.compareByPeriod(context, List.of(dimensionName), referenceDateTime, windowUnit, windowSize, stepUnit, steps).get(0);
	}

	// ---- Distribution / probability ----

	/**
	 * Builds the per-dimension distribution from a single per-dimension fetch spanning the whole
	 * reference-inclusive schedule, then buckets the rows in memory. Does no truncation — the schedule
	 * is already resolved and truncated by the caller. <strong>Good cache candidate:</strong> keyed
	 * only on the value-independent {@code (context, dimension, schedule, window, step, steps)} so every
	 * applicant value evaluated against the same population shares one aggregation — the heavy
	 * fetch-and-aggregate is paid once per period instead of per value.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  schedule          Resolved (truncated) reference-inclusive sampling schedule.
	 * @param  windowUnit        Unit defining the window size.
	 * @param  windowSize        Number of window units per window.
	 * @param  stepUnit          Unit defining how far back each sample is.
	 * @param  steps             Number of periods sampled (including reference).
	 * @return                   The dimension distribution.
	 * @throws BusinessException If no data is found in any of the sampled periods.
	 */
	protected StatisticsEventDimensionDistribution singleDimensionDistributionByPeriodCacheable(
			final String context,
			final String dimensionName,
			final WindowSchedule schedule,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final List<StatisticsEventSummary> dimensionSummaries = this.findSummariesByPeriodCacheable(context, dimensionName, schedule.overallStart(),
				schedule.overallEnd());
		final List<List<StatisticsEventSummary>> perWindowSummaries = StatisticsEventSummaryHelper.bucketByWindows(dimensionSummaries, schedule.samples());
		return StatisticsEventSummaryHelper.computeDistribution(perWindowSummaries, context, dimensionName, schedule.reference().start(), windowUnit, windowSize,
				stepUnit, steps);
	}

	/**
	 * Builds the value-independent per-dimension distribution over the sampled period (reference
	 * included). Validates the total window, truncates the reference and builds the reference-inclusive
	 * schedule, then delegates to {@link #singleDimensionDistributionByPeriodCacheable}.
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
		final LocalDateTime referenceStart = StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
		final WindowSchedule schedule = StatisticsEventSummaryHelper.referenceInclusiveSchedule(referenceStart, windowUnit, windowSize, stepUnit, steps,
				truncationMinutes);
		return this.singleDimensionDistributionByPeriodCacheable(context, dimensionName, schedule, windowUnit, windowSize, stepUnit, steps);
	}

	// Pure reductions over already-computed results — like the z-score aggregators below, these take
	// the output of the fetch/aggregate methods (a distribution, or a list of single-dimension
	// probabilities) and need none of the context/window arguments. Delegated to
	// StatisticsEventSummaryHelper; let a caller that already holds the inputs compute without a fetch.

	/**
	 * Single-dimension probability derived from an already-built distribution (the return of
	 * {@link #singleDimensionDistributionByPeriod}) — a pure lookup-plus-Laplace step, no fetch.
	 *
	 * @param  distribution   The dimension distribution.
	 * @param  dimensionValue The value to evaluate.
	 * @return                The single-dimension probability for the value.
	 */
	public StatisticsEventSingleDimensionProbability singleDimensionProbability(
			final StatisticsEventDimensionDistribution distribution,
			final String dimensionValue) {
		return StatisticsEventSummaryHelper.singleDimensionProbability(distribution, dimensionValue, StatisticsEventSummaryHelper.DEFAULT_SMOOTHING_FACTOR);
	}

	/**
	 * Naive joint probability combined from already-computed single-dimension probabilities — a pure
	 * reduction, no fetch. Context and window metadata are taken from the probabilities themselves.
	 *
	 * @param  individualProbabilities Per-dimension probabilities (non-empty; all from the same call).
	 * @return                         The naive multi-dimension probability.
	 */
	public StatisticsEventNaiveMultiDimensionProbability naiveMultiDimensionProbability(
			final List<StatisticsEventSingleDimensionProbability> individualProbabilities) {
		return StatisticsEventSummaryHelper.naiveMultiDimensionProbability(individualProbabilities);
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
