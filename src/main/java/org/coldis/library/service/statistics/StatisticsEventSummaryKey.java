package org.coldis.library.service.statistics;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/** Composite key for {@link StatisticsEventSummary}. */
public class StatisticsEventSummaryKey implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = -5918264730182746501L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Date time. */
  private LocalDateTime dateTime;

  /** No arguments constructor. */
  public StatisticsEventSummaryKey() {}

  /**
   * Full constructor.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param dateTime Date time.
   */
  public StatisticsEventSummaryKey(
      final String context, final String dimensionName, final LocalDateTime dateTime) {
    super();
    this.context = context;
    this.dimensionName = dimensionName;
    this.dateTime = dateTime;
  }

  /**
   * Gets the context.
   *
   * @return The context.
   */
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
   * Gets the date time.
   *
   * @return The date time.
   */
  public LocalDateTime getDateTime() {
    return dateTime;
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
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.context, this.dimensionName, this.dateTime);
  }

  /**
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StatisticsEventSummaryKey)) {
      return false;
    }
    final StatisticsEventSummaryKey other = (StatisticsEventSummaryKey) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.dateTime, other.dateTime);
  }
}
