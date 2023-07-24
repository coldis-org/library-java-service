package org.coldis.library.service.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.TreeSet;

import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * Rate limit stats.
 */
@JsonTypeName(value = RateLimitStats.TYPE_NAME)
public class RateLimitStats implements Typable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 2765305635524612046L;

	/**
	 * Rate limit stats.
	 */
	public static final String TYPE_NAME = "org.coldis.library.service.ratelimit.RateLimitStats";

	/**
	 * Period.
	 */
	private Duration period;

	/**
	 * Executions.
	 */
	private TreeSet<Long> executions;

	/**
	 * No arguments constructor.
	 */
	public RateLimitStats() {
		super();
	}

	/**
	 * Default constructor.
	 *
	 * @param period Period.
	 */
	public RateLimitStats(final Duration period) {
		super();
		this.period = period;
	}

	/**
	 * Gets the period.
	 *
	 * @return The period.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getPeriod() {
		// Makes sure the period is initialized.
		this.period = (this.period == null ? Duration.ofMinutes(1) : this.period);
		// Returns the period.
		return this.period;
	}

	/**
	 * Sets the period.
	 *
	 * @param period New period.
	 */
	public void setPeriod(
			final Duration period) {
		this.period = period;
	}

	/**
	 * Gets the executions.
	 *
	 * @return The executions.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public TreeSet<Long> getExecutions() {
		// Makes sure the set is initialized.
		this.executions = (this.executions == null ? new TreeSet<>() : this.executions);
		// Drops expired executions.
		this.executions.removeIf(execution -> execution < (System.nanoTime() - (this.period.toNanos())));
		// Returns the set.
		return this.executions;
	}

	/**
	 * Sets the executions.
	 *
	 * @param executions New executions.
	 */
	public void setExecutions(
			final TreeSet<Long> executions) {
		this.executions = executions;
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getTypeName() {
		return RateLimitStats.TYPE_NAME;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.executions, this.period);
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (this.getClass() != obj.getClass())) {
			return false;
		}
		final RateLimitStats other = (RateLimitStats) obj;
		return Objects.equals(this.executions, other.executions) && Objects.equals(this.period, other.period);
	}

}
