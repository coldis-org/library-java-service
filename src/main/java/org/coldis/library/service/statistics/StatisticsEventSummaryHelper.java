package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.http.HttpStatus;

/**
 * Pure (no I/O) statistics calculations over already-fetched {@link StatisticsEventSummary} data:
 * window boundaries, period aggregation, drift comparison, per-dimension distribution/probability,
 * the naive multi-dimension joint, and the cross-dimension z-score aggregators.
 *
 * <p>{@link StatisticsEventSummaryServiceComponent} owns fetching and delegates every computation
 * here; the base component does not cache the summary path, but exposes {@code protected} extension
 * points an extended bean can override to add caching. Callers that already hold (and cache, e.g.
 * with longer or two-layer caches) the underlying summaries can compute against them directly.
 *
 * <p>Methods are ordered callees-before-callers: low-level primitives first, then the mid-level
 * builders, then the public entry points that compose them.
 */
public final class StatisticsEventSummaryHelper {

	private StatisticsEventSummaryHelper() {
	}

	/** Math context for BigDecimal operations. */
	static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

	/** Default additive (Laplace) smoothing factor for probability estimates. */
	public static final double DEFAULT_SMOOTHING_FACTOR = 1.0;

	/** Maximum total window span (6 months). */
	private static final Duration MAX_TOTAL_WINDOW = Duration.ofDays(183);

	// ---- Intermediate aggregation holders ----

	/** Aggregated statistics for one metric across historical periods. */
	private static class MetricAggregation {
		final List<BigDecimal> totals = new ArrayList<>();
		final List<Map<String, BigDecimal>> allValues = new ArrayList<>();
		BigDecimal averageTotal;
		BigDecimal stdDevTotal;
		final Map<String, BigDecimal> averageValues = new HashMap<>();
		final Map<String, BigDecimal> stdDevValues = new HashMap<>();
		final Map<String, BigDecimal> averageRatios = new HashMap<>();
		final Map<String, BigDecimal> stdDevRatios = new HashMap<>();
	}

	/** Internal holder for aggregated period data used by comparison and probability. */
	private static class PeriodAggregation {
		final MetricAggregation counts = new MetricAggregation();
		final MetricAggregation weights = new MetricAggregation();
		final Set<String> allKeys = new HashSet<>();
	}

	// ---- Low-level math primitives ----

	/** Approximate day span of {@code size} units, floored at 1 day for sub-day units. */
	private static long toDays(
			final long size,
			final ChronoUnit unit) {
		return switch (unit) {
			case HOURS -> Math.max(size / 24, 1);
			case DAYS -> size;
			case WEEKS -> size * 7;
			case MONTHS -> size * 31;
			default -> size;
		};
	}

	/** Converts a Map&lt;String, Long&gt; to Map&lt;String, BigDecimal&gt;. */
	private static Map<String, BigDecimal> toBigDecimalMap(
			final Map<String, Long> longMap) {
		final Map<String, BigDecimal> result = new HashMap<>(longMap.size());
		longMap.forEach((
				key,
				value) -> result.put(key, BigDecimal.valueOf(value)));
		return result;
	}

	/** Computes the average of an array of BigDecimal values. */
	private static BigDecimal computeAverage(
			final BigDecimal[] values) {
		if (values.length == 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal sum = BigDecimal.ZERO;
		for (final BigDecimal value : values) {
			sum = sum.add(value, StatisticsEventSummaryHelper.MATH_CONTEXT);
		}
		return sum.divide(BigDecimal.valueOf(values.length), StatisticsEventSummaryHelper.MATH_CONTEXT);
	}

	/** Computes the population standard deviation given pre-computed mean. */
	private static BigDecimal computeStdDev(
			final BigDecimal[] values,
			final BigDecimal mean) {
		if (values.length == 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal sumSquaredDiff = BigDecimal.ZERO;
		for (final BigDecimal value : values) {
			final BigDecimal difference = value.subtract(mean, StatisticsEventSummaryHelper.MATH_CONTEXT);
			sumSquaredDiff = sumSquaredDiff.add(difference.multiply(difference, StatisticsEventSummaryHelper.MATH_CONTEXT),
					StatisticsEventSummaryHelper.MATH_CONTEXT);
		}
		final BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(values.length), StatisticsEventSummaryHelper.MATH_CONTEXT);
		return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).round(StatisticsEventSummaryHelper.MATH_CONTEXT);
	}

	/** Computes the z-score (number of standard deviations from the mean); zero if stdDev is zero. */
	private static BigDecimal computeZScore(
			final BigDecimal observed,
			final BigDecimal mean,
			final BigDecimal stdDev) {
		return stdDev.compareTo(BigDecimal.ZERO) > 0
				? observed.subtract(mean, StatisticsEventSummaryHelper.MATH_CONTEXT).divide(stdDev, StatisticsEventSummaryHelper.MATH_CONTEXT)
				: BigDecimal.ZERO;
	}

	/**
	 * Laplace (additive) smoothed probability {@code (pooledValueCount + α) / (pooledTotal + α·V)},
	 * where {@code V = distinctValueCount} and {@code α = smoothingFactor}. Never zero, so the joint
	 * probability and its log stay finite even for a value unseen in the sampled periods.
	 */
	private static BigDecimal laplaceSmoothedProbability(
			final BigDecimal pooledValueCount,
			final BigDecimal pooledTotal,
			final int distinctValueCount,
			final double smoothingFactor) {
		final BigDecimal alpha = BigDecimal.valueOf(smoothingFactor);
		final BigDecimal numerator = pooledValueCount.add(alpha, StatisticsEventSummaryHelper.MATH_CONTEXT);
		final BigDecimal denominator = pooledTotal.add(
				alpha.multiply(BigDecimal.valueOf(distinctValueCount), StatisticsEventSummaryHelper.MATH_CONTEXT), StatisticsEventSummaryHelper.MATH_CONTEXT);
		return numerator.divide(denominator, StatisticsEventSummaryHelper.MATH_CONTEXT);
	}

