package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Statistics event single-dimension probability. The probability of a single dimension value within
 * a period, derived from that period's aggregated summary: the raw ratio {@code count/total} and the
 * Laplace-smoothed {@code (count + α) / (total + α·V)}.
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

  /** Probability — raw ratio {@code count/total} of the dimension value within the period. */
  private BigDecimal probability;

  /** Number of distinct values observed for the dimension (vocabulary size used in smoothing). */
  private Integer distinctValueCount;

  /** Laplace-smoothed probability {@code (count + α) / (total + α·V)}, never zero. */
  private BigDecimal smoothedProbability;

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
   * Gets the probability (raw {@code count/total} ratio of the dimension value).
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
   * Gets the number of distinct values observed for the dimension (the smoothing vocabulary size).
   *
   * @return The distinct value count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getDistinctValueCount() {
    return this.distinctValueCount;
  }

  /**
   * Sets the distinct value count.
   *
   * @param distinctValueCount New distinct value count.
   */
  public void setDistinctValueCount(final Integer distinctValueCount) {
    this.distinctValueCount = distinctValueCount;
  }

  /**
   * Gets the Laplace-smoothed probability — {@code (count + α) / (total + α·V)}, never zero.
   *
   * @return The smoothed probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getSmoothedProbability() {
    return this.smoothedProbability;
  }

  /**
   * Sets the smoothed probability.
   *
   * @param smoothedProbability New smoothed probability.
   */
  public void setSmoothedProbability(final BigDecimal smoothedProbability) {
    this.smoothedProbability = smoothedProbability;
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.context, this.dimensionName, this.dimensionValue);
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
        && Objects.equals(this.dimensionValue, other.dimensionValue);
  }
}
