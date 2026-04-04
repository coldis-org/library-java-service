package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Statistics event single-dimension probability. Computes the probability of a single dimension
 * value based on historical distribution (reference period included in the sample).
 */
public class StatisticsEventSingleDimensionProbability implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = -4827163958274619385L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Dimension value being evaluated. */
  private String dimensionValue;

  /** Reference date time (start of the reference window). */
  private LocalDateTime referenceDateTime;

  /** Window unit (defines window size). */
  private ChronoUnit windowUnit;

  /** Window size (number of window units). */
  private Integer windowSize;

  /** Step unit (defines how far back each sample is). */
  private ChronoUnit stepUnit;

  /** Number of steps (periods sampled, including reference). */
  private Integer steps;

  /** Number of periods that actually had data. */
  private Integer sampleSize;

  /** Probability (average ratio of the dimension value across sampled periods). */
  private BigDecimal probability;

  /** Standard deviation of the probability across sampled periods. */
  private BigDecimal stdDevProbability;

  /** Average count of the dimension value across sampled periods. */
  private BigDecimal averageCount;

  /** Standard deviation of the count across sampled periods. */
  private BigDecimal stdDevCount;

  /** No arguments constructor. */
  public StatisticsEventSingleDimensionProbability() {}

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
   * Gets the dimension value.
   *
   * @return The dimension value.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getDimensionValue() {
    return this.dimensionValue;
  }

  /**
   * Sets the dimension value.
   *
   * @param dimensionValue New dimension value.
   */
  public void setDimensionValue(final String dimensionValue) {
    this.dimensionValue = dimensionValue;
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
   * Gets the sample size (how many periods had data).
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
   * Gets the probability (average ratio of the dimension value).
   *
   * @return The probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getProbability() {
    return this.probability;
  }

  /**
   * Sets the probability.
   *
   * @param probability New probability.
   */
  public void setProbability(final BigDecimal probability) {
    this.probability = probability;
  }

  /**
   * Gets the standard deviation of the probability.
   *
   * @return The standard deviation of the probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getStdDevProbability() {
    return this.stdDevProbability;
  }

  /**
   * Sets the standard deviation of the probability.
   *
   * @param stdDevProbability New standard deviation of the probability.
   */
  public void setStdDevProbability(final BigDecimal stdDevProbability) {
    this.stdDevProbability = stdDevProbability;
  }

  /**
   * Gets the average count of the dimension value.
   *
   * @return The average count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getAverageCount() {
    return this.averageCount;
  }

  /**
   * Sets the average count.
   *
   * @param averageCount New average count.
   */
  public void setAverageCount(final BigDecimal averageCount) {
    this.averageCount = averageCount;
  }

  /**
   * Gets the standard deviation of the count.
   *
   * @return The standard deviation of the count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getStdDevCount() {
    return this.stdDevCount;
  }

  /**
   * Sets the standard deviation of the count.
   *
   * @param stdDevCount New standard deviation of the count.
   */
  public void setStdDevCount(final BigDecimal stdDevCount) {
    this.stdDevCount = stdDevCount;
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        this.context,
        this.dimensionName,
        this.dimensionValue,
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
    if (!(obj instanceof StatisticsEventSingleDimensionProbability)) {
      return false;
    }
    final StatisticsEventSingleDimensionProbability other =
        (StatisticsEventSingleDimensionProbability) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.dimensionValue, other.dimensionValue)
        && Objects.equals(this.referenceDateTime, other.referenceDateTime)
        && Objects.equals(this.windowUnit, other.windowUnit)
        && Objects.equals(this.windowSize, other.windowSize)
        && Objects.equals(this.stepUnit, other.stepUnit)
        && Objects.equals(this.steps, other.steps);
  }
}