	/**
	 * Standard-normal CDF via Abramowitz &amp; Stegun 7.1.26 (~7.5e-8 max error). Computed in
	 * {@code double}: the CDF is transcendental with no exact {@code BigDecimal} form, and its
	 * approximation error dwarfs any float rounding.
	 */
	private static double standardNormalCdf(
			final double value) {
		final double a1 = 0.254829592;
		final double a2 = -0.284496736;
		final double a3 = 1.421413741;
		final double a4 = -1.453152027;
		final double a5 = 1.061405429;
		final double pCoefficient = 0.3275911;
		final double sign = value < 0 ? -1.0 : 1.0;
		final double absScaled = Math.abs(value) / Math.sqrt(2.0);
		final double tValue = 1.0 / (1.0 + pCoefficient * absScaled);
		final double polynomial = 1.0 - (((((a5 * tValue + a4) * tValue) + a3) * tValue + a2) * tValue + a1) * tValue * Math.exp(-absScaled * absScaled);
		return 0.5 * (1.0 + sign * polynomial);
	}

	/**
	 * Two-sided-p-value surprise {@code −log(2·(1−Φ(|z|)))} for a single z-score, floored so the
	 * {@code log} stays finite. Computed in {@code double} (transcendental {@code log} and CDF).
	 */
	private static double surprise(
			final BigDecimal zScore) {
		final double cdf = StatisticsEventSummaryHelper.standardNormalCdf(zScore.abs().doubleValue());
		final double tailProbability = Math.max(2.0 * (1.0 - cdf), 1e-300);
		return -Math.log(tailProbability);
	}

	/** Sum of squared z-scores ({@code Σ z²}), the chi-square statistic. Summed exactly in {@code BigDecimal}. */
	private static BigDecimal sumOfSquares(
			final List<BigDecimal> zScores) {
		BigDecimal total = BigDecimal.ZERO;
		for (final BigDecimal zScore : zScores) {
			total = total.add(zScore.multiply(zScore, StatisticsEventSummaryHelper.MATH_CONTEXT), StatisticsEventSummaryHelper.MATH_CONTEXT);
		}
		return total;
	}

	/** Sum of per-z-score Fisher surprises ({@code Σ −log(2·(1−Φ(|z|)))}). */
	private static double fisherCombinedSum(
			final List<BigDecimal> zScores) {
		double sum = 0.0;
		for (final BigDecimal zScore : zScores) {
			sum += StatisticsEventSummaryHelper.surprise(zScore);
		}
		return sum;
	}

	/** Flat list of non-null per-value z-scores across every comparison, pulled from the given metric map. */
	private static List<BigDecimal> flatZScores(
			final List<StatisticsEventSummaryComparison> comparisons,
			final Function<MetricComparisonStats, Map<String, BigDecimal>> mapExtractor) {
		return comparisons == null ? List.of()
				: comparisons.stream().filter(Objects::nonNull).map(StatisticsEventSummaryComparison::getCountStats).filter(Objects::nonNull)
						.map(mapExtractor).filter(Objects::nonNull).flatMap(map -> map.values().stream()).filter(Objects::nonNull).toList();
	}

	/** Flat list of per-value ratio (share) z-scores across every comparison. */
	private static List<BigDecimal> flatRatioZScores(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.flatZScores(comparisons, MetricComparisonStats::getZScoreRatios);
	}

	/** Flat list of per-value raw-count z-scores across every comparison. */
	private static List<BigDecimal> flatValueZScores(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.flatZScores(comparisons, MetricComparisonStats::getZScoreValues);
	}

	/** Flat list of per-dimension total-volume z-scores across every comparison (one per dimension). */
	private static List<BigDecimal> flatTotalZScores(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return comparisons == null ? List.of()
				: comparisons.stream().filter(Objects::nonNull).map(StatisticsEventSummaryComparison::getCountStats).filter(Objects::nonNull)
						.map(MetricComparisonStats::getZScoreTotal).filter(Objects::nonNull).toList();
	}

	// ---- Mid-level aggregation builders ----

	/** Computes avg/stdDev for total, per-value, and per-ratio on a single MetricAggregation. */
	private static void computeMetricStats(
			final MetricAggregation metric,
			final Set<String> allKeys) {
		final int size = metric.totals.size();
		final BigDecimal[] totalValues = metric.totals.toArray(new BigDecimal[0]);
		metric.averageTotal = StatisticsEventSummaryHelper.computeAverage(totalValues);
		metric.stdDevTotal = StatisticsEventSummaryHelper.computeStdDev(totalValues, metric.averageTotal);
		for (final String key : allKeys) {
			final BigDecimal[] values = new BigDecimal[size];
			final BigDecimal[] ratios = new BigDecimal[size];
			for (int sampleIndex = 0; sampleIndex < size; sampleIndex++) {
				values[sampleIndex] = metric.allValues.get(sampleIndex).getOrDefault(key, BigDecimal.ZERO);
				final BigDecimal total = metric.totals.get(sampleIndex);
				ratios[sampleIndex] = total.compareTo(BigDecimal.ZERO) > 0
						? values[sampleIndex].divide(total, StatisticsEventSummaryHelper.MATH_CONTEXT)
						: BigDecimal.ZERO;
			}
			final BigDecimal averageValue = StatisticsEventSummaryHelper.computeAverage(values);
			metric.averageValues.put(key, averageValue);
			metric.stdDevValues.put(key, StatisticsEventSummaryHelper.computeStdDev(values, averageValue));
			final BigDecimal averageRatio = StatisticsEventSummaryHelper.computeAverage(ratios);
			metric.averageRatios.put(key, averageRatio);
			metric.stdDevRatios.put(key, StatisticsEventSummaryHelper.computeStdDev(ratios, averageRatio));
		}
	}

