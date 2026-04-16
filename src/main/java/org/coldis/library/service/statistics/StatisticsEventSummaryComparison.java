package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Statistics event summary comparison. Compares a reference window against historical periods,
 * providing moving averages, standard deviations, value ratios, and z-scores for both counts and
 * weights.
 */
public class StatisticsEventSummaryComparison implements Serializable {

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

  /** Count statistics (historical avg/stdDev, reference, z-scores). */
  private MetricComparisonStats countStats;

  /** Weight statistics (historical avg/stdDev, reference, z-scores). */
  private MetricComparisonStats weightStats;

  public StatisticsEventSummaryComparison() {}

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getContext() {
    return this.context;
  }

  public void setContext(final String context) {
    this.context = context;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getDimensionName() {
    return this.dimensionName;
  }

  public void setDimensionName(final String dimensionName) {
    this.dimensionName = dimensionName;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public LocalDateTime getReferenceDateTime() {
    return this.referenceDateTime;
  }

  public void setReferenceDateTime(final LocalDateTime referenceDateTime) {
    this.referenceDateTime = referenceDateTime;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public ChronoUnit getWindowUnit() {
    return this.windowUnit;
  }

  public void setWindowUnit(final ChronoUnit windowUnit) {
    this.windowUnit = windowUnit;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getWindowSize() {
    return this.windowSize;
  }

  public void setWindowSize(final Integer windowSize) {
    this.windowSize = windowSize;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public ChronoUnit getStepUnit() {
    return this.stepUnit;
  }

  public void setStepUnit(final ChronoUnit stepUnit) {
    this.stepUnit = stepUnit;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getSteps() {
    return this.steps;
  }

  public void setSteps(final Integer steps) {
    this.steps = steps;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getSampleSize() {
    return this.sampleSize;
  }

  public void setSampleSize(final Integer sampleSize) {
    this.sampleSize = sampleSize;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public MetricComparisonStats getCountStats() {
    this.countStats = (this.countStats == null ? new MetricComparisonStats() : this.countStats);
    return this.countStats;
  }

  public void setCountStats(final MetricComparisonStats countStats) {
    this.countStats = countStats;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public MetricComparisonStats getWeightStats() {
    this.weightStats = (this.weightStats == null ? new MetricComparisonStats() : this.weightStats);
    return this.weightStats;
  }

  public void setWeightStats(final MetricComparisonStats weightStats) {
    this.weightStats = weightStats;
  }

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
