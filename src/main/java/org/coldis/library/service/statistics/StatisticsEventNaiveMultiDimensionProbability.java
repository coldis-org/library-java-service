package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Statistics event naive multi-dimension probability. Computes the joint probability of multiple
 * dimension values assuming independence: P(A ∩ B) = P(A) × P(B).
 */
public class StatisticsEventNaiveMultiDimensionProbability implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = 7293581046819274653L;

  /** Context. */
  private String context;

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

  /** Joint probability (product of individual probabilities, assuming independence). */
  private BigDecimal jointProbability;

  /** Individual probabilities per dimension. */
  private List<StatisticsEventSingleDimensionProbability> individualProbabilities;

  /** No arguments constructor. */
  public StatisticsEventNaiveMultiDimensionProbability() {}

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
   * Gets the joint probability (product of individual probabilities).
   *
   * @return The joint probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getJointProbability() {
    return this.jointProbability;
  }

  /**
   * Sets the joint probability.
   *
   * @param jointProbability New joint probability.
   */
  public void setJointProbability(final BigDecimal jointProbability) {
    this.jointProbability = jointProbability;
  }

  /**
   * Gets the individual probabilities per dimension.
   *
   * @return The individual probabilities.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public List<StatisticsEventSingleDimensionProbability> getIndividualProbabilities() {
    return this.individualProbabilities;
  }

  /**
   * Sets the individual probabilities.
   *
   * @param individualProbabilities New individual probabilities.
   */
  public void setIndividualProbabilities(
      final List<StatisticsEventSingleDimensionProbability> individualProbabilities) {
    this.individualProbabilities = individualProbabilities;
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(
        this.context,
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
    if (!(obj instanceof StatisticsEventNaiveMultiDimensionProbability)) {
      return false;
    }
    final StatisticsEventNaiveMultiDimensionProbability other =
        (StatisticsEventNaiveMultiDimensionProbability) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.referenceDateTime, other.referenceDateTime)
        && Objects.equals(this.windowUnit, other.windowUnit)
        && Objects.equals(this.windowSize, other.windowSize)
        && Objects.equals(this.stepUnit, other.stepUnit)
        && Objects.equals(this.steps, other.steps);
  }
}
