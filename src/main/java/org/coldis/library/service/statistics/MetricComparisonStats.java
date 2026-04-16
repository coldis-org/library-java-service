package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.coldis.library.model.view.ModelView;

/**
 * Statistical comparison data for a single metric (counts or weights). Holds historical
 * averages/stdDevs, reference-window values, and z-scores. Used as a nested component of {@link
 * StatisticsEventSummaryComparison}.
 */
public class MetricComparisonStats implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Average total across past periods. */
  private BigDecimal averageTotal;

  /** Standard deviation of total across past periods. */
  private BigDecimal stdDevTotal;

  /** Average per-value across past periods. */
  private Map<String, BigDecimal> averageValues;

  /** Standard deviation of per-value across past periods. */
  private Map<String, BigDecimal> stdDevValues;

  /** Average value ratios (value / total) across past periods. */
  private Map<String, BigDecimal> averageRatios;

  /** Standard deviation of value ratios across past periods. */
  private Map<String, BigDecimal> stdDevRatios;

  /** Reference window total. */
  private BigDecimal referenceTotal;

  /** Reference window per-value. */
  private Map<String, BigDecimal> referenceValues;

  /** Reference window value ratios. */
  private Map<String, BigDecimal> referenceRatios;

  /** Z-score of reference total (deviations from mean). */
  private BigDecimal zScoreTotal;

  /** Z-scores of reference per-value (deviations from mean). */
  private Map<String, BigDecimal> zScoreValues;

  /** Z-scores of reference value ratios (deviations from mean). */
  private Map<String, BigDecimal> zScoreRatios;

  public MetricComparisonStats() {}

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getAverageTotal() {
    return this.averageTotal;
  }

  public void setAverageTotal(final BigDecimal averageTotal) {
    this.averageTotal = averageTotal;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getStdDevTotal() {
    return this.stdDevTotal;
  }

  public void setStdDevTotal(final BigDecimal stdDevTotal) {
    this.stdDevTotal = stdDevTotal;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getAverageValues() {
    this.averageValues = (this.averageValues == null ? new HashMap<>() : this.averageValues);
    return this.averageValues;
  }

  public void setAverageValues(final Map<String, BigDecimal> averageValues) {
    this.averageValues = averageValues;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getStdDevValues() {
    this.stdDevValues = (this.stdDevValues == null ? new HashMap<>() : this.stdDevValues);
    return this.stdDevValues;
  }

  public void setStdDevValues(final Map<String, BigDecimal> stdDevValues) {
    this.stdDevValues = stdDevValues;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getAverageRatios() {
    this.averageRatios = (this.averageRatios == null ? new HashMap<>() : this.averageRatios);
    return this.averageRatios;
  }

  public void setAverageRatios(final Map<String, BigDecimal> averageRatios) {
    this.averageRatios = averageRatios;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getStdDevRatios() {
    this.stdDevRatios = (this.stdDevRatios == null ? new HashMap<>() : this.stdDevRatios);
    return this.stdDevRatios;
  }

  public void setStdDevRatios(final Map<String, BigDecimal> stdDevRatios) {
    this.stdDevRatios = stdDevRatios;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getReferenceTotal() {
    return this.referenceTotal;
  }

  public void setReferenceTotal(final BigDecimal referenceTotal) {
    this.referenceTotal = referenceTotal;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getReferenceValues() {
    this.referenceValues = (this.referenceValues == null ? new HashMap<>() : this.referenceValues);
    return this.referenceValues;
  }

  public void setReferenceValues(final Map<String, BigDecimal> referenceValues) {
    this.referenceValues = referenceValues;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getReferenceRatios() {
    this.referenceRatios = (this.referenceRatios == null ? new HashMap<>() : this.referenceRatios);
    return this.referenceRatios;
  }

  public void setReferenceRatios(final Map<String, BigDecimal> referenceRatios) {
    this.referenceRatios = referenceRatios;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getZScoreTotal() {
    return this.zScoreTotal;
  }

  public void setZScoreTotal(final BigDecimal zScoreTotal) {
    this.zScoreTotal = zScoreTotal;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getZScoreValues() {
    this.zScoreValues = (this.zScoreValues == null ? new HashMap<>() : this.zScoreValues);
    return this.zScoreValues;
  }

  public void setZScoreValues(final Map<String, BigDecimal> zScoreValues) {
    this.zScoreValues = zScoreValues;
  }

  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getZScoreRatios() {
    this.zScoreRatios = (this.zScoreRatios == null ? new HashMap<>() : this.zScoreRatios);
    return this.zScoreRatios;
  }

  public void setZScoreRatios(final Map<String, BigDecimal> zScoreRatios) {
    this.zScoreRatios = zScoreRatios;
  }
}
