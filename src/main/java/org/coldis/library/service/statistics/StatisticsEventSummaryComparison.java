package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Statistics event summary comparison. Compares a reference window against historical periods,
 * providing moving averages, standard deviations, value ratios, and z-scores.
 */
public class StatisticsEventSummaryComparison implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = -3918475620384719562L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Reference date time (start of the target window). */
  private LocalDateTime referenceDateTime;

  /** Window unit (defines window size). */
  private ChronoUnit windowUnit;

  /** Window size (number of window units). */
  private Integer windowSize;

  /** Step unit (defines how far back each sample is). */
  private ChronoUnit stepUnit;

  /** Number of steps (past periods sampled). */
  private Integer steps;

  /** Number of past periods that actually had data. */
  private Integer sampleSize;

  /** Average total count across past periods. */
  private BigDecimal averageTotalCount;

  /** Standard deviation of total count across past periods. */
  private BigDecimal stdDevTotalCount;

  /** Average value counts across past periods. */
  private Map<String, BigDecimal> averageValueCounts;

  /** Standard deviation of value counts across past periods. */
  private Map<String, BigDecimal> stdDevValueCounts;

  /** Average value ratios (value count / total count) across past periods. */
  private Map<String, BigDecimal> averageValueRatios;

  /** Standard deviation of value ratios across past periods. */
  private Map<String, BigDecimal> stdDevValueRatios;

  /** Reference window total count. */
  private Long referenceTotalCount;

  /** Reference window value counts. */
  private Map<String, Long> referenceValueCounts;

  /** Reference window value ratios. */
  private Map<String, BigDecimal> referenceValueRatios;

  /** Z-score of reference total count (deviations from mean). */
  private BigDecimal zScoreTotalCount;

  /** Z-scores of reference value counts (deviations from mean per value). */
  private Map<String, BigDecimal> zScoreValueCounts;

  /** Z-scores of reference value ratios (deviations from mean per value). */
  private Map<String, BigDecimal> zScoreValueRatios;

  /** No arguments constructor. */
  public StatisticsEventSummaryComparison() {}

  /**
   * Gets the context.
   *
   * @return The context.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getContext() {
    return this.context;
  }

  /**
   * Sets the context.
   *
   * @param context New context.
   */
  public void setContext(final String context) {
    this.context = context;
  }

  /**
   * Gets the dimension name.
   *
   * @return The dimension name.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getDimensionName() {
    return this.dimensionName;
  }

  /**
   * Sets the dimension name.
   *
   * @param dimensionName New dimension name.
   */
  public void setDimensionName(final String dimensionName) {
    this.dimensionName = dimensionName;
  }

  /**
   * Gets the reference date time.
   *
   * @return The reference date time.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public LocalDateTime getReferenceDateTime() {
    return this.referenceDateTime;
  }

  /**
   * Sets the reference date time.
   *
   * @param referenceDateTime New reference date time.
   */
  public void setReferenceDateTime(final LocalDateTime referenceDateTime) {
    this.referenceDateTime = referenceDateTime;
  }

  /**
   * Gets the window unit.
   *
   * @return The window unit.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public ChronoUnit getWindowUnit() {
    return this.windowUnit;
  }

  /**
   * Sets the window unit.
   *
   * @param windowUnit New window unit.
   */
  public void setWindowUnit(final ChronoUnit windowUnit) {
    this.windowUnit = windowUnit;
  }

  /**
   * Gets the window size.
   *
   * @return The window size.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getWindowSize() {
    return this.windowSize;
  }

  /**
   * Sets the window size.
   *
   * @param windowSize New window size.
   */
  public void setWindowSize(final Integer windowSize) {
    this.windowSize = windowSize;
  }

  /**
   * Gets the step unit.
   *
   * @return The step unit.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public ChronoUnit getStepUnit() {
    return this.stepUnit;
  }

  /**
   * Sets the step unit.
   *
   * @param stepUnit New step unit.
   */
  public void setStepUnit(final ChronoUnit stepUnit) {
    this.stepUnit = stepUnit;
  }

  /**
   * Gets the number of steps.
   *
   * @return The number of steps.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getSteps() {
    return this.steps;
  }

  /**
   * Sets the number of steps.
   *
   * @param steps New number of steps.
   */
  public void setSteps(final Integer steps) {
    this.steps = steps;
  }

  /**
   * Gets the sample size (how many past periods had data).
   *
   * @return The sample size.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getSampleSize() {
    return this.sampleSize;
  }

  /**
   * Sets the sample size.
   *
   * @param sampleSize New sample size.
   */
  public void setSampleSize(final Integer sampleSize) {
    this.sampleSize = sampleSize;
  }

  /**
   * Gets the average total count.
   *
   * @return The average total count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getAverageTotalCount() {
    return this.averageTotalCount;
  }

  /**
   * Sets the average total count.
   *
   * @param averageTotalCount New average total count.
   */
  public void setAverageTotalCount(final BigDecimal averageTotalCount) {
    this.averageTotalCount = averageTotalCount;
  }

  /**
   * Gets the standard deviation of total count.
   *
   * @return The standard deviation of total count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getStdDevTotalCount() {
    return this.stdDevTotalCount;
  }

  /**
   * Sets the standard deviation of total count.
   *
   * @param stdDevTotalCount New standard deviation of total count.
   */
  public void setStdDevTotalCount(final BigDecimal stdDevTotalCount) {
    this.stdDevTotalCount = stdDevTotalCount;
  }

  /**
   * Gets the average value counts.
   *
   * @return The average value counts.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getAverageValueCounts() {
    this.averageValueCounts =
        (this.averageValueCounts == null ? new HashMap<>() : this.averageValueCounts);
    return this.averageValueCounts;
  }

  /**
   * Sets the average value counts.
   *
   * @param averageValueCounts New average value counts.
   */
  public void setAverageValueCounts(final Map<String, BigDecimal> averageValueCounts) {
    this.averageValueCounts = averageValueCounts;
  }

  /**
   * Gets the standard deviation of value counts.
   *
   * @return The standard deviation of value counts.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getStdDevValueCounts() {
    this.stdDevValueCounts =
        (this.stdDevValueCounts == null ? new HashMap<>() : this.stdDevValueCounts);
    return this.stdDevValueCounts;
  }

  /**
   * Sets the standard deviation of value counts.
   *
   * @param stdDevValueCounts New standard deviation of value counts.
   */
  public void setStdDevValueCounts(final Map<String, BigDecimal> stdDevValueCounts) {
    this.stdDevValueCounts = stdDevValueCounts;
  }

  /**
   * Gets the average value ratios (proportion of each value relative to total).
   *
   * @return The average value ratios.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getAverageValueRatios() {
    this.averageValueRatios =
        (this.averageValueRatios == null ? new HashMap<>() : this.averageValueRatios);
    return this.averageValueRatios;
  }

  /**
   * Sets the average value ratios.
   *
   * @param averageValueRatios New average value ratios.
   */
  public void setAverageValueRatios(final Map<String, BigDecimal> averageValueRatios) {
    this.averageValueRatios = averageValueRatios;
  }

  /**
   * Gets the standard deviation of value ratios.
   *
   * @return The standard deviation of value ratios.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getStdDevValueRatios() {
    this.stdDevValueRatios =
        (this.stdDevValueRatios == null ? new HashMap<>() : this.stdDevValueRatios);
    return this.stdDevValueRatios;
  }

  /**
   * Sets the standard deviation of value ratios.
   *
   * @param stdDevValueRatios New standard deviation of value ratios.
   */
  public void setStdDevValueRatios(final Map<String, BigDecimal> stdDevValueRatios) {
    this.stdDevValueRatios = stdDevValueRatios;
  }

  /**
   * Gets the reference total count.
   *
   * @return The reference total count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Long getReferenceTotalCount() {
    return this.referenceTotalCount;
  }

  /**
   * Sets the reference total count.
   *
   * @param referenceTotalCount New reference total count.
   */
  public void setReferenceTotalCount(final Long referenceTotalCount) {
    this.referenceTotalCount = referenceTotalCount;
  }

  /**
   * Gets the reference value counts.
   *
   * @return The reference value counts.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, Long> getReferenceValueCounts() {
    this.referenceValueCounts =
        (this.referenceValueCounts == null ? new HashMap<>() : this.referenceValueCounts);
    return this.referenceValueCounts;
  }

  /**
   * Sets the reference value counts.
   *
   * @param referenceValueCounts New reference value counts.
   */
  public void setReferenceValueCounts(final Map<String, Long> referenceValueCounts) {
    this.referenceValueCounts = referenceValueCounts;
  }

  /**
   * Gets the reference value ratios.
   *
   * @return The reference value ratios.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getReferenceValueRatios() {
    this.referenceValueRatios =
        (this.referenceValueRatios == null ? new HashMap<>() : this.referenceValueRatios);
    return this.referenceValueRatios;
  }

  /**
   * Sets the reference value ratios.
   *
   * @param referenceValueRatios New reference value ratios.
   */
  public void setReferenceValueRatios(final Map<String, BigDecimal> referenceValueRatios) {
    this.referenceValueRatios = referenceValueRatios;
  }

  /**
   * Gets the z-score of the reference total count (number of standard deviations from the mean).
   *
   * @return The z-score of the total count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getZScoreTotalCount() {
    return this.zScoreTotalCount;
  }

  /**
   * Sets the z-score of the reference total count.
   *
   * @param zScoreTotalCount New z-score.
   */
  public void setZScoreTotalCount(final BigDecimal zScoreTotalCount) {
    this.zScoreTotalCount = zScoreTotalCount;
  }

  /**
   * Gets the z-scores of the reference value counts.
   *
   * @return The z-scores per value.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getZScoreValueCounts() {
    this.zScoreValueCounts =
        (this.zScoreValueCounts == null ? new HashMap<>() : this.zScoreValueCounts);
    return this.zScoreValueCounts;
  }

  /**
   * Sets the z-scores of the reference value counts.
   *
   * @param zScoreValueCounts New z-scores.
   */
  public void setZScoreValueCounts(final Map<String, BigDecimal> zScoreValueCounts) {
    this.zScoreValueCounts = zScoreValueCounts;
  }

  /**
   * Gets the z-scores of the reference value ratios.
   *
   * @return The z-scores per value ratio.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getZScoreValueRatios() {
    this.zScoreValueRatios =
        (this.zScoreValueRatios == null ? new HashMap<>() : this.zScoreValueRatios);
    return this.zScoreValueRatios;
  }

  /**
   * Sets the z-scores of the reference value ratios.
   *
   * @param zScoreValueRatios New z-scores.
   */
  public void setZScoreValueRatios(final Map<String, BigDecimal> zScoreValueRatios) {
    this.zScoreValueRatios = zScoreValueRatios;
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        this.context,
        this.dimensionName,
        this.referenceDateTime,
        this.windowUnit,
        this.windowSize,
        this.stepUnit,
        this.steps);
  }

  /**
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StatisticsEventSummaryComparison)) {
      return false;
    }
    final StatisticsEventSummaryComparison other = (StatisticsEventSummaryComparison) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.referenceDateTime, other.referenceDateTime)
        && Objects.equals(this.windowUnit, other.windowUnit)
        && Objects.equals(this.windowSize, other.windowSize)
        && Objects.equals(this.stepUnit, other.stepUnit)
        && Objects.equals(this.steps, other.steps);
  }
}
