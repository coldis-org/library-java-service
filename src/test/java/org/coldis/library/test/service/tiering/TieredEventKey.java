package org.coldis.library.test.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Composite key of {@link TieredEvent}.
 */
public class TieredEventKey implements Serializable {

	private static final long serialVersionUID = -2010493350137228155L;

	/** Source. */
	private String source;

	/** Moment. */
	private LocalDateTime occurredAt;

	/**
	 * No-arguments constructor.
	 */
	public TieredEventKey() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param source     Source.
	 * @param occurredAt Moment.
	 */
	public TieredEventKey(
			final String source,
			final LocalDateTime occurredAt) {
		this.source = source;
		this.occurredAt = occurredAt;
	}

	/**
	 * Gets the source.
	 *
	 * @return The source.
	 */
	public String getSource() {
		return this.source;
	}

	/**
	 * Sets the source.
	 *
	 * @param source New source.
	 */
	public void setSource(
			final String source) {
		this.source = source;
	}

	/**
	 * Gets the moment.
	 *
	 * @return The moment.
	 */
	public LocalDateTime getOccurredAt() {
		return this.occurredAt;
	}

	/**
	 * Sets the moment.
	 *
	 * @param occurredAt New moment.
	 */
	public void setOccurredAt(
			final LocalDateTime occurredAt) {
		this.occurredAt = occurredAt;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.occurredAt, this.source);
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object otherObject) {
		return (this == otherObject)
				|| ((otherObject instanceof final TieredEventKey other) && Objects.equals(this.occurredAt, other.occurredAt) && Objects.equals(this.source, other.source));
	}

}