	/**
	 * Aggregates already-fetched per-window summaries and computes avg/stdDev for total counts, value
	 * counts, and value ratios. Each element of {@code perWindowSummaries} is the summary list for one
	 * sample window (in window order).
	 *
	 * @param  perWindowSummaries Per-window summary lists.
	 * @return                    The aggregation, or {@code null} if no window had data.
	 */
	private static PeriodAggregation aggregatePeriods(
			final List<List<StatisticsEventSummary>> perWindowSummaries) {
		final PeriodAggregation aggregation = new PeriodAggregation();
		for (final List<StatisticsEventSummary> summaries : perWindowSummaries) {
			long total = 0L;
			BigDecimal totalWeight = BigDecimal.ZERO;
			final Map<String, Long> mergedCounts = new HashMap<>();
			final Map<String, BigDecimal> mergedWeights = new HashMap<>();
			// A window with no data still contributes a zero-valued sample (it is not skipped), so every
			// sample window weighs in equally on the average and standard deviation — a sparse dimension
			// is not silently averaged over only the windows that happened to have data.
			if (summaries != null) {
				for (final StatisticsEventSummary summary : summaries) {
					total += summary.getTotalCount();
					totalWeight = totalWeight.add(summary.getTotalWeight());
					summary.getValueCounts().forEach((
							key,
							value) -> mergedCounts.merge(key, value, Long::sum));
					summary.getValueWeights().forEach((
							key,
							value) -> mergedWeights.merge(key, value, BigDecimal::add));
				}
			}
			aggregation.counts.totals.add(BigDecimal.valueOf(total));
			aggregation.counts.allValues.add(StatisticsEventSummaryHelper.toBigDecimalMap(mergedCounts));
			aggregation.weights.totals.add(totalWeight);
			aggregation.weights.allValues.add(mergedWeights);
			aggregation.allKeys.addAll(mergedCounts.keySet());
		}
		// Null only when no sample window had any data at all: this preserves the "nodata" contract and
		// avoids a zero denominator in Laplace smoothing. A mix of populated and empty windows is kept,
		// with the empty ones counted as zeros.
		if (aggregation.allKeys.isEmpty()) {
			return null;
		}
		StatisticsEventSummaryHelper.computeMetricStats(aggregation.counts, aggregation.allKeys);
		StatisticsEventSummaryHelper.computeMetricStats(aggregation.weights, aggregation.allKeys);
		return aggregation;
	}

	/** Populates reference ratios on a MetricComparisonStats from its referenceTotal/Values. */
	private static void populateReferenceRatios(
			final MetricComparisonStats stats) {
		if ((stats.getReferenceTotal() != null) && (stats.getReferenceTotal().compareTo(BigDecimal.ZERO) > 0) && (stats.getReferenceValues() != null)) {
			final Map<String, BigDecimal> ratios = new HashMap<>();
			stats.getReferenceValues().forEach((
					key,
					value) -> ratios.put(key, value.divide(stats.getReferenceTotal(), StatisticsEventSummaryHelper.MATH_CONTEXT)));
			stats.setReferenceRatios(ratios);
		}
	}

	/** Computes z-scores for a MetricComparisonStats against its MetricAggregation. */
	private static void populateZScores(
			final MetricComparisonStats stats,
			final MetricAggregation aggregation,
			final Set<String> allKeys) {
		if (stats.getReferenceTotal() == null) {
			return;
		}
		stats.setZScoreTotal(StatisticsEventSummaryHelper.computeZScore(stats.getReferenceTotal(), aggregation.averageTotal, aggregation.stdDevTotal));
		if ((stats.getReferenceValues() != null) && !stats.getReferenceValues().isEmpty()) {
			final Map<String, BigDecimal> zScoreValues = new HashMap<>();
			for (final String key : allKeys) {
				zScoreValues.put(key, StatisticsEventSummaryHelper.computeZScore(stats.getReferenceValues().getOrDefault(key, BigDecimal.ZERO),
						aggregation.averageValues.getOrDefault(key, BigDecimal.ZERO), aggregation.stdDevValues.getOrDefault(key, BigDecimal.ZERO)));
			}
			stats.setZScoreValues(zScoreValues);
		}
		if ((stats.getReferenceRatios() != null) && !stats.getReferenceRatios().isEmpty()) {
			final Map<String, BigDecimal> zScoreRatios = new HashMap<>();
			for (final String key : allKeys) {
				zScoreRatios.put(key, StatisticsEventSummaryHelper.computeZScore(stats.getReferenceRatios().getOrDefault(key, BigDecimal.ZERO),
						aggregation.averageRatios.getOrDefault(key, BigDecimal.ZERO), aggregation.stdDevRatios.getOrDefault(key, BigDecimal.ZERO)));
			}
			stats.setZScoreRatios(zScoreRatios);
		}
	}

	/**
	 * Populates a MetricComparisonStats from its aggregation: copies avg/stdDev, computes reference
	 * ratios, and computes z-scores.
	 */
	private static void populateMetricComparisonStats(
			final MetricComparisonStats stats,
			final MetricAggregation aggregation,
			final Set<String> allKeys) {
		stats.setAverageTotal(aggregation.averageTotal);
		stats.setStdDevTotal(aggregation.stdDevTotal);
		stats.setAverageValues(aggregation.averageValues);
		stats.setStdDevValues(aggregation.stdDevValues);
		stats.setAverageRatios(aggregation.averageRatios);
		stats.setStdDevRatios(aggregation.stdDevRatios);
		StatisticsEventSummaryHelper.populateReferenceRatios(stats);
		StatisticsEventSummaryHelper.populateZScores(stats, aggregation, allKeys);
	}

	// ---- Window schedule ----

	/** One sample window: an inclusive {@code [start, end]} time range. Both bounds are truncated. */
	public record Window(
			LocalDateTime start,
			LocalDateTime end) {
	}

