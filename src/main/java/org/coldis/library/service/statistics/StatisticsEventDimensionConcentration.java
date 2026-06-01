package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Distribution-level concentration of a dimension's value mix within a period, derived from that
 * period's aggregated summary. Unlike the per-value probability, these describe the whole
 * distribution: Shannon {@code entropy} (nats), {@code normalizedEntropy} in {@code [0,1]} (1 =
 * perfectly uniform, 0 = a single value carries everything), and the {@code giniSimpsonIndex}
 * {@code 1 − Σpᵢ²} (probability two random draws differ).
 */
public class StatisticsEventDimensionConcentration implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = 7129834756120983476L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Number of distinct values observed for the dimension. */
  private Integer distinctValueCount;

  /** Total event count over which the distribution is computed. */
  private Long totalCount;

  /** Shannon entropy {@code -Σpᵢ·ln(pᵢ)} in nats; zero when one value carries all the mass. */
  private BigDecimal entropy;

  /** Entropy normalized to {@code [0,1]} by {@code ln(distinctValueCount)}; 1 = uniform, 0 = fully concentrated. */
  private BigDecimal normalizedEntropy;

  /** Gini-Simpson index {@code 1 − Σpᵢ²} — the probability that two independent draws yield different values. */
  private BigDecimal giniSimpsonIndex;

  /** No arguments constructor. */
  public StatisticsEventDimensionConcentration() {}

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
   * Gets the number of distinct values observed for the dimension.
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
   * Gets the total event count over which the distribution is computed.
   *
   * @return The total count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Long getTotalCount() {
    return this.totalCount;
  }

  /**
   * Sets the total count.
   *
   * @param totalCount New total count.
   */
  public void setTotalCount(final Long totalCount) {
    this.totalCount = totalCount;
  }

  /**
   * Gets the Shannon entropy (nats).
   *
   * @return The entropy.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getEntropy() {
    return this.entropy;
  }

  /**
   * Sets the entropy.
   *
   * @param entropy New entropy.
   */
  public void setEntropy(final BigDecimal entropy) {
    this.entropy = entropy;
  }

  /**
   * Gets the normalized entropy in {@code [0,1]}.
   *
   * @return The normalized entropy.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getNormalizedEntropy() {
    return this.normalizedEntropy;
  }

  /**
   * Sets the normalized entropy.
   *
   * @param normalizedEntropy New normalized entropy.
   */
  public void setNormalizedEntropy(final BigDecimal normalizedEntropy) {
    this.normalizedEntropy = normalizedEntropy;
  }

  /**
   * Gets the Gini-Simpson index {@code 1 − Σpᵢ²}.
   *
   * @return The Gini-Simpson index.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getGiniSimpsonIndex() {
    return this.giniSimpsonIndex;
  }

  /**
   * Sets the Gini-Simpson index.
   *
   * @param giniSimpsonIndex New Gini-Simpson index.
   */
  public void setGiniSimpsonIndex(final BigDecimal giniSimpsonIndex) {
    this.giniSimpsonIndex = giniSimpsonIndex;
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.context, this.dimensionName);
  }

  /**
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StatisticsEventDimensionConcentration)) {
      return false;
    }
    final StatisticsEventDimensionConcentration other = (StatisticsEventDimensionConcentration) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.dimensionName, other.dimensionName);
  }
}
