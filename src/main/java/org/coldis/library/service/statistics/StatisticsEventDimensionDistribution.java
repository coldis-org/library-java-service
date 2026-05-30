package org.coldis.library.service.statistics;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Value-independent per-dimension distribution over a sampled period: the pooled counts and the
 * per-value average/standard-deviation of count and ratio across the sampled windows. Holds
 * everything needed to derive any single value's probability ({@link
 * StatisticsEventSummaryHelper#singleDimensionProbability}) without re-reading the underlying
 * summaries — so it is the cacheable, reusable unit shared by every applicant evaluated against the
 * same {@code (context, dimension, reference, window)}.
 */
public class StatisticsEventDimensionDistribution implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = 7184926305471829364L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Reference date time (start of the included reference window). */
  private LocalDateTime referenceDateTime;

  /** Window unit. */
  private ChronoUnit windowUnit;

  /** Window size. */
  private Integer windowSize;

  /** Step unit. */
  private ChronoUnit stepUnit;

  /** Number of sampled periods. */
  private Integer steps;

  /** Number of sampled periods that had data. */
  private Integer sampleSize;

  /** Number of distinct values seen across the sampled periods (V, for Laplace smoothing). */
  private Integer distinctValueCount;

  /** Pooled total count across every sampled period. */
  private BigDecimal pooledTotal = BigDecimal.ZERO;

  /** Pooled count per value across every sampled period. */
  private Map<String, BigDecimal> pooledValueCounts = new HashMap<>();

  /** Average per-period ratio (value / total) per value — the unsmoothed probability. */
  private Map<String, BigDecimal> averageRatios = new HashMap<>();

  /** Standard deviation of the per-period ratio per value. */
  private Map<String, BigDecimal> stdDevRatios = new HashMap<>();

  /** Average per-period count per value. */
  private Map<String, BigDecimal> averageValues = new HashMap<>();

  /** Standard deviation of the per-period count per value. */
  private Map<String, BigDecimal> stdDevValues = new HashMap<>();

  /** @return the context. */
  public String getContext() {
    return this.context;
  }

  /** @param context the context. */
  public void setContext(final String context) {
    this.context = context;
  }

  /** @return the dimension name. */
  public String getDimensionName() {
    return this.dimensionName;
  }

  /** @param dimensionName the dimension name. */
  public void setDimensionName(final String dimensionName) {
    this.dimensionName = dimensionName;
  }

  /** @return the reference date time. */
  public LocalDateTime getReferenceDateTime() {
    return this.referenceDateTime;
  }

  /** @param referenceDateTime the reference date time. */
  public void setReferenceDateTime(final LocalDateTime referenceDateTime) {
    this.referenceDateTime = referenceDateTime;
  }

  /** @return the window unit. */
  public ChronoUnit getWindowUnit() {
    return this.windowUnit;
  }

  /** @param windowUnit the window unit. */
  public void setWindowUnit(final ChronoUnit windowUnit) {
    this.windowUnit = windowUnit;
  }

  /** @return the window size. */
  public Integer getWindowSize() {
    return this.windowSize;
  }

  /** @param windowSize the window size. */
  public void setWindowSize(final Integer windowSize) {
    this.windowSize = windowSize;
  }

  /** @return the step unit. */
  public ChronoUnit getStepUnit() {
    return this.stepUnit;
  }

  /** @param stepUnit the step unit. */
  public void setStepUnit(final ChronoUnit stepUnit) {
    this.stepUnit = stepUnit;
  }

  /** @return the number of sampled periods. */
  public Integer getSteps() {
    return this.steps;
  }

  /** @param steps the number of sampled periods. */
  public void setSteps(final Integer steps) {
    this.steps = steps;
  }

  /** @return the number of sampled periods that had data. */
  public Integer getSampleSize() {
    return this.sampleSize;
  }

  /** @param sampleSize the number of sampled periods that had data. */
  public void setSampleSize(final Integer sampleSize) {
    this.sampleSize = sampleSize;
  }

  /** @return the number of distinct values. */
  public Integer getDistinctValueCount() {
    return this.distinctValueCount;
  }

  /** @param distinctValueCount the number of distinct values. */
  public void setDistinctValueCount(final Integer distinctValueCount) {
    this.distinctValueCount = distinctValueCount;
  }

  /** @return the pooled total count. */
  public BigDecimal getPooledTotal() {
    return this.pooledTotal;
  }

  /** @param pooledTotal the pooled total count. */
  public void setPooledTotal(final BigDecimal pooledTotal) {
    this.pooledTotal = pooledTotal;
  }

  /** @return the pooled count per value. */
  public Map<String, BigDecimal> getPooledValueCounts() {
    return this.pooledValueCounts;
  }

  /** @param pooledValueCounts the pooled count per value. */
  public void setPooledValueCounts(final Map<String, BigDecimal> pooledValueCounts) {
    this.pooledValueCounts = pooledValueCounts;
  }

  /** @return the average ratio per value. */
  public Map<String, BigDecimal> getAverageRatios() {
    return this.averageRatios;
  }

  /** @param averageRatios the average ratio per value. */
  public void setAverageRatios(final Map<String, BigDecimal> averageRatios) {
    this.averageRatios = averageRatios;
  }

  /** @return the std-dev of the ratio per value. */
  public Map<String, BigDecimal> getStdDevRatios() {
    return this.stdDevRatios;
  }

  /** @param stdDevRatios the std-dev of the ratio per value. */
  public void setStdDevRatios(final Map<String, BigDecimal> stdDevRatios) {
    this.stdDevRatios = stdDevRatios;
  }

  /** @return the average count per value. */
  public Map<String, BigDecimal> getAverageValues() {
    return this.averageValues;
  }

  /** @param averageValues the average count per value. */
  public void setAverageValues(final Map<String, BigDecimal> averageValues) {
    this.averageValues = averageValues;
  }

  /** @return the std-dev of the count per value. */
  public Map<String, BigDecimal> getStdDevValues() {
    return this.stdDevValues;
  }

  /** @param stdDevValues the std-dev of the count per value. */
  public void setStdDevValues(final Map<String, BigDecimal> stdDevValues) {
    this.stdDevValues = stdDevValues;
  }
}