	/**
	 * The fully-resolved (already-truncated) sampling schedule for a query: the reference window plus
	 * the sample windows to aggregate over. For drift comparison the samples are the historical windows
	 * (reference excluded); for distribution/probability they are the reference-inclusive windows. The
	 * service builds this in its public methods so the {@code ...Cacheable} seams receive it ready-made
	 * and never truncate.
	 */
	public record WindowSchedule(
			Window reference,
			List<Window> samples) {

		/** Earliest start across the reference and every sample window — the bulk fetch's lower bound. */
		public LocalDateTime overallStart() {
			return Stream.concat(Stream.of(this.reference), this.samples.stream()).map(Window::start).min(Comparator.naturalOrder())
					.orElse(this.reference.start());
		}

		/** Latest end across the reference and every sample window — the bulk fetch's upper bound. */
		public LocalDateTime overallEnd() {
			return Stream.concat(Stream.of(this.reference), this.samples.stream()).map(Window::end).max(Comparator.naturalOrder())
					.orElse(this.reference.end());
		}
	}

	/**
	 * Builds the schedule whose samples are the historical windows, EXCLUDING the reference window
	 * (steps {@code 1..steps} back). Used by drift comparison, which contrasts the reference against its
	 * own past. {@code referenceStart} must already be truncated; the reference end and every sample
	 * bound are truncated here.
	 */
	public static WindowSchedule historicalSchedule(
			final LocalDateTime referenceStart,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps,
			final long truncationMinutes) {
		final LocalDateTime referenceEnd = StatisticsEvent.truncateDateTime(referenceStart.plus(windowSize, windowUnit), truncationMinutes);
		final List<Window> samples = new ArrayList<>();
		for (int stepIndex = 1; stepIndex <= steps; stepIndex++) {
			samples.add(new Window(StatisticsEvent.truncateDateTime(referenceStart.minus(stepIndex, stepUnit), truncationMinutes),
					StatisticsEvent.truncateDateTime(referenceEnd.minus(stepIndex, stepUnit), truncationMinutes)));
		}
		return new WindowSchedule(new Window(referenceStart, referenceEnd), samples);
	}

	// ---- Public validation ----

