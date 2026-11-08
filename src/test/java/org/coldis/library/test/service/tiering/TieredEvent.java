package org.coldis.library.test.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;

import org.coldis.library.helper.DateTimeHelper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Tiered entity keyed by a source and a {@code TIMESTAMPTZ} moment mapped as
 * {@code LocalDateTime}, aged by that moment ({@code TtlClock.COLUMN}), never rehydrated.
 */
@Entity
@Table(name = "tiered_event")
@IdClass(TieredEventKey.class)
public class TieredEvent implements Serializable {

	private static final long serialVersionUID = 7418856925302310044L;

	/** Source. */
	private String source;

	/** Moment. */
	private LocalDateTime occurredAt;

	/** Payload. */
	private String content;

	/** Last update (watermark). */
	private LocalDateTime updatedAt;

	/** Archive moment. */
	private LocalDateTime archivedAt;

	/**
	 * No-arguments constructor.
	 */
	protected TieredEvent() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param source     Source.
	 * @param occurredAt Moment.
	 * @param content    Payload.
	 */
	public TieredEvent(
			final String source,
			final LocalDateTime occurredAt,
			final String content) {
		this.source = source;
		this.occurredAt = occurredAt;
		this.content = content;
		this.updatedAt = DateTimeHelper.getCurrentLocalDateTime();
	}

	/**
	 * Gets the source.
	 *
	 * @return The source.
	 */
	@Id
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
	@Id
	@Column(columnDefinition = "TIMESTAMPTZ")
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
	 * Gets the payload.
	 *
	 * @return The payload.
	 */
	@Column(columnDefinition = "TEXT")
	public String getContent() {
		return this.content;
	}

	/**
	 * Sets the payload.
	 *
	 * @param content New payload.
	 */
	public void setContent(
			final String content) {
		this.content = content;
	}

	/**
	 * Gets the last update.
	 *
	 * @return The last update.
	 */
	@Column(
			columnDefinition = "TIMESTAMPTZ",
			nullable = false
	)
	public LocalDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	/**
	 * Sets the last update.
	 *
	 * @param updatedAt New last update.
	 */
	public void setUpdatedAt(
			final LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	/**
	 * Gets the archive moment.
	 *
	 * @return The archive moment.
	 */
	@Column(columnDefinition = "TIMESTAMPTZ")
	public LocalDateTime getArchivedAt() {
		return this.archivedAt;
	}

	/**
	 * Sets the archive moment.
	 *
	 * @param archivedAt New archive moment.
	 */
	public void setArchivedAt(
			final LocalDateTime archivedAt) {
		this.archivedAt = archivedAt;
	}

}
