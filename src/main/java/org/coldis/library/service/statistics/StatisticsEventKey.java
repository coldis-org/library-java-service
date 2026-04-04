package org.coldis.library.service.statistics;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link StatisticsEvent}. */
public class StatisticsEventKey implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = -3847562918374652810L;

  /** Context. */
  private String context;

  /** Owner key. */
  private String ownerKey;

  /** Dimension name. */
  private String dimensionName;

  /** No arguments constructor. */
  public StatisticsEventKey() {}

  /**
   * Full constructor.
   *
   * @param context Context.
   * @param ownerKey Owner key.
   * @param dimensionName Dimension name.
   */
  public StatisticsEventKey(
      final String context, final String ownerKey, final String dimensionName) {
    super();
    this.context = context;
    this.ownerKey = ownerKey;
    this.dimensionName = dimensionName;
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
   * Gets the owner key.
   *
   * @return The owner key.
   */
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
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.context, this.ownerKey, this.dimensionName);
  }

  /**
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StatisticsEventKey)) {
      return false;
    }
    final StatisticsEventKey other = (StatisticsEventKey) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.ownerKey, other.ownerKey)
        && Objects.equals(this.dimensionName, other.dimensionName);
  }
}
