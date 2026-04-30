package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.Expirable;
import org.coldis.library.model.Reduceable;
import org.coldis.library.model.view.ModelView;
import org.coldis.library.persistence.model.AbstractTimestampableEntity;

/**
 * Statistics event. Captures a single dimension value for a given owner within a context at a
 * truncated point in time (15-minute intervals). Used for statistics anomaly detection.
 */
@Entity
@IdClass(value = StatisticsEventKey.class)
@Table(
    indexes = {
      @Index(
          name = "idx_statistics_event_context_dimension_datetime",
          columnList = "context, dimension_name, date_time")
    })
public class StatisticsEvent extends AbstractTimestampableEntity
    implements Expirable, Reduceable<StatisticsEvent> {

  /** Serial. */
  private static final long serialVersionUID = -4829175638201947362L;

  /** Context. */
  private String context;

  /** Owner key. */
  private String ownerKey;

  /** Date time. */
  private LocalDateTime dateTime;

  /** Dimension name. */
  private String dimensionName;

  /** Dimension value. */
  private String dimensionValue;

  /** Weight (numeric value to accumulate in summaries, defaults to 1). */
  private BigDecimal weight;

  /** When the statistics event expires. */
  private LocalDateTime expiredAt;

  /** When the event was emitted by the caller (used to preserve order across buffered upserts). */
  private LocalDateTime emittedAt;

  /** No arguments constructor. */
  public StatisticsEvent() {}

  /**
   * Full constructor.
   *
   * @param context Context.
   * @param ownerKey Owner key.
   * @param dateTime Date time.
   * @param dimensionName Dimension name.
   * @param dimensionValue Dimension value.
   */
  public StatisticsEvent(
      final String context,
      final String ownerKey,
      final LocalDateTime dateTime,
      final String dimensionName,
      final String dimensionValue) {
    super();
    this.context = context;
    this.ownerKey = ownerKey;
    this.dateTime = dateTime;
    this.dimensionName = dimensionName;
    this.dimensionValue = dimensionValue;
  }

  /**
   * Returns the composite id.
   *
   * @return The composite id.
   */
  @Transient
  @JsonIgnore
  public StatisticsEventKey getId() {
    return new StatisticsEventKey(this.getContext(), this.getOwnerKey(), this.getDimensionName());
  }

  /**
   * Truncates a date time to the given interval in minutes.
   *
   * @param dateTime Date time.
   * @param truncationMinutes Truncation interval in minutes.
   * @return Truncated date time.
   */
  public static LocalDateTime truncateDateTime(
      final LocalDateTime dateTime, final long truncationMinutes) {
    if (dateTime == null) {
      return null;
    }
    final LocalDateTime truncatedToHour = dateTime.truncatedTo(ChronoUnit.HOURS);
    final long minuteOfHour = dateTime.getMinute();
    final long truncatedMinute = (minuteOfHour / truncationMinutes) * truncationMinutes;
    return truncatedToHour.plusMinutes(truncatedMinute);
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
   * Gets the owner key.
   *
   * @return The owner key.
   */
  @Id
  @NotNull
  @NotEmpty
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getOwnerKey() {
    return ownerKey;
  }

  /**
   * Sets the owner key.
   *
   * @param ownerKey New owner key.
   */
  public void setOwnerKey(final String ownerKey) {
    this.ownerKey = ownerKey;
  }

  /**
   * Gets the date time (truncated to the configured interval).
   *
   * @return The date time.
   */
  @NotNull
  @Column(name = "date_time", columnDefinition = "TIMESTAMPTZ")
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public LocalDateTime getDateTime() {
    // Makes sure the date is initialized and truncated.
    this.dateTime =
        (this.dateTime == null
            ? DateTimeHelper.getCurrentLocalDateTime()
            : this.dateTime);
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
   * Gets the dimension value.
   *
   * @return The dimension value.
   */
  @NotNull
  @NotEmpty
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public String getDimensionValue() {
    return dimensionValue;
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
   * Gets the weight.
   *
   * @return The weight.
   */
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public BigDecimal getWeight() {
    this.weight = (this.weight == null ? BigDecimal.ONE : this.weight);
    return this.weight;
  }

  /**
   * Sets the weight.
   *
   * @param weight New weight.
   */
  public void setWeight(final BigDecimal weight) {
    this.weight = weight;
  }

  /**
   * Gets the expiredAt.
   *
   * @return The expiredAt.
   */
  @Override
  @Column(columnDefinition = "TIMESTAMPTZ")
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public LocalDateTime getExpiredAt() {
    return this.expiredAt;
  }

  /**
   * Sets the expiredAt.
   *
   * @param expiredAt New expiredAt.
   */
  @Override
  public void setExpiredAt(final LocalDateTime expiredAt) {
    this.expiredAt = expiredAt;
  }

  /**
   * @see Expirable#getExpired()
   */
  @Override
  @Transient
  public Boolean getExpired() {
    return (this.getExpiredAt() != null)
        && this.getExpiredAt().isBefore(DateTimeHelper.getCurrentLocalDateTime());
  }

  /**
   * Gets the emittedAt. Falls back to updatedAt when null (covers legacy rows that pre-date the
   * column and callers that did not set it explicitly).
   *
   * @return The emittedAt.
   */
  @Column(name = "emitted_at", columnDefinition = "TIMESTAMPTZ")
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public LocalDateTime getEmittedAt() {
    this.emittedAt = (this.emittedAt == null ? this.getUpdatedAt() : this.emittedAt);
    return this.emittedAt;
  }

  /**
   * Sets the emittedAt.
   *
   * @param emittedAt New emittedAt.
   */
  public void setEmittedAt(final LocalDateTime emittedAt) {
    this.emittedAt = emittedAt;
  }

  /**
   * @see Reduceable#reduce(Object)
   *
   * <p>Latest-emission-wins: keeps the current state if the incoming event is older than what is
   * already buffered.
   */
  @Override
  public synchronized void reduce(final StatisticsEvent toBeReduced) {
    final LocalDateTime currentEmittedAt = this.getEmittedAt();
    final LocalDateTime incomingEmittedAt = toBeReduced.getEmittedAt();
    if (currentEmittedAt != null
        && incomingEmittedAt != null
        && incomingEmittedAt.isBefore(currentEmittedAt)) {
      return;
    }
    this.dateTime = toBeReduced.getDateTime();
    this.dimensionValue = toBeReduced.getDimensionValue();
    this.weight = toBeReduced.getWeight();
    this.expiredAt = toBeReduced.getExpiredAt();
    this.emittedAt = incomingEmittedAt;
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
                this.ownerKey,
                this.dateTime,
                this.dimensionName,
                this.dimensionValue,
                this.weight,
                this.expiredAt,
                this.emittedAt);
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
    if (!(obj instanceof StatisticsEvent)) {
      return false;
    }
    final StatisticsEvent other = (StatisticsEvent) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.ownerKey, other.ownerKey)
        && Objects.equals(this.dateTime, other.dateTime)
        && Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.dimensionValue, other.dimensionValue)
        && Objects.equals(this.weight, other.weight)
        && Objects.equals(this.expiredAt, other.expiredAt)
        && Objects.equals(this.emittedAt, other.emittedAt);
  }
}
