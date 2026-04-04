package org.coldis.library.service.statistics;

import java.io.Serializable;
import java.util.Objects;

/**
 * Groups a dimension name and its value. Used as a parameter object to replace the recurring
 * (dimensionName, dimensionValue) pair in service methods, and as a base class for timed variants.
 */
public class StatisticsValuedEventDimension implements Serializable {

  /** Serial. */
  private static final long serialVersionUID = -6182749305718294631L;

  /** Dimension name. */
  private String dimensionName;

  /** Dimension value. */
  private String dimensionValue;

  /** No arguments constructor. */
  public StatisticsValuedEventDimension() {}

  /**
   * Full constructor.
   *
   * @param dimensionName Dimension name.
   * @param dimensionValue Dimension value.
   */
  public StatisticsValuedEventDimension(final String dimensionName, final String dimensionValue) {
    super();
    this.dimensionName = dimensionName;
    this.dimensionValue = dimensionValue;
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
   * Gets the dimension value.
   *
   * @return The dimension value.
   */
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
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.dimensionName, this.dimensionValue);
  }

  /**
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StatisticsValuedEventDimension)) {
      return false;
    }
    final StatisticsValuedEventDimension other = (StatisticsValuedEventDimension) obj;
    return Objects.equals(this.dimensionName, other.dimensionName)
        && Objects.equals(this.dimensionValue, other.dimensionValue);
  }
}
