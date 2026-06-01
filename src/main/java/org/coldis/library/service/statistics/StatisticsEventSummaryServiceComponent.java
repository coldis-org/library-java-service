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
 * period queries ({@link #findByPeriod}/{@link #summarizePeriod}) and drift comparison.
 * The write path (buffering, flushing, applying deltas, single-key lookup/creation) lives in
 * {@link StatisticsEventSummaryBufferServiceComponent}; the probability and cross-dimension
 * z-score reductions are pure static methods on {@link StatisticsEventSummaryHelper}.
 *
 * <p>Each public entry point validates its inputs and truncates its date-time math up
 * front, then delegates to a {@code protected ...Cacheable} sibling that does no
 * truncation. That keeps the seams usable for caching — an extended bean can
 * {@code @Override} one and add {@code @Cacheable} on the already-truncated key. The
 * base bean does not cache.
 *
 * <p>The whole read side is built on one fetch seam — {@link #findByPeriodCacheable}, a
 * {@code WHERE date_time BETWEEN} range query scoped to exact bounds so no out-of-window rows are
 * read. {@link #findByPeriod} returns the rows, {@link #summarizePeriod} merges them into one
 * summary, and drift comparison fetches the reference and each sample window and aggregates them in
 * the service via {@link StatisticsEventSummaryHelper#computeComparison} (window bucketing,
 * mean/std-dev/ratio and z-scores all in {@code BigDecimal} — nothing is aggregated in the database).
 *
 * <p>Each public read method takes two fetch-strategy flags (defaulting to {@code true}/{@code false}
 * in the convenience overloads): {@code useTruncationBuckets} fetches one truncation bucket at a time
 * ({@link #findByPeriodCacheable} called with {@code start == end == bucket}) instead of a single
 * range query, so a bucket cached once is reused by every window or period that contains it — across
 * calls and across the summarize/compare paths; {@code parallel} runs those per-bucket (or, for
 * compare, per-window) reads concurrently. Bucket mode falls back to a range query when the context
 * has no positive truncation.
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
	 * Fetches one window's summaries either as a single range query (one {@link #findByPeriodCacheable}
	 * over {@code [start, end]}, cached at the range grain) or, when {@code useTruncationBuckets} is set,
	 * as one {@link #findByPeriodCacheable} per truncation bucket — each called with {@code start == end ==
	 * bucket}, so its cache entry is keyed on a single bucket and reused by every window or period that
	 * contains that bucket, across calls and across the summarize / compare paths. When {@code parallel}
	 * is set the per-bucket reads run concurrently. Not a cache seam itself — the caching lives on
	 * {@link #findByPeriodCacheable}.
	 */
	private List<StatisticsEventSummary> fetchPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime,
			final boolean useTruncationBuckets,
			final boolean parallel) {
		final List<StatisticsEventSummary> summaries;
		final long truncationMinutes = useTruncationBuckets ? this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context) : 0L;
		// Bucket mode needs a positive truncation to enumerate the grid; without one, fall back to the
		// single range query (a degenerate step would otherwise miss every bucket after the first).
		if (useTruncationBuckets && (truncationMinutes > 0L)) {
			final List<LocalDateTime> buckets = StatisticsEventSummaryHelper.bucketsBetween(startDateTime, endDateTime, truncationMinutes);
			summaries = (parallel ? buckets.parallelStream() : buckets.stream())
					.flatMap(bucket -> this.findByPeriodCacheable(context, dimensionName, bucket, bucket).stream()).toList();
		}
		else {
			summaries = this.findByPeriodCacheable(context, dimensionName, startDateTime, endDateTime);
		}
		return summaries;
	}

	/**
	 * Fetches the raw summaries for a context and dimension within a date range, truncating the bounds to
	 * the context interval before delegating to {@link #fetchPeriod}. With explicit control over the fetch
	 * strategy: per-truncation-bucket fetching (for cache reuse) and parallel reads.
	 *
	 * @param  context              Context.
	 * @param  dimensionName        Dimension name.
	 * @param  startDateTime        Start date time.
	 * @param  endDateTime          End date time.
	 * @param  useTruncationBuckets Fetch/cache one truncation bucket at a time instead of a range query.
	 * @param  parallel             Fetch the per-bucket reads concurrently.
	 * @return                      The list of summaries in the period for the dimension.
	 */
	public List<StatisticsEventSummary> findByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime,
			final boolean useTruncationBuckets,
			final boolean parallel) {
		return this.fetchPeriod(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime),
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, endDateTime), useTruncationBuckets, parallel);
	}

	/**
	 * Fetches the raw summaries for a context and dimension within a date range — the convenience form
	 * (per-truncation-bucket fetching on, sequential), delegating to
	 * {@link #findByPeriod(String, String, LocalDateTime, LocalDateTime, boolean, boolean)}.
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
		return this.findByPeriod(context, dimensionName, startDateTime, endDateTime, true, false);
	}

	// ---- Period aggregation ----

	/**
	 * Merges every summary for a context and dimension across the (already truncated) range into one
	 * aggregated summary. <strong>Good cache candidate:</strong> keyed on the value-independent
	 * {@code (context, dimensionName, startDateTime, endDateTime)}.
	 *
	 * @param  context              Context.
	 * @param  dimensionName        Dimension name.
	 * @param  startDateTime        Start date time (truncated before the call).
	 * @param  endDateTime          End date time (truncated before the call).
	 * @param  useTruncationBuckets Fetch and cache one truncation bucket at a time (higher cache reuse)
	 *                                  instead of a single range query.
	 * @param  parallel             Fetch the per-bucket reads concurrently (only meaningful with
	 *                                  {@code useTruncationBuckets}).
	 * @return                      The aggregated statistics event summary.
	 * @throws BusinessException    If no summaries are found in the period.
	 */
	protected StatisticsEventSummary summarizePeriodCacheable(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime,
			final boolean useTruncationBuckets,
			final boolean parallel) throws BusinessException {
		final List<StatisticsEventSummary> summaries = this.fetchPeriod(context, dimensionName, startDateTime, endDateTime, useTruncationBuckets, parallel);
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
	 * With explicit control over the fetch strategy: per-truncation-bucket fetching (for cache reuse) and
	 * parallel reads.
	 *
	 * @param  context              Context.
	 * @param  dimensionName        Dimension name.
	 * @param  startDateTime        Start date time.
	 * @param  endDateTime          End date time.
	 * @param  useTruncationBuckets Fetch/cache one truncation bucket at a time instead of a range query.
	 * @param  parallel             Fetch the per-bucket reads concurrently.
	 * @return                      The aggregated statistics event summary.
	 * @throws BusinessException    If no summaries are found in the period.
	 */
	public StatisticsEventSummary summarizePeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime,
			final boolean useTruncationBuckets,
			final boolean parallel) throws BusinessException {
		return this.summarizePeriodCacheable(context, dimensionName,
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, startDateTime),
				this.statisticsContextConfigurationServiceComponent.truncateDateTime(context, endDateTime), useTruncationBuckets, parallel);
	}

	/**
	 * Finds all summaries for a context and dimension within a date range and merges them into a single
	 * aggregated summary — the convenience form (per-truncation-bucket fetching on, sequential),
	 * delegating to {@link #summarizePeriod(String, String, LocalDateTime, LocalDateTime, boolean, boolean)}.
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
		return this.summarizePeriod(context, dimensionName, startDateTime, endDateTime, true, false);
	}

	// ---- Comparison ----

	/**
	 * Builds the drift comparison per dimension by fetching each window's summaries and aggregating in
	 * the service: one indexed range query per window ({@link #findByPeriodCacheable}, scoped to that
	 * window's exact {@code [start, end]} so no out-of-window rows are fetched or discarded) for the
	 * reference and every sample window, then {@link StatisticsEventSummaryHelper#computeComparison}
	 * derives averages, std-devs, ratios and z-scores in {@code BigDecimal}. Returns one comparison per
	 * distinct dimension, in request order. Does no truncation — the schedule is already resolved and
	 * truncated by the caller. Each per-window fetch is the cache seam, so repeated windows reuse one
	 * cache entry.
	 *
	 * @param  context              Context.
	 * @param  dimensionNames       Dimension names to compare.
	 * @param  schedule             Resolved (truncated) sampling schedule.
	 * @param  windowUnit           Unit defining the window size.
	 * @param  windowSize           Number of window units per window.
	 * @param  stepUnit             Unit defining how far back each sample is.
	 * @param  steps                Number of past periods sampled (excluding reference).
	 * @param  useTruncationBuckets Fetch/cache one truncation bucket at a time (higher cache reuse)
	 *                                  instead of one range query per window.
	 * @param  parallel             Fetch the windows (and, in bucket mode, the reference's buckets)
	 *                                  concurrently.
	 * @return                      One comparison per distinct dimension, in request order.
	 * @throws BusinessException    If no data is found in any of the sampled periods for any dimension.
	 */
	protected List<StatisticsEventSummaryComparison> compareByPeriodCacheable(
			final String context,
			final Collection<String> dimensionNames,
			final WindowSchedule schedule,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps,
			final boolean useTruncationBuckets,
			final boolean parallel) throws BusinessException {
		final List<StatisticsEventSummaryComparison> comparisons = new ArrayList<>();
		for (final String dimensionName : dimensionNames.stream().distinct().toList()) {
			final List<StatisticsEventSummary> referenceSummaries =
					this.fetchPeriod(context, dimensionName, schedule.reference().start(), schedule.reference().end(), useTruncationBuckets, parallel);
			// Sample windows are fetched concurrently when parallel; each window's own buckets stay
			// sequential to avoid nested parallelism. Window order does not affect the aggregation.
			final List<StatisticsEventSummaryHelper.Window> samples = schedule.samples();
			final List<List<StatisticsEventSummary>> perWindowSummaries =
					(parallel ? samples.parallelStream() : samples.stream())
							.map(window -> this.fetchPeriod(context, dimensionName, window.start(), window.end(), useTruncationBuckets, false)).toList();
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
	 * aggregators directly. With explicit control over the fetch strategy: per-truncation-bucket
	 * fetching (for cache reuse) and parallel reads.
	 *
	 * @param  context              Context.
	 * @param  dimensionNames       Dimension names to compare.
	 * @param  referenceDateTime    Start of the reference window.
	 * @param  windowUnit           Unit defining the window size.
	 * @param  windowSize           Number of window units per window.
	 * @param  stepUnit             Unit defining how far back each sample is.
	 * @param  steps                Number of past periods to sample (excluding reference).
	 * @param  useTruncationBuckets Fetch/cache one truncation bucket at a time instead of a range query per window.
	 * @param  parallel             Fetch the windows concurrently.
	 * @return                      One comparison per distinct dimension, in request order.
	 * @throws BusinessException    If no data is found in any of the sampled periods for any dimension.
	 */
	public List<StatisticsEventSummaryComparison> compareByPeriod(
			final String context,
			final Collection<String> dimensionNames,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps,
			final boolean useTruncationBuckets,
			final boolean parallel) throws BusinessException {
		StatisticsEventSummaryHelper.validateTotalWindow(windowUnit, windowSize, stepUnit, steps);
		final long truncationMinutes = this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context);
		final LocalDateTime referenceStart = StatisticsEvent.truncateDateTime(referenceDateTime, truncationMinutes);
		final WindowSchedule schedule = StatisticsEventSummaryHelper.historicalSchedule(referenceStart, windowUnit, windowSize, stepUnit, steps,
				truncationMinutes);
		return this.compareByPeriodCacheable(context, dimensionNames, schedule, windowUnit, windowSize, stepUnit, steps, useTruncationBuckets, parallel);
	}

	/**
	 * Compares a reference window against historical periods for every requested dimension — the
	 * convenience form (per-truncation-bucket fetching on, sequential), delegating to
	 * {@link #compareByPeriod(String, Collection, LocalDateTime, ChronoUnit, Integer, ChronoUnit, Integer, boolean, boolean)}.
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
		return this.compareByPeriod(context, dimensionNames, referenceDateTime, windowUnit, windowSize, stepUnit, steps, true, false);
	}

	/**
	 * Single-dimension convenience over {@link #compareByPeriod(String, Collection, LocalDateTime,
	 * ChronoUnit, Integer, ChronoUnit, Integer, boolean, boolean)} with explicit fetch-strategy control.
	 *
	 * @param  context              Context.
	 * @param  dimensionName        Dimension name.
	 * @param  referenceDateTime    Start of the reference window.
	 * @param  windowUnit           Unit defining the window size.
	 * @param  windowSize           Number of window units per window.
	 * @param  stepUnit             Unit defining how far back each sample is.
	 * @param  steps                Number of past periods to sample (excluding reference).
	 * @param  useTruncationBuckets Fetch/cache one truncation bucket at a time instead of a range query per window.
	 * @param  parallel             Fetch the windows concurrently.
	 * @return                      The comparison with averages, std devs, z-scores, and reference values.
	 * @throws BusinessException    If no data is found in any of the sampled periods.
	 */
	public StatisticsEventSummaryComparison compareByPeriod(
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps,
			final boolean useTruncationBuckets,
			final boolean parallel) throws BusinessException {
		return this.compareByPeriod(context, List.of(dimensionName), referenceDateTime, windowUnit, windowSize, stepUnit, steps, useTruncationBuckets,
				parallel).get(0);
	}

	/**
	 * Single-dimension convenience over {@link #compareByPeriod(String, Collection, LocalDateTime,
	 * ChronoUnit, Integer, ChronoUnit, Integer)}: compares one dimension and returns its single
	 * comparison (per-truncation-bucket fetching on, sequential).
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
}
