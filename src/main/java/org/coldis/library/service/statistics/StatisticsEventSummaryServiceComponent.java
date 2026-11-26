package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * compare without buckets, per-window) reads concurrently. In bucket mode a comparison reads each
 * distinct bucket of its reference and sample windows exactly once and assembles the windows in
 * memory, so buckets shared by several windows are not read again. Bucket mode falls back to a range
 * query when the context has no positive truncation.
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
	 * Resolves the truncation interval to read by bucket: the context's positive truncation when
	 * {@code useTruncationBuckets} is set, otherwise {@code 0}. Bucket mode needs a positive truncation to
	 * enumerate the grid; with {@code 0} the callers fall back to range queries (a degenerate step would
	 * otherwise miss every bucket after the first). The single owner of when bucket mode applies.
	 *
	 * @param  context              Context.
	 * @param  useTruncationBuckets Whether bucket mode was requested.
	 * @return                      The positive truncation minutes to read by bucket, or {@code 0}.
	 */
	private long resolveBucketTruncationMinutes(
			final String context,
			final boolean useTruncationBuckets) {
		final long truncationMinutes = useTruncationBuckets ? this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(context) : 0L;
		return (truncationMinutes > 0L ? truncationMinutes : 0L);
	}

	/**
	 * Reads each given truncation bucket once through {@link #findByPeriodCacheable} with
	 * {@code start == end == bucket}, concurrently when {@code parallel} is set. A failing bucket read
	 * fails the whole read.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  buckets       Distinct buckets to read.
	 * @param  parallel      Read the buckets concurrently.
	 * @return               Each bucket's summaries, keyed by bucket.
	 */
	private Map<LocalDateTime, List<StatisticsEventSummary>> readBuckets(
			final String context,
			final String dimensionName,
			final List<LocalDateTime> buckets,
			final boolean parallel) {
		return (parallel ? buckets.parallelStream() : buckets.stream())
				.collect(Collectors.toMap(Function.identity(), bucket -> this.findByPeriodCacheable(context, dimensionName, bucket, bucket)));
	}

	/**
	 * Assembles one window's summaries from buckets already read, in bucket order: a window is the
	 * concatenation of its inclusive buckets' rows. The single assembly routine for both the window fetch
	 * ({@link #fetchPeriodCacheable}) and the comparison ({@link #compareByPeriodCacheable}).
	 *
	 * @param  bucketSummaries   Each bucket's summaries, keyed by bucket.
	 * @param  startDateTime     Window start (truncated).
	 * @param  endDateTime       Window end (truncated).
	 * @param  truncationMinutes Positive truncation interval of the context.
	 * @return                   The window's summaries.
	 */
	private static List<StatisticsEventSummary> assembleWindowSummaries(
			final Map<LocalDateTime, List<StatisticsEventSummary>> bucketSummaries,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime,
			final long truncationMinutes) {
		return StatisticsEventSummaryHelper.bucketsBetween(startDateTime, endDateTime, truncationMinutes).stream()
				.flatMap(bucket -> bucketSummaries.get(bucket).stream()).toList();
	}

	/**
	 * Fetches one window's summaries either as a single range query (one {@link #findByPeriodCacheable}
	 * over {@code [start, end]}) or, when {@code useTruncationBuckets} is set, as one
	 * {@link #findByPeriodCacheable} per truncation bucket — each called with {@code start == end == bucket},
	 * so the bucket cache entry is reused by every window or period that contains it. When {@code parallel}
	 * is set the per-bucket reads run concurrently.
	 *
	 * <p><strong>Cache candidate at the window grain.</strong> The result depends only on the
	 * value-independent {@code (context, dimensionName, startDateTime, endDateTime)} — {@code
	 * useTruncationBuckets} and {@code parallel} only change <em>how</em> the rows are fetched, never
	 * <em>which</em> rows come back — so an extended bean that {@code @Cacheable}s this must key on those
	 * four and <em>exclude</em> the two strategy flags from the key. Caching here (window grain) is an
	 * alternative to caching {@link #findByPeriodCacheable} (range/bucket grain), not a complement: for a
	 * range fetch the two cache the same rows. Bounds are already truncated by the caller.
	 *
	 * <p><strong>Not used by bucket-mode comparisons.</strong> {@link #compareByPeriodCacheable} in bucket
	 * mode reads its buckets directly through {@link #findByPeriodCacheable}, so an override of (or a cache
	 * on) this method applies to the find and summarize paths only; the bucket-grain hook comparisons use is
	 * {@link #findByPeriodCacheable}.
	 *
	 * @param  context              Context.
	 * @param  dimensionName        Dimension name.
	 * @param  startDateTime        Start date time (truncated before the call).
	 * @param  endDateTime          End date time (truncated before the call).
	 * @param  useTruncationBuckets Fetch one truncation bucket at a time instead of a single range query.
	 * @param  parallel             Fetch the per-bucket reads concurrently.
	 * @return                      The window's summaries.
	 */
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	protected List<StatisticsEventSummary> fetchPeriodCacheable(
			final String context,
			final String dimensionName,
			final LocalDateTime startDateTime,
			final LocalDateTime endDateTime,
			final boolean useTruncationBuckets,
			final boolean parallel) {
		final List<StatisticsEventSummary> summaries;
		final long truncationMinutes = this.resolveBucketTruncationMinutes(context, useTruncationBuckets);
		if (truncationMinutes > 0L) {
			final Map<LocalDateTime, List<StatisticsEventSummary>> bucketSummaries = this.readBuckets(context, dimensionName,
					StatisticsEventSummaryHelper.bucketsBetween(startDateTime, endDateTime, truncationMinutes), parallel);
			summaries = StatisticsEventSummaryServiceComponent.assembleWindowSummaries(bucketSummaries, startDateTime, endDateTime, truncationMinutes);
		}
		else {
			summaries = this.findByPeriodCacheable(context, dimensionName, startDateTime, endDateTime);
		}
		return summaries;
	}

	/**
	 * Fetches the raw summaries for a context and dimension within a date range, truncating the bounds to
	 * the context interval before delegating to {@link #fetchPeriodCacheable}. With explicit control over the fetch
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
		return this.fetchPeriodCacheable(context, dimensionName,
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
		final List<StatisticsEventSummary> summaries = this.fetchPeriodCacheable(context, dimensionName, startDateTime, endDateTime, useTruncationBuckets, parallel);
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
	 * Reads each distinct truncation bucket of a comparison's reference and sample windows exactly once,
	 * through {@link #findByPeriodCacheable} with {@code start == end == bucket}. Windows of a schedule
	 * overlap in buckets (a window's inclusive bucket range shares its edge buckets with its neighbours,
	 * and same-slot schedules can repeat buckets), so reading per window would read those buckets again.
	 * When {@code parallel} is set the bucket reads run concurrently. A failing bucket read fails the
	 * whole fetch, as it does on the per-window path.
	 *
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  schedule          Resolved (truncated) sampling schedule.
	 * @param  truncationMinutes Positive truncation interval of the context.
	 * @param  parallel          Read the buckets concurrently.
	 * @return                   Each distinct bucket's summaries, keyed by bucket.
	 */
	private Map<LocalDateTime, List<StatisticsEventSummary>> fetchScheduleBucketsOnce(
			final String context,
			final String dimensionName,
			final WindowSchedule schedule,
			final long truncationMinutes,
			final boolean parallel) {
		final Set<LocalDateTime> distinctBuckets = new LinkedHashSet<>(
				StatisticsEventSummaryHelper.bucketsBetween(schedule.reference().start(), schedule.reference().end(), truncationMinutes));
		for (final StatisticsEventSummaryHelper.Window window : schedule.samples()) {
			distinctBuckets.addAll(StatisticsEventSummaryHelper.bucketsBetween(window.start(), window.end(), truncationMinutes));
		}
		return this.readBuckets(context, dimensionName, List.copyOf(distinctBuckets), parallel);
	}


	/**
	 * Builds the drift comparison per dimension by fetching the windows' summaries and aggregating in
	 * the service, then {@link StatisticsEventSummaryHelper#computeComparison} derives averages,
	 * std-devs, ratios and z-scores in {@code BigDecimal}. Returns one comparison per distinct
	 * dimension, in request order. Does no truncation — the schedule is already resolved and truncated
	 * by the caller.
	 *
	 * <p>In bucket mode ({@code useTruncationBuckets} with a positive context truncation) each distinct
	 * bucket of the reference and sample windows is read once ({@link #fetchScheduleBucketsOnce}) and
	 * the windows are assembled in memory, giving exactly the rows the per-window fetch would. Otherwise
	 * each window is one indexed range query ({@link #fetchPeriodCacheable} → {@link
	 * #findByPeriodCacheable}, scoped to that window's exact {@code [start, end]} so no out-of-window rows
	 * are fetched or discarded). In bucket mode this method no longer goes through {@link
	 * #fetchPeriodCacheable}, so an override of (or a window-grain cache on) that seam applies to the find
	 * and summarize paths only and is not consulted by comparisons; the bucket-grain hook comparisons use
	 * is {@link #findByPeriodCacheable}.
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
	 * @param  parallel             Fetch concurrently: the buckets in bucket mode, otherwise the windows.
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
		final long truncationMinutes = this.resolveBucketTruncationMinutes(context, useTruncationBuckets);
		final boolean isReadingBucketsOnce = (truncationMinutes > 0L);
		for (final String dimensionName : dimensionNames.stream().distinct().toList()) {
			final List<StatisticsEventSummary> referenceSummaries;
			final List<List<StatisticsEventSummary>> perWindowSummaries;
			final List<StatisticsEventSummaryHelper.Window> samples = schedule.samples();
			if (isReadingBucketsOnce) {
				final Map<LocalDateTime, List<StatisticsEventSummary>> bucketSummaries =
						this.fetchScheduleBucketsOnce(context, dimensionName, schedule, truncationMinutes, parallel);
				referenceSummaries = StatisticsEventSummaryServiceComponent.assembleWindowSummaries(bucketSummaries, schedule.reference().start(),
						schedule.reference().end(), truncationMinutes);
				perWindowSummaries = samples.stream().map(window -> StatisticsEventSummaryServiceComponent.assembleWindowSummaries(bucketSummaries,
						window.start(), window.end(), truncationMinutes)).toList();
			}
			else {
				referenceSummaries =
						this.fetchPeriodCacheable(context, dimensionName, schedule.reference().start(), schedule.reference().end(), useTruncationBuckets, parallel);
				// Sample windows are fetched concurrently when parallel. Window order does not affect the aggregation.
				perWindowSummaries = (parallel ? samples.parallelStream() : samples.stream())
						.map(window -> this.fetchPeriodCacheable(context, dimensionName, window.start(), window.end(), useTruncationBuckets, false)).toList();
			}
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
