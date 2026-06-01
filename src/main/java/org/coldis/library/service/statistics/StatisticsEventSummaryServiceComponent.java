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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics event summary computation service component. Read/query side:
 * period queries ({@link #findByPeriod}/{@link #summarizePeriod}), drift comparison,
 * probability, and the cross-dimension z-score aggregators. The write path (buffering,
 * flushing, applying deltas, single-key lookup/creation) lives in
 * {@link StatisticsEventSummaryBufferServiceComponent}.
 *
 * <p>Each public entry point validates its inputs and truncates its date-time math up
 * front, then delegates to a {@code protected ...Cacheable} sibling that does no
 * truncation. That keeps the seams usable for caching — an extended bean can
 * {@code @Override} one and add {@code @Cacheable} on the already-truncated key. The
 * base bean does not cache.
 *
 * <p>Two query shapes back the read side:
 * <ul>
 * <li><b>Range fetch</b> ({@link #findByPeriodCacheable}) — one {@code WHERE date_time
 * BETWEEN} query per dimension; {@link #findByPeriod} returns the rows and
 * {@link #summarizePeriod} merges them into one summary. Probability rides on the
 * merged summary.</li>
 * <li><b>Sufficient statistics</b> ({@link #compareStatisticsCacheable}) — for drift
 * comparison, the window bucketing, JSONB map merge, and per-value summation are pushed
 * into Postgres, so one query per dimension returns a few sums per value (Σ, Σ², and the
 * ratio equivalents over the sample windows, plus the reference value) instead of any
 * per-window maps. {@link StatisticsEventSummaryHelper#assembleComparison} turns those into
 * averages, std-devs and z-scores in {@code BigDecimal}.</li>
 * </ul>
 *
 * <p>Methods are ordered callees-before-callers: each {@code ...Cacheable} seam ahead of
 * the public entry point that wraps it.
 */
@Component
@Qualifier(StatisticsEventSummaryServiceComponent.QUALIFIER)
@ConditionalOnProperty(
		name = "org.coldis.configuration.service.statistics-enabled",
		matchIfMissing = false
)
public class StatisticsEventSummaryServiceComponent {

	/**
	 * Bean qualifier. Pin this library bean with {@code @Qualifier(StatisticsEventSummaryServiceComponent.QUALIFIER)}
	 * when an extended (e.g. caching) subclass makes injection by type ambiguous.
	 */
	public static final String QUALIFIER = "statisticsEventSummaryServiceComponent";

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
	protected List<StatisticsEventSummary> findByPeriodCacheable(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) {
		return this.statisticsEventSummaryRepository.findByPeriod(context, dimensionName, startDateTime, endDateTime);
	}

	/**
	 * Fetches the raw summaries for a context and dimension within a date range, truncating the bounds
	 * to the context interval before delegating to {@link #findByPeriodCacheable}.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  startDateTime Start date time.
	 * @param  endDateTime   End date time.
	 * @return               The list of summaries in the period for the dimension.
	 */
	public List<StatisticsEventSummary> findByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) {
		return this.findByPeriodCacheable(context, dimensionName,
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
	protected StatisticsEventSummary summarizePeriodCacheable(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) throws BusinessException {
		final List<StatisticsEventSummary> summaries = this.findByPeriodCacheable(context, dimensionName, startDateTime, endDateTime);
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
	 * aggregated summary, truncating the bounds before delegating to {@link #summarizePeriodCacheable}.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  startDateTime     Start date time.
	 * @param  endDateTime       End date time.
	 * @return                   The aggregated statistics event summary.
	 * @throws BusinessException If no summaries are found in the period.
	 */
	public StatisticsEventSummary summarizePeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime) throws BusinessException {
		return this.summarizePeriodCacheable(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime),
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, endDateTime));
	}

	// ---- Comparison ----

	/**
	 * Comparison sufficient-statistics seam: one Postgres query that reduces the schedule's sample and
	 * reference windows to per-value sums (window bucketing, JSONB map merge and per-value summation
	 * pushed into the database). The flat date arrays the query needs are derived from the schedule's
	 * {@link StatisticsEventSummaryHelper.Window} records here, at the repo boundary.
	 * <strong>Cache candidate:</strong> keyed on the value-independent {@code (context, dimensionName,
	 * schedule)} — the schedule's windows are already truncated and a record, so the key has value-based
	 * equality.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  schedule      Resolved (truncated) sampling schedule (reference + sample windows).
	 * @return               The sufficient-statistic rows for the dimension.
	 */
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	protected List<StatisticsEventSummaryRepositoryCustom.ComparisonStatistic> compareStatisticsCacheable(
			final String context,
			final String dimensionName,
			final WindowSchedule schedule) {
		final List<StatisticsEventSummaryHelper.Window> sampleWindows = schedule.samples();
		final LocalDateTime[] sampleStarts = new LocalDateTime[sampleWindows.size()];
		final LocalDateTime[] sampleEnds = new LocalDateTime[sampleWindows.size()];
		for (int sampleIndex = 0; sampleIndex < sampleWindows.size(); sampleIndex++) {
			sampleStarts[sampleIndex] = sampleWindows.get(sampleIndex).start();
			sampleEnds[sampleIndex] = sampleWindows.get(sampleIndex).end();
		}
		return this.statisticsEventSummaryRepository.compareStatistics(context, dimensionName,
				sampleStarts, sampleEnds, schedule.reference().start(), schedule.reference().end());
	}

	/**
	 * Builds the drift comparison per dimension from the Postgres-computed sufficient statistics: one
	 * {@code dimensionName}-scoped query per dimension ({@link #compareStatisticsCacheable}) reduces the
	 * sample/reference windows to per-value sums, then {@link StatisticsEventSummaryHelper#assembleComparison}
	 * turns them into averages, std-devs, ratios and z-scores. No per-window maps cross the wire.
	 * Returns one comparison per distinct dimension, in request order. Does no truncation — the schedule
	 * is already resolved and truncated by the caller. <strong>Good cache candidate:</strong> keyed on
	 * the value-independent {@code (context, dimension, schedule, window, step, steps)}.
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
			final List<StatisticsEventSummaryRepositoryCustom.ComparisonStatistic> statistics =
					this.compareStatisticsCacheable(context, dimensionName, schedule);
			comparisons.add(StatisticsEventSummaryHelper.assembleComparison(statistics, schedule.samples().size(), context, dimensionName,
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

	// Pure reductions over already-computed results — like the z-score aggregators below, these take
	// the output of the fetch/aggregate methods (a merged summary, or a list of single-dimension
	// probabilities) and need none of the context/window arguments. Delegated to
	// StatisticsEventSummaryHelper; let a caller that already holds the inputs compute without a fetch.

	/**
	 * Single-dimension probability for a value within a period, derived from that period's aggregated
	 * summary (the return of {@link #findByPeriod}) — a pure {@code count/total} plus Laplace step, no
	 * fetch.
	 *
	 * @param  summary        The period's aggregated summary.
	 * @param  dimensionValue The value to evaluate.
	 * @return                The single-dimension probability for the value.
	 */
	public StatisticsEventSingleDimensionProbability singleDimensionProbability(
			final StatisticsEventSummary summary,
			final String dimensionValue) {
		return StatisticsEventSummaryHelper.singleDimensionProbability(summary, dimensionValue, StatisticsEventSummaryHelper.DEFAULT_SMOOTHING_FACTOR);
	}

	/**
	 * Naive joint probability from one merged period summary per dimension plus the value to evaluate
	 * for each (positionally paired) — derives each dimension's probability and combines them. A pure
	 * reduction, no fetch; the per-dimension {@link #findByPeriod} returns feed straight in.
	 *
	 * @param  summaries         Per-dimension merged summaries (non-empty; aligned with {@code dimensionValues}).
	 * @param  dimensionValues   The value to evaluate for each dimension (aligned with {@code summaries}).
	 * @return                   The naive multi-dimension probability.
	 * @throws BusinessException If {@code summaries} is null/empty or not positionally aligned with
	 *                               {@code dimensionValues}.
	 */
	public StatisticsEventNaiveMultiDimensionProbability naiveMultiDimensionProbability(
			final List<StatisticsEventSummary> summaries,
			final List<String> dimensionValues) throws BusinessException {
		return StatisticsEventSummaryHelper.naiveMultiDimensionProbability(summaries, dimensionValues);
	}
}
