package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.view.ModelView;
import org.coldis.library.persistence.model.AbstractTimestampableEntity;

/**
 * Statistics event summary. Aggregates dimension value counts for a given context and dimension at
 * a truncated point in time (15-minute intervals). Each row holds the full distribution of values
 * as a JSONB map.
 */
@Entity
@IdClass(value = StatisticsEventSummaryKey.class)
public class StatisticsEventSummary extends AbstractTimestampableEntity {

  /** Serial. */
  private static final long serialVersionUID = -7294618352047183920L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Date time. */
  private LocalDateTime dateTime;

  /** Value counts. */
  private Map<String, Long> valueCounts;

  /** Total count. */
  private Long totalCount;

  /** Accumulated weight per dimension value. */
  private Map<String, BigDecimal> valueWeights;

  /** Total accumulated weight. */
  private BigDecimal totalWeight;

  /** No arguments constructor. */
  public StatisticsEventSummary() {}

  /**
   * Full constructor.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param dateTime Date time.
   */
  public StatisticsEventSummary(
      final String context, final String dimensionName, final LocalDateTime dateTime) {
    super();
    this.context = context;
    this.dimensionName = dimensionName;
    this.dateTime = dateTime;
  }

  /**
   * Returns the composite id.
   *
   * @return The composite id.
   */
  @Transient
  @JsonIgnore
  public StatisticsEventSummaryKey getId() {
    return new StatisticsEventSummaryKey(
        this.getContext(), this.getDimensionName(), this.getDateTime());
  }

  /**
   * Gets the context.
   *
   * @return The context.
   */
  @Id
  @NotNull
  @NotEmpty
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getContext() {
    return context;
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
  @Id
  @NotNull
  @NotEmpty
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getDimensionName() {
    return dimensionName;
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
   * Gets the date time (truncated to 15-minute intervals).
   *
   * @return The date time.
   */
  @Id
  @NotNull
  @Column(name = "date_time", columnDefinition = "TIMESTAMPTZ")
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public LocalDateTime getDateTime() {
    return this.dateTime;
  }

  /**
   * Sets the date time.
   *
   * @param dateTime New date time.
   */
  public void setDateTime(final LocalDateTime dateTime) {
    this.dateTime = dateTime;
  }

  /**
   * Gets the value counts map. Each key is a dimension value and its value is the occurrence count.
   *
   * @return The value counts.
   */
  @Column(columnDefinition = "JSONB")
  @Convert(converter = MapStringLongJsonConverter.class)
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, Long> getValueCounts() {
    // Makes sure the map is initialized.
    this.valueCounts = (this.valueCounts == null ? new HashMap<>() : this.valueCounts);
    return this.valueCounts;
  }

  /**
   * Sets the value counts.
   *
   * @param valueCounts New value counts.
   */
  public void setValueCounts(final Map<String, Long> valueCounts) {
    this.valueCounts = valueCounts;
  }

  /**
   * Gets the total count across all dimension values.
   *
   * @return The total count.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Long getTotalCount() {
    // Makes sure the count is initialized.
    this.totalCount = (this.totalCount == null ? 0L : this.totalCount);
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
   * Increments the count for a dimension value by one.
   *
   * @param dimensionValue The dimension value to increment.
   */
  public void incrementValueCount(final String dimensionValue) {
    this.getValueCounts().merge(dimensionValue, 1L, Long::sum);
    this.setTotalCount(this.getTotalCount() + 1);
  }

  /**
   * Decrements the count for a dimension value by one. Removes the entry if the count reaches zero.
   *
   * @param dimensionValue The dimension value to decrement.
   */
  public void decrementValueCount(final String dimensionValue) {
    final Map<String, Long> counts = this.getValueCounts();
    final Long current = counts.get(dimensionValue);
    if (current != null && current > 0) {
      if (current <= 1) {
        counts.remove(dimensionValue);
      } else {
        counts.put(dimensionValue, current - 1);
      }
      this.setTotalCount(Math.max(0, this.getTotalCount() - 1));
    }
  }

  /**
   * Gets the accumulated weight per dimension value.
   *
   * @return The value weights.
   */
  @Column(columnDefinition = "JSONB")
  @Convert(converter = MapStringBigDecimalJsonConverter.class)
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Map<String, BigDecimal> getValueWeights() {
    this.valueWeights = (this.valueWeights == null ? new HashMap<>() : this.valueWeights);
    return this.valueWeights;
  }

  /**
   * Sets the value weights.
   *
   * @param valueWeights New value weights.
   */
  public void setValueWeights(final Map<String, BigDecimal> valueWeights) {
    this.valueWeights = valueWeights;
  }

  /**
   * Gets the total accumulated weight.
   *
   * @return The total weight.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getTotalWeight() {
    this.totalWeight = (this.totalWeight == null ? BigDecimal.ZERO : this.totalWeight);
    return this.totalWeight;
  }

  /**
   * Sets the total weight.
   *
   * @param totalWeight New total weight.
   */
  public void setTotalWeight(final BigDecimal totalWeight) {
    this.totalWeight = totalWeight;
  }

  /**
   * Increments the weight for a dimension value.
   *
   * @param dimensionValue The dimension value.
   * @param weight The weight to add.
   */
  public void incrementValueWeight(final String dimensionValue, final BigDecimal weight) {
    this.getValueWeights().merge(dimensionValue, weight, BigDecimal::add);
    this.setTotalWeight(this.getTotalWeight().add(weight));
  }

  /**
   * Decrements the weight for a dimension value. Removes the entry if the weight reaches zero or
   * below.
   *
   * @param dimensionValue The dimension value.
   * @param weight The weight to subtract.
   */
  public void decrementValueWeight(final String dimensionValue, final BigDecimal weight) {
    final Map<String, BigDecimal> weights = this.getValueWeights();
    final BigDecimal current = weights.get(dimensionValue);
    if (current != null) {
      final BigDecimal newValue = current.subtract(weight);
      if (newValue.compareTo(BigDecimal.ZERO) <= 0) {
        weights.remove(dimensionValue);
      } else {
        weights.put(dimensionValue, newValue);
      }
      this.setTotalWeight(this.getTotalWeight().subtract(weight).max(BigDecimal.ZERO));
    }
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result =
        (prime * result)
            + Objects.hash(
                this.context,
                this.dimensionName,
                this.dateTime,
                this.valueCounts,
                this.totalCount,
                this.valueWeights,
                this.totalWeight);
    return result;
  }

  /**
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (!super.equals(obj)) {
      return false;
    }
    if (!(obj instanceof StatisticsEventSummary)) {
      return false;
    }
    final StatisticsEventSummary other = (StatisticsEventSummary) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.dateTime, other.dateTime)
        && Objects.equals(this.valueCounts, other.valueCounts)
        && Objects.equals(this.totalCount, other.totalCount)
        && Objects.equals(this.valueWeights, other.valueWeights)
        && Objects.equals(this.totalWeight, other.totalWeight);
  }
}
