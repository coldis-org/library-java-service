package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;

/**
 * Windowed probability of a dimension value across several per-window summaries (e.g. the raw
 * per-bucket rows returned by {@code findByPeriod}). Contrasts two estimates of the same value's
 * probability:
 *
 * <ul>
 * <li><b>Pooled (micro)</b> — {@code Σcount / Σtotal} over all windows, so high-volume windows
 * dominate (the same number the single-period probability produces).</li>
 * <li><b>Macro</b> — the mean of each window's own {@code count/total} ratio, every window weighted
 * equally, plus {@code macroProbabilityStdDev} measuring how stable that share is across windows.</li>
 * </ul>
 */
public class StatisticsEventWindowedProbability implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = 5821093746512098347L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Dimension value being evaluated. */
  private String dimensionValue;

  /** Number of windows (with at least one event) the estimates are computed over. */
  private Integer windowCount;

  /** Pooled (micro) probability {@code Σcount / Σtotal} across all windows. */
  private BigDecimal pooledProbability;

  /** Macro probability — the mean of each window's own {@code count/total} ratio, equal-weighted. */
  private BigDecimal macroProbability;

  /** Population standard deviation of the per-window ratios — how stable the value's share is over time. */
  private BigDecimal macroProbabilityStdDev;

  /** No arguments constructor. */
  public StatisticsEventWindowedProbability() {}

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
   * Gets the number of windows the estimates are computed over.
   *
   * @return The window count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Integer getWindowCount() {
    return this.windowCount;
  }

  /**
   * Sets the window count.
   *
   * @param windowCount New window count.
   */
  public void setWindowCount(final Integer windowCount) {
    this.windowCount = windowCount;
  }

  /**
   * Gets the pooled (micro) probability {@code Σcount / Σtotal}.
   *
   * @return The pooled probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getPooledProbability() {
    return this.pooledProbability;
  }

  /**
   * Sets the pooled probability.
   *
   * @param pooledProbability New pooled probability.
   */
  public void setPooledProbability(final BigDecimal pooledProbability) {
    this.pooledProbability = pooledProbability;
  }

  /**
   * Gets the macro probability (equal-weighted mean of per-window ratios).
   *
   * @return The macro probability.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getMacroProbability() {
    return this.macroProbability;
  }

  /**
   * Sets the macro probability.
   *
   * @param macroProbability New macro probability.
   */
  public void setMacroProbability(final BigDecimal macroProbability) {
    this.macroProbability = macroProbability;
  }

  /**
   * Gets the standard deviation of the per-window ratios.
   *
   * @return The macro probability standard deviation.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getMacroProbabilityStdDev() {
    return this.macroProbabilityStdDev;
  }

  /**
   * Sets the macro probability standard deviation.
   *
   * @param macroProbabilityStdDev New macro probability standard deviation.
   */
  public void setMacroProbabilityStdDev(final BigDecimal macroProbabilityStdDev) {
    this.macroProbabilityStdDev = macroProbabilityStdDev;
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
    if (!(obj instanceof StatisticsEventWindowedProbability)) {
      return false;
    }
    final StatisticsEventWindowedProbability other = (StatisticsEventWindowedProbability) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.dimensionValue, other.dimensionValue);
  }
}
