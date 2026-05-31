package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
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

  /** Joint probability (product of individual probabilities, assuming independence). */
  private BigDecimal jointProbability;

  /** Joint smoothed probability (product of the per-dimension Laplace-smoothed probabilities; never zero). */
  private BigDecimal jointSmoothedProbability;

  /** Natural log of the joint smoothed probability (Σ ln of the per-dimension smoothed probabilities). */
  private BigDecimal jointSmoothedLogProbability;

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
   * Gets the joint smoothed probability — product of the per-dimension Laplace-smoothed
   * probabilities. Never zero, so it (and its log) stay finite even when a value was unseen.
   *
   * @return The joint smoothed probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getJointSmoothedProbability() {
    return this.jointSmoothedProbability;
  }

  /**
   * Sets the joint smoothed probability.
   *
   * @param jointSmoothedProbability New joint smoothed probability.
   */
  public void setJointSmoothedProbability(final BigDecimal jointSmoothedProbability) {
    this.jointSmoothedProbability = jointSmoothedProbability;
  }

  /**
   * Gets the natural log of the joint smoothed probability (Σ ln of the per-dimension smoothed
   * probabilities) — the additive, finite log-likelihood of the attribute combination.
   *
   * @return The joint smoothed log probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getJointSmoothedLogProbability() {
    return this.jointSmoothedLogProbability;
  }

  /**
   * Sets the joint smoothed log probability.
   *
   * @param jointSmoothedLogProbability New joint smoothed log probability.
   */
  public void setJointSmoothedLogProbability(final BigDecimal jointSmoothedLogProbability) {
    this.jointSmoothedLogProbability = jointSmoothedLogProbability;
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
    return Objects.hash(this.context, this.individualProbabilities);
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
        && Objects.equals(this.individualProbabilities, other.individualProbabilities);
  }
}
