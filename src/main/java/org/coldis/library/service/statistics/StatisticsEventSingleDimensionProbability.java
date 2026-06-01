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

  /** Lower bound of the 95% Wilson score interval on the raw {@code count/total} proportion. */
  private BigDecimal wilsonLowerBound;

  /** Upper bound of the 95% Wilson score interval on the raw {@code count/total} proportion. */
  private BigDecimal wilsonUpperBound;

  /** Variance of the Beta posterior whose mean is {@link #smoothedProbability} (Dirichlet-multinomial). */
  private BigDecimal posteriorVariance;

  /** Lower bound of the 95% credible interval (normal approximation to the Beta posterior), clamped to [0,1]. */
  private BigDecimal credibleLowerBound;

  /** Upper bound of the 95% credible interval (normal approximation to the Beta posterior), clamped to [0,1]. */
  private BigDecimal credibleUpperBound;

  /** Surprisal (self-information) {@code -ln(smoothedProbability)} in nats; zero when the value is certain. */
  private BigDecimal surprisal;

  /** Log-odds (logit) {@code ln(p / (1 - p))} of the smoothed probability; positive favours the value. */
  private BigDecimal logOdds;

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
   * Gets the lower bound of the 95% Wilson score interval on the raw proportion.
   *
   * @return The Wilson lower bound.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getWilsonLowerBound() {
    return this.wilsonLowerBound;
  }

  /**
   * Sets the Wilson score interval lower bound.
   *
   * @param wilsonLowerBound New Wilson lower bound.
   */
  public void setWilsonLowerBound(final BigDecimal wilsonLowerBound) {
    this.wilsonLowerBound = wilsonLowerBound;
  }

  /**
   * Gets the upper bound of the 95% Wilson score interval on the raw proportion.
   *
   * @return The Wilson upper bound.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getWilsonUpperBound() {
    return this.wilsonUpperBound;
  }

  /**
   * Sets the Wilson score interval upper bound.
   *
   * @param wilsonUpperBound New Wilson upper bound.
   */
  public void setWilsonUpperBound(final BigDecimal wilsonUpperBound) {
    this.wilsonUpperBound = wilsonUpperBound;
  }

  /**
   * Gets the variance of the Beta posterior whose mean is the smoothed probability.
   *
   * @return The posterior variance.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getPosteriorVariance() {
    return this.posteriorVariance;
  }

  /**
   * Sets the posterior variance.
   *
   * @param posteriorVariance New posterior variance.
   */
  public void setPosteriorVariance(final BigDecimal posteriorVariance) {
    this.posteriorVariance = posteriorVariance;
  }

  /**
   * Gets the lower bound of the 95% credible interval (normal approximation to the Beta posterior).
   *
   * @return The credible interval lower bound.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getCredibleLowerBound() {
    return this.credibleLowerBound;
  }

  /**
   * Sets the credible interval lower bound.
   *
   * @param credibleLowerBound New credible interval lower bound.
   */
  public void setCredibleLowerBound(final BigDecimal credibleLowerBound) {
    this.credibleLowerBound = credibleLowerBound;
  }

  /**
   * Gets the upper bound of the 95% credible interval (normal approximation to the Beta posterior).
   *
   * @return The credible interval upper bound.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getCredibleUpperBound() {
    return this.credibleUpperBound;
  }

  /**
   * Sets the credible interval upper bound.
   *
   * @param credibleUpperBound New credible interval upper bound.
   */
  public void setCredibleUpperBound(final BigDecimal credibleUpperBound) {
    this.credibleUpperBound = credibleUpperBound;
  }

  /**
   * Gets the surprisal (self-information) {@code -ln(smoothedProbability)} in nats.
   *
   * @return The surprisal.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getSurprisal() {
    return this.surprisal;
  }

  /**
   * Sets the surprisal.
   *
   * @param surprisal New surprisal.
   */
  public void setSurprisal(final BigDecimal surprisal) {
    this.surprisal = surprisal;
  }

  /**
   * Gets the log-odds (logit) of the smoothed probability.
   *
   * @return The log-odds.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getLogOdds() {
    return this.logOdds;
  }

  /**
   * Sets the log-odds.
   *
   * @param logOdds New log-odds.
   */
  public void setLogOdds(final BigDecimal logOdds) {
    this.logOdds = logOdds;
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