	/**
	 * Validates that the total spanned window (reference window plus the stepped-back samples) does
	 * not exceed the 6-month maximum.
	 *
	 * @param  windowUnit        Window unit.
	 * @param  windowSize        Window size.
	 * @param  stepUnit          Step unit.
	 * @param  steps             Number of steps.
	 * @throws BusinessException If the total window is too large.
	 */
	public static void validateTotalWindow(
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final long totalDays = StatisticsEventSummaryHelper.toDays(windowSize, windowUnit) + StatisticsEventSummaryHelper.toDays(steps, stepUnit);
		if (totalDays > StatisticsEventSummaryHelper.MAX_TOTAL_WINDOW.toDays()) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.window.too.large"), HttpStatus.BAD_REQUEST.value());
		}
	}

	// ---- Public compute entry points ----

	/**
	 * Builds the drift comparison of a reference window against its historical sample windows from
	 * already-fetched summaries.
	 *
	 * @param  referenceSummaries Summaries of the reference window.
	 * @param  perWindowSummaries Per-window summary lists for the historical samples (excluding the
	 *                                reference window).
	 * @param  context            Context.
	 * @param  dimensionName      Dimension name.
	 * @param  referenceDateTime  Start of the (already truncated) reference window.
	 * @param  windowUnit         Window unit.
	 * @param  windowSize         Window size.
	 * @param  stepUnit           Step unit.
	 * @param  steps              Number of historical samples.
	 * @return                    The comparison.
	 * @throws BusinessException  If no historical period had data.
	 */
	public static StatisticsEventSummaryComparison computeComparison(
			final List<StatisticsEventSummary> referenceSummaries,
			final List<List<StatisticsEventSummary>> perWindowSummaries,
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final PeriodAggregation aggregation = StatisticsEventSummaryHelper.aggregatePeriods(perWindowSummaries);
		if (aggregation == null) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.comparison.nodata"), HttpStatus.NOT_FOUND.value());
		}

		final StatisticsEventSummaryComparison comparison = new StatisticsEventSummaryComparison();
		comparison.setContext(context);
		comparison.setDimensionName(dimensionName);
		comparison.setReferenceDateTime(referenceDateTime);
		comparison.setWindowUnit(windowUnit);
		comparison.setWindowSize(windowSize);
		comparison.setStepUnit(stepUnit);
		comparison.setSteps(steps);
		comparison.setSampleSize(aggregation.counts.totals.size());

		final MetricComparisonStats countStats = comparison.getCountStats();
		final MetricComparisonStats weightStats = comparison.getWeightStats();
		if ((referenceSummaries != null) && !referenceSummaries.isEmpty()) {
			BigDecimal referenceTotalCount = BigDecimal.ZERO;
			BigDecimal referenceTotalWeight = BigDecimal.ZERO;
			final Map<String, BigDecimal> referenceValueCounts = new HashMap<>();
			final Map<String, BigDecimal> referenceValueWeights = new HashMap<>();
			for (final StatisticsEventSummary summary : referenceSummaries) {
				referenceTotalCount = referenceTotalCount.add(BigDecimal.valueOf(summary.getTotalCount()));
				referenceTotalWeight = referenceTotalWeight.add(summary.getTotalWeight());
				summary.getValueCounts().forEach((
						key,
						value) -> referenceValueCounts.merge(key, BigDecimal.valueOf(value), BigDecimal::add));
				summary.getValueWeights().forEach((
						key,
						value) -> referenceValueWeights.merge(key, value, BigDecimal::add));
			}
			countStats.setReferenceTotal(referenceTotalCount);
			countStats.setReferenceValues(referenceValueCounts);
			weightStats.setReferenceTotal(referenceTotalWeight);
			weightStats.setReferenceValues(referenceValueWeights);
		}

		StatisticsEventSummaryHelper.populateMetricComparisonStats(countStats, aggregation.counts, aggregation.allKeys);
		StatisticsEventSummaryHelper.populateMetricComparisonStats(weightStats, aggregation.weights, aggregation.allKeys);

		return comparison;
	}

	/**
	 * Builds the drift comparison from the Postgres-computed sufficient statistics (the
	 * {@link StatisticsEventSummaryRepositoryCustom#compareStatistics} rows). Derives averages, standard
	 * deviations, ratios and z-scores in {@code BigDecimal} from the per-value sums — the same math as
	 * {@link #computeComparison}, just fed by the database's reduction instead of per-window summaries.
	 * Variance is {@code Σx²/n − mean²} (vs. the iterative form in {@link #computeComparison}); the two
	 * agree to within rounding. A value present only in the samples is z-scored against a zero
	 * reference; a value present only in the reference appears only in the reference maps.
	 *
	 * @param  statistics        The sufficient-statistic rows (per metric × value, plus per-metric totals).
	 * @param  sampleWindowCount The number of sample windows (the divisor for the averages).
	 * @param  context           Context.
	 * @param  dimensionName     Dimension name.
	 * @param  referenceDateTime Start of the (already truncated) reference window.
	 * @param  windowUnit        Window unit.
	 * @param  windowSize        Window size.
	 * @param  stepUnit          Step unit.
	 * @param  steps             Number of historical samples.
	 * @return                   The comparison.
	 * @throws BusinessException If no sample window had any data.
	 */
	public static StatisticsEventSummaryComparison assembleComparison(
			final List<StatisticsEventSummaryRepositoryCustom.ComparisonStatistic> statistics,
			final int sampleWindowCount,
			final String context,
			final String dimensionName,
			final LocalDateTime referenceDateTime,
			final ChronoUnit windowUnit,
			final Integer windowSize,
			final ChronoUnit stepUnit,
			final Integer steps) throws BusinessException {
		final StatisticsEventSummaryComparison comparison = new StatisticsEventSummaryComparison();
		comparison.setContext(context);
		comparison.setDimensionName(dimensionName);
		comparison.setReferenceDateTime(referenceDateTime);
		comparison.setWindowUnit(windowUnit);
		comparison.setWindowSize(windowSize);
		comparison.setStepUnit(stepUnit);
		comparison.setSteps(steps);
		comparison.setSampleSize(sampleWindowCount);
		final boolean hasCountSample = StatisticsEventSummaryHelper.populateMetricFromStatistics(comparison.getCountStats(), "count", statistics,
				sampleWindowCount);
		StatisticsEventSummaryHelper.populateMetricFromStatistics(comparison.getWeightStats(), "weight", statistics, sampleWindowCount);
		if (!hasCountSample) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.comparison.nodata"), HttpStatus.NOT_FOUND.value());
		}
		return comparison;
	}

	/**
	 * Populates one metric's comparison stats from the sufficient-statistic rows. Returns whether the
	 * metric had any sample-window data (used to enforce the {@code nodata} contract on counts).
	 */
	private static boolean populateMetricFromStatistics(
			final MetricComparisonStats stats,
			final String metric,
			final List<StatisticsEventSummaryRepositoryCustom.ComparisonStatistic> statistics,
			final int sampleWindowCount) {
		final BigDecimal windowCount = BigDecimal.valueOf(sampleWindowCount);
		BigDecimal referenceTotal = null;
		for (final StatisticsEventSummaryRepositoryCustom.ComparisonStatistic statistic : statistics) {
			if (metric.equals(statistic.metric()) && (statistic.dimensionValue() == null)) {
				final BigDecimal averageTotal = (statistic.sampleSum() == null) ? BigDecimal.ZERO
						: statistic.sampleSum().divide(windowCount, StatisticsEventSummaryHelper.MATH_CONTEXT);
				stats.setAverageTotal(averageTotal);
				stats.setStdDevTotal(StatisticsEventSummaryHelper.stdDevFromSums(statistic.sampleSum(), statistic.sampleSumSquare(), sampleWindowCount));
				if ((statistic.referenceValue() != null) && (statistic.referenceValue().signum() > 0)) {
					referenceTotal = statistic.referenceValue();
					stats.setReferenceTotal(referenceTotal);
					stats.setZScoreTotal(StatisticsEventSummaryHelper.computeZScore(referenceTotal, averageTotal, stats.getStdDevTotal()));
				}
			}
		}
		final boolean hasReference = referenceTotal != null;
		final Map<String, BigDecimal> averageValues = new HashMap<>();
		final Map<String, BigDecimal> stdDevValues = new HashMap<>();
		final Map<String, BigDecimal> averageRatios = new HashMap<>();
		final Map<String, BigDecimal> stdDevRatios = new HashMap<>();
		final Map<String, BigDecimal> referenceValues = new HashMap<>();
		final Map<String, BigDecimal> referenceRatios = new HashMap<>();
		final Map<String, BigDecimal> zScoreValues = new HashMap<>();
		final Map<String, BigDecimal> zScoreRatios = new HashMap<>();
		boolean hasSample = false;
		for (final StatisticsEventSummaryRepositoryCustom.ComparisonStatistic statistic : statistics) {
			if (!metric.equals(statistic.metric()) || (statistic.dimensionValue() == null)) {
				continue;
			}
			final String value = statistic.dimensionValue();
			if (statistic.sampleSum() != null) {
				hasSample = true;
				final BigDecimal average = statistic.sampleSum().divide(windowCount, StatisticsEventSummaryHelper.MATH_CONTEXT);
				final BigDecimal stdDev = StatisticsEventSummaryHelper.stdDevFromSums(statistic.sampleSum(), statistic.sampleSumSquare(), sampleWindowCount);
				final BigDecimal averageRatio = statistic.sampleRatioSum().divide(windowCount, StatisticsEventSummaryHelper.MATH_CONTEXT);
				final BigDecimal stdDevRatio = StatisticsEventSummaryHelper.stdDevFromSums(statistic.sampleRatioSum(), statistic.sampleRatioSumSquare(),
						sampleWindowCount);
				averageValues.put(value, average);
				stdDevValues.put(value, stdDev);
				averageRatios.put(value, averageRatio);
				stdDevRatios.put(value, stdDevRatio);
				if (hasReference) {
					final BigDecimal referenceValue = (statistic.referenceValue() == null) ? BigDecimal.ZERO : statistic.referenceValue();
					final BigDecimal referenceRatio = (statistic.referenceValue() == null) ? BigDecimal.ZERO
							: statistic.referenceValue().divide(referenceTotal, StatisticsEventSummaryHelper.MATH_CONTEXT);
					zScoreValues.put(value, StatisticsEventSummaryHelper.computeZScore(referenceValue, average, stdDev));
					zScoreRatios.put(value, StatisticsEventSummaryHelper.computeZScore(referenceRatio, averageRatio, stdDevRatio));
				}
			}
			if (hasReference && (statistic.referenceValue() != null)) {
				referenceValues.put(value, statistic.referenceValue());
				referenceRatios.put(value, statistic.referenceValue().divide(referenceTotal, StatisticsEventSummaryHelper.MATH_CONTEXT));
			}
		}
		stats.setAverageValues(averageValues);
		stats.setStdDevValues(stdDevValues);
		stats.setAverageRatios(averageRatios);
		stats.setStdDevRatios(stdDevRatios);
		if (hasReference) {
			stats.setReferenceValues(referenceValues);
			stats.setReferenceRatios(referenceRatios);
			stats.setZScoreValues(zScoreValues);
			stats.setZScoreRatios(zScoreRatios);
		}
		return hasSample;
	}

	/**
	 * Population standard deviation from the sum and sum-of-squares over {@code count} samples:
	 * {@code sqrt(Σx²/n − (Σx/n)²)}, clamped at zero. Mirrors {@link #computeStdDev}'s final {@code sqrt}
	 * step (taken in {@code double}; the variance is exact {@code BigDecimal}).
	 */
	private static BigDecimal stdDevFromSums(
			final BigDecimal sum,
			final BigDecimal sumSquare,
			final int count) {
		if ((sum == null) || (sumSquare == null) || (count == 0)) {
			return BigDecimal.ZERO;
		}
		// Centre the sum of squares in EXACT arithmetic (no MathContext): n·Σx² − (Σx)² cancels to
		// exactly zero when every sample is equal (e.g. a single window) and is ≥ 0 by Cauchy-Schwarz.
		// Doing the subtraction in DECIMAL64 instead leaves a rounding residual that an irrational
		// ratio turns into a tiny non-zero std-dev — and a divide-by-near-zero z-score blow-up.
		final BigDecimal centeredSumSquare = sumSquare.multiply(BigDecimal.valueOf(count)).subtract(sum.multiply(sum));
		if (centeredSumSquare.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		final double variance = centeredSumSquare.doubleValue() / ((double) count * (double) count);
		return BigDecimal.valueOf(Math.sqrt(Math.max(variance, 0.0))).round(StatisticsEventSummaryHelper.MATH_CONTEXT);
	}

	/**
	 * Derives one value's single-dimension probability from a period's aggregated summary: the raw
	 * ratio {@code count/total} and the Laplace-smoothed {@code (count + α) / (total + α·V)} (an unseen
	 * value never collapses the smoothed probability to zero). A pure reduction over the merged summary
	 * — the {@code findByPeriod} return feeds straight in.
	 *
	 * @param  summary         The period's aggregated summary.
	 * @param  dimensionValue  The value to evaluate.
	 * @param  smoothingFactor Additive (Laplace) smoothing factor.
	 * @return                 The single-dimension probability.
	 */
	public static StatisticsEventSingleDimensionProbability singleDimensionProbability(
			final StatisticsEventSummary summary,
			final String dimensionValue,
			final double smoothingFactor) {
		final Map<String, Long> valueCounts = summary.getValueCounts();
		final long total = summary.getTotalCount();
		final long valueCount = valueCounts.getOrDefault(dimensionValue, 0L);
		final int distinctValueCount = valueCounts.size();
		final StatisticsEventSingleDimensionProbability probability = new StatisticsEventSingleDimensionProbability();
		probability.setContext(summary.getContext());
		probability.setDimensionName(summary.getDimensionName());
		probability.setDimensionValue(dimensionValue);
		probability.setDistinctValueCount(distinctValueCount);
		probability.setProbability(total > 0L
				? BigDecimal.valueOf(valueCount).divide(BigDecimal.valueOf(total), StatisticsEventSummaryHelper.MATH_CONTEXT)
				: BigDecimal.ZERO);
		probability.setSmoothedProbability(distinctValueCount > 0
				? StatisticsEventSummaryHelper.laplaceSmoothedProbability(BigDecimal.valueOf(valueCount), BigDecimal.valueOf(total), distinctValueCount,
						smoothingFactor)
				: BigDecimal.ZERO);
		return probability;
	}

	/**
	 * Builds the naive (independence) multi-dimension joint {@code P(A ∩ B) = P(A) × P(B)} from one
	 * period summary per dimension plus the value to evaluate for each (positionally paired): derives
	 * each dimension's probability via {@link #singleDimensionProbability}, then multiplies them. A pure
	 * reduction over the merged summaries — the per-dimension {@code findByPeriod} returns feed straight
	 * in; context is taken from the summaries.
	 *
	 * @param  summaries         Per-dimension merged summaries (non-empty; aligned with {@code dimensionValues}).
	 * @param  dimensionValues   The value to evaluate for each dimension (aligned with {@code summaries}).
	 * @return                   The naive multi-dimension probability.
	 * @throws BusinessException If {@code summaries} is null/empty or not positionally aligned with
	 *                               {@code dimensionValues}.
	 */
	public static StatisticsEventNaiveMultiDimensionProbability naiveMultiDimensionProbability(
			final List<StatisticsEventSummary> summaries,
			final List<String> dimensionValues) throws BusinessException {
		if ((summaries == null) || summaries.isEmpty()) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.probability.nodimensions"), HttpStatus.BAD_REQUEST.value());
		}
		if ((dimensionValues == null) || (dimensionValues.size() != summaries.size())) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.probability.misaligned"), HttpStatus.BAD_REQUEST.value());
		}
		final List<StatisticsEventSingleDimensionProbability> individualProbabilities = new ArrayList<>();
		BigDecimal jointProbability = BigDecimal.ONE;
		BigDecimal jointSmoothedProbability = BigDecimal.ONE;
		double jointSmoothedLogProbability = 0.0;
		for (int index = 0; index < summaries.size(); index++) {
			final StatisticsEventSingleDimensionProbability individual = StatisticsEventSummaryHelper.singleDimensionProbability(summaries.get(index),
					dimensionValues.get(index), StatisticsEventSummaryHelper.DEFAULT_SMOOTHING_FACTOR);
			individualProbabilities.add(individual);
			jointProbability = jointProbability.multiply(individual.getProbability(), StatisticsEventSummaryHelper.MATH_CONTEXT);
			jointSmoothedProbability = jointSmoothedProbability.multiply(individual.getSmoothedProbability(), StatisticsEventSummaryHelper.MATH_CONTEXT);
			jointSmoothedLogProbability += Math.log(individual.getSmoothedProbability().doubleValue());
		}
		final StatisticsEventNaiveMultiDimensionProbability result = new StatisticsEventNaiveMultiDimensionProbability();
		result.setContext(individualProbabilities.get(0).getContext());
		result.setJointProbability(jointProbability);
		result.setJointSmoothedProbability(jointSmoothedProbability);
		result.setJointSmoothedLogProbability(BigDecimal.valueOf(jointSmoothedLogProbability).setScale(6, RoundingMode.HALF_UP));
		result.setIndividualProbabilities(individualProbabilities);
		return result;
	}

	// ---- Cross-dimension z-score reductions (private): shared by the public ratio / value families. ----

	private static BigDecimal maxAbs(
			final List<BigDecimal> zScores) {
		return zScores.stream().map(BigDecimal::abs).max(Comparator.naturalOrder()).orElse(null);
	}

	private static BigDecimal minAbs(
			final List<BigDecimal> zScores) {
		return zScores.stream().map(BigDecimal::abs).min(Comparator.naturalOrder()).orElse(null);
	}

	private static BigDecimal meanAbs(
			final List<BigDecimal> zScores) {
		return zScores.isEmpty() ? null
				: zScores.stream().map(BigDecimal::abs).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(zScores.size()), 6,
						RoundingMode.HALF_UP);
	}

	private static BigDecimal countAbsAbove(
			final List<BigDecimal> zScores,
			final double threshold) {
		final BigDecimal thresholdValue = BigDecimal.valueOf(threshold);
		return zScores.isEmpty() ? null
				: BigDecimal.valueOf(zScores.stream().filter(zScore -> zScore.abs().compareTo(thresholdValue) > 0).count());
	}

	private static BigDecimal countAbove(
			final List<BigDecimal> zScores,
			final double threshold) {
		final BigDecimal thresholdValue = BigDecimal.valueOf(threshold);
		return zScores.isEmpty() ? null
				: BigDecimal.valueOf(zScores.stream().filter(zScore -> zScore.compareTo(thresholdValue) > 0).count());
	}

	private static BigDecimal countBelow(
			final List<BigDecimal> zScores,
			final double threshold) {
		final BigDecimal thresholdValue = BigDecimal.valueOf(-threshold);
		return zScores.isEmpty() ? null
				: BigDecimal.valueOf(zScores.stream().filter(zScore -> zScore.compareTo(thresholdValue) < 0).count());
	}

	private static BigDecimal rootSumSquare(
			final List<BigDecimal> zScores) {
		return zScores.isEmpty() ? null
				: BigDecimal.valueOf(Math.sqrt(StatisticsEventSummaryHelper.sumOfSquares(zScores).doubleValue())).setScale(6, RoundingMode.HALF_UP);
	}

	private static BigDecimal standardizedChiSquare(
			final List<BigDecimal> zScores) {
		final int count = zScores.size();
		return count == 0 ? null
				: BigDecimal.valueOf((StatisticsEventSummaryHelper.sumOfSquares(zScores).doubleValue() - count) / Math.sqrt(2.0 * count)).setScale(6,
						RoundingMode.HALF_UP);
	}

	private static BigDecimal fisherCombined(
			final List<BigDecimal> zScores) {
		return zScores.isEmpty() ? null
				: BigDecimal.valueOf(StatisticsEventSummaryHelper.fisherCombinedSum(zScores)).setScale(6, RoundingMode.HALF_UP);
	}

	private static BigDecimal standardizedFisher(
			final List<BigDecimal> zScores) {
		final int count = zScores.size();
		return count == 0 ? null
				: BigDecimal.valueOf((StatisticsEventSummaryHelper.fisherCombinedSum(zScores) - count) / Math.sqrt(count)).setScale(6, RoundingMode.HALF_UP);
	}

	private static BigDecimal maxSigned(
			final List<BigDecimal> zScores) {
		return zScores.stream().max(Comparator.naturalOrder()).orElse(null);
	}

	private static BigDecimal minSigned(
			final List<BigDecimal> zScores) {
		return zScores.stream().min(Comparator.naturalOrder()).orElse(null);
	}

	private static BigDecimal meanSigned(
			final List<BigDecimal> zScores) {
		return zScores.isEmpty() ? null
				: zScores.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(zScores.size()), 6, RoundingMode.HALF_UP);
	}

	// ---- Public ratio (value/total share) z-score aggregators. Pure reductions, no DB access. ----

	/** Maximum {@code |z|} across every dimension's value-level ratio z-scores. */
	public static BigDecimal maxAbsRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.maxAbs(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/** Minimum {@code |z|} across every dimension's value-level ratio z-scores. */
	public static BigDecimal minAbsRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.minAbs(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/** Mean {@code |z|} across every dimension's value-level ratio z-scores. */
	public static BigDecimal meanAbsRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanAbs(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/**
	 * Count of dimension-value ratio z-scores whose {@code |z|} strictly exceeds {@code threshold}, as
	 * {@code BigDecimal} for uniformity with the sibling aggregators.
	 */
	public static BigDecimal countAbsRatioZScoreAbove(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countAbsAbove(StatisticsEventSummaryHelper.flatRatioZScores(comparisons), threshold);
	}

	/**
	 * Raw {@code sqrt(Σ z²)} over the ratio z-scores — the vector's Euclidean length ("drift energy").
	 * Grows with the number of z-scores, so prefer {@link #standardizedChiSquareRatioZScore} across
	 * populations.
	 */
	public static BigDecimal rootSumSquareRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.rootSumSquare(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/**
	 * Standardized chi-square {@code (Σ z² − k)/sqrt(2k)} over the ratio z-scores. Under independent
	 * standard normals {@code Σ z² ~ χ²(k)}, so mean 0 / variance 1 — comparable across populations.
	 */
	public static BigDecimal standardizedChiSquareRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.standardizedChiSquare(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/**
	 * Raw Fisher combined surprise {@code Σ −log(2·(1−Φ(|z|)))} over the ratio z-scores. Grows with the
	 * number of z-scores, so prefer {@link #standardizedFisherRatioZScore} across populations.
	 */
	public static BigDecimal fisherCombinedRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.fisherCombined(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/**
	 * Standardized Fisher {@code (S − k)/sqrt(k)} over the ratio z-scores — mean 0 / variance 1 across
	 * populations.
	 */
	public static BigDecimal standardizedFisherRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.standardizedFisher(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	// ---- Signed ratio variants: preserve drift direction (the |z| aggregators above discard it). ----

	/** Maximum signed ratio z-score — the strongest upward shift in a value's share (positive). */
	public static BigDecimal maxSignedRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.maxSigned(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/** Minimum signed ratio z-score — the strongest downward shift in a value's share (most negative). */
	public static BigDecimal minSignedRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.minSigned(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/** Mean signed ratio z-score — net drift direction (per-value deviations partly cancel within a dimension). */
	public static BigDecimal meanSignedRatioZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanSigned(StatisticsEventSummaryHelper.flatRatioZScores(comparisons));
	}

	/** Count of dimension-value ratio z-scores strictly above {@code +threshold} (directional over-representation). */
	public static BigDecimal countRatioZScoreAbove(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countAbove(StatisticsEventSummaryHelper.flatRatioZScores(comparisons), threshold);
	}

	/** Count of dimension-value ratio z-scores strictly below {@code −threshold} (directional under-representation). */
	public static BigDecimal countRatioZScoreBelow(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countBelow(StatisticsEventSummaryHelper.flatRatioZScores(comparisons), threshold);
	}

	// ---- Public value (per-value raw count) z-score aggregators: same reductions over count z. ----

	/** Maximum {@code |z|} over per-value raw-count z-scores. See {@link #maxAbsRatioZScore}. */
	public static BigDecimal maxAbsValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.maxAbs(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Minimum {@code |z|} over per-value raw-count z-scores. See {@link #minAbsRatioZScore}. */
	public static BigDecimal minAbsValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.minAbs(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Mean {@code |z|} over per-value raw-count z-scores. See {@link #meanAbsRatioZScore}. */
	public static BigDecimal meanAbsValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanAbs(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Count of per-value raw-count z-scores with {@code |z|} above {@code threshold}. See {@link #countAbsRatioZScoreAbove}. */
	public static BigDecimal countAbsValueZScoreAbove(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countAbsAbove(StatisticsEventSummaryHelper.flatValueZScores(comparisons), threshold);
	}

	/** {@code sqrt(Σ z²)} over per-value raw-count z-scores. See {@link #rootSumSquareRatioZScore}. */
	public static BigDecimal rootSumSquareValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.rootSumSquare(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Standardized chi-square over per-value raw-count z-scores. See {@link #standardizedChiSquareRatioZScore}. */
	public static BigDecimal standardizedChiSquareValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.standardizedChiSquare(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Raw Fisher combined surprise over per-value raw-count z-scores. See {@link #fisherCombinedRatioZScore}. */
	public static BigDecimal fisherCombinedValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.fisherCombined(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Standardized Fisher over per-value raw-count z-scores. See {@link #standardizedFisherRatioZScore}. */
	public static BigDecimal standardizedFisherValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.standardizedFisher(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Maximum signed per-value raw-count z-score — the strongest count surge. See {@link #maxSignedRatioZScore}. */
	public static BigDecimal maxSignedValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.maxSigned(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Minimum signed per-value raw-count z-score — the strongest count drop. See {@link #minSignedRatioZScore}. */
	public static BigDecimal minSignedValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.minSigned(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Mean signed per-value raw-count z-score. See {@link #meanSignedRatioZScore}. */
	public static BigDecimal meanSignedValueZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanSigned(StatisticsEventSummaryHelper.flatValueZScores(comparisons));
	}

	/** Count of per-value raw-count z-scores strictly above {@code +threshold}. See {@link #countRatioZScoreAbove}. */
	public static BigDecimal countValueZScoreAbove(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countAbove(StatisticsEventSummaryHelper.flatValueZScores(comparisons), threshold);
	}

	/** Count of per-value raw-count z-scores strictly below {@code −threshold}. See {@link #countRatioZScoreBelow}. */
	public static BigDecimal countValueZScoreBelow(
			final List<StatisticsEventSummaryComparison> comparisons,
			final double threshold) {
		return StatisticsEventSummaryHelper.countBelow(StatisticsEventSummaryHelper.flatValueZScores(comparisons), threshold);
	}

	// ---- Public total (overall volume) z-score aggregators. zScoreTotal is the dimension's total-volume
	// z; within a context every dimension emits one event per loan, so it is uniform across dimensions —
	// a single volume-drift signal (the mean just collapses the equal per-dimension values). ----

	/** Mean signed total-volume z-score — overall volume drift, signed (surge positive, drop negative). */
	public static BigDecimal meanSignedTotalZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanSigned(StatisticsEventSummaryHelper.flatTotalZScores(comparisons));
	}

	/** Mean {@code |z|} total-volume z-score — overall volume drift magnitude. */
	public static BigDecimal meanAbsTotalZScore(
			final List<StatisticsEventSummaryComparison> comparisons) {
		return StatisticsEventSummaryHelper.meanAbs(StatisticsEventSummaryHelper.flatTotalZScores(comparisons));
	}
}
