package org.coldis.library.service.statistics;

import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import org.coldis.library.model.view.ModelView;
import org.coldis.library.persistence.model.AbstractTimestampableEntity;

/**
 * Configuration for a statistics context. Defines settings such as the time truncation interval used
 * for bucketing events and summaries.
 */
@Entity
public class StatisticsContextConfiguration extends AbstractTimestampableEntity {

  /** Serial. */
  private static final long serialVersionUID = -3948271650382917465L;

  /** Default truncation in minutes. */
  public static final Long DEFAULT_TRUNCATION_MINUTES = 15L;

  /** Context. */
  private String context;

  /** Truncation in minutes. */
  private Long truncationMinutes;

  /** No arguments constructor. */
  public StatisticsContextConfiguration() {}

  /**
   * Full constructor.
   *
   * @param context Context.
   * @param truncationMinutes Truncation in minutes.
   */
  public StatisticsContextConfiguration(final String context, final Long truncationMinutes) {
    super();
    this.context = context;
    this.truncationMinutes = truncationMinutes;
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
   * Gets the truncation in minutes.
   *
   * @return The truncation in minutes.
   */
  @NotNull
  @jakarta.validation.constraints.Min(1)
  @jakarta.validation.constraints.Max(1440)
  @JsonView({ModelView.Persistent.class, ModelView.Public.class})
  public Long getTruncationMinutes() {
    this.truncationMinutes =
        (this.truncationMinutes == null ? DEFAULT_TRUNCATION_MINUTES : this.truncationMinutes);
    return this.truncationMinutes;
  }

  /**
   * Sets the truncation in minutes.
   *
   * @param truncationMinutes New truncation in minutes.
   */
  public void setTruncationMinutes(final Long truncationMinutes) {
    this.truncationMinutes = truncationMinutes;
  }

  /**
   * @see Object#hashCode()
   */
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = (prime * result) + Objects.hash(this.context, this.truncationMinutes);
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
    if (!(obj instanceof StatisticsContextConfiguration)) {
      return false;
    }
    final StatisticsContextConfiguration other = (StatisticsContextConfiguration) obj;
    return Objects.equals(this.context, other.context)
        && Objects.equals(this.truncationMinutes, other.truncationMinutes);
  }
}
