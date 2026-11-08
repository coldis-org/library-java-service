package org.coldis.library.test.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;

import org.coldis.library.helper.DateTimeHelper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Tiered entity with a single id, a text payload, a fixed age from its write and no rehydration:
 * the log-service shape (archived once, never hot again).
 */
@Entity
@Table(name = "tiered_archive_entry")
public class TieredArchiveEntry implements Serializable {

	private static final long serialVersionUID = 2370961489305112633L;

	/** Id. */
	private Long id;

	/** Payload. */
	private String content;

	/** Last update (watermark and TTL clock). */
	private LocalDateTime updatedAt;

	/** Archive moment. */
	private LocalDateTime archivedAt;

	/**
	 * No-arguments constructor.
	 */
	protected TieredArchiveEntry() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param id      Id.
	 * @param content Payload.
	 */
	public TieredArchiveEntry(
			final Long id,
			final String content) {
		this.id = id;
		this.content = content;
		this.updatedAt = DateTimeHelper.getCurrentLocalDateTime();
	}

	/**
	 * Gets the id.
	 *
	 * @return The id.
	 */
	@Id
	public Long getId() {
		return this.id;
	}

	/**
	 * Sets the id.
	 *
	 * @param id New id.
	 */
	public void setId(
			final Long id) {
		this.id = id;
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
