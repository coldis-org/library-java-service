package org.coldis.library.service.statistics;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.coldis.library.model.Reduceable;

/**
 * Represents a buffered delta to be applied to a {@link StatisticsEventSummary}. Count and weight
 * changes are accumulated per dimension value and flushed periodically.
 */
public class StatisticsEventSummaryDelta
    implements Serializable, Reduceable<StatisticsEventSummaryDelta> {

  /** Serial. */
  private static final long serialVersionUID = -7382916450283741650L;

  /** Context. */
  private String context;

  /** Dimension name. */
  private String dimensionName;

  /** Date time (truncated). */
  private LocalDateTime dateTime;

  /** Count deltas per dimension value (positive = increment, negative = decrement). */
  private Map<String, Long> countDeltas;

  /** Weight deltas per dimension value (positive = increment, negative = decrement). */
  private Map<String, BigDecimal> weightDeltas;

  /** No arguments constructor. */
  public StatisticsEventSummaryDelta() {}

  /**
   * Full constructor.
   *
   * @param context Context.
   * @param dimensionName Dimension name.
   * @param dateTime Date time.
   */
  public StatisticsEventSummaryDelta(
      final String context, final String dimensionName, final LocalDateTime dateTime) {
    this.context = context;
    this.dimensionName = dimensionName;
    this.dateTime = dateTime;
  }

  public String getContext() {
    return context;
  }

  public void setContext(final String context) {
    this.context = context;
  }

  public String getDimensionName() {
    return dimensionName;
  }

  public void setDimensionName(final String dimensionName) {
    this.dimensionName = dimensionName;
  }

  public LocalDateTime getDateTime() {
    return dateTime;
  }

  public void setDateTime(final LocalDateTime dateTime) {
    this.dateTime = dateTime;
  }

  public Map<String, Long> getCountDeltas() {
    this.countDeltas = (this.countDeltas == null ? new HashMap<>() : this.countDeltas);
    return this.countDeltas;
  }

  public void setCountDeltas(final Map<String, Long> countDeltas) {
    this.countDeltas = countDeltas;
  }

  public Map<String, BigDecimal> getWeightDeltas() {
    this.weightDeltas = (this.weightDeltas == null ? new HashMap<>() : this.weightDeltas);
    return this.weightDeltas;
  }

  public void setWeightDeltas(final Map<String, BigDecimal> weightDeltas) {
    this.weightDeltas = weightDeltas;
  }

  /**
   * Adds a count and weight increment for a dimension value.
   *
   * @param dimensionValue Dimension value.
   * @param count Count delta.
   * @param weight Weight delta.
   */
  public void addDelta(final String dimensionValue, final long count, final BigDecimal weight) {
    this.getCountDeltas().merge(dimensionValue, count, Long::sum);
    this.getWeightDeltas().merge(dimensionValue, weight, BigDecimal::add);
  }

  /**
   * Returns the summary key for this delta.
   *
   * @return The summary key.
   */
  public StatisticsEventSummaryKey getKey() {
    return new StatisticsEventSummaryKey(this.context, this.dimensionName, this.dateTime);
  }

  /**
   * @see Reduceable#reduce(Object)
   */
  @Override
  public synchronized void reduce(final StatisticsEventSummaryDelta toBeReduced) {
    toBeReduced.getCountDeltas().forEach((key, value) -> this.getCountDeltas().merge(key, value, Long::sum));
    toBeReduced
        .getWeightDeltas()
        .forEach((key, value) -> this.getWeightDeltas().merge(key, value, BigDecimal::add));
  }
}
