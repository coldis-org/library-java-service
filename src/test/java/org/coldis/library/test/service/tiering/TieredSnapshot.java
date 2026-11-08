package org.coldis.library.test.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

import org.coldis.library.helper.DateTimeHelper;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Tiered entity with a composite key (owner + moment) and a JSONB payload: the data-gathering
 * shape (snapshots read by key, latest and as-of).
 */
@Entity
@Table(name = "tiered_snapshot")
@IdClass(TieredSnapshotKey.class)
public class TieredSnapshot implements Serializable {

	private static final long serialVersionUID = -6260538329011237284L;

	/** Owner. */
	private String ownerId;

	/** Snapshot moment. */
	private LocalDateTime cachedAt;

	/** Payload. */
	private Map<String, Object> data;

	/** Creation. */
	private LocalDateTime createdAt;

	/** Last update (watermark). */
	private LocalDateTime updatedAt;

	/** Last access (tiering clock). */
	private LocalDateTime lastAccessedAt;

	/** Archive moment. */
	private LocalDateTime archivedAt;

	/**
	 * No-arguments constructor.
	 */
	protected TieredSnapshot() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param ownerId  Owner.
	 * @param cachedAt Snapshot moment.
	 * @param data     Payload.
	 */
	public TieredSnapshot(
			final String ownerId,
			final LocalDateTime cachedAt,
			final Map<String, Object> data) {
		this.ownerId = ownerId;
		this.cachedAt = cachedAt;
		this.data = data;
		this.createdAt = DateTimeHelper.getCurrentLocalDateTime();
		this.updatedAt = this.createdAt;
	}

	/**
	 * Gets the owner.
	 *
	 * @return The owner.
	 */
	@Id
	public String getOwnerId() {
		return this.ownerId;
	}

	/**
	 * Sets the owner.
	 *
	 * @param ownerId New owner.
	 */
	public void setOwnerId(
			final String ownerId) {
		this.ownerId = ownerId;
	}

	/**
	 * Gets the snapshot moment.
	 *
	 * @return The snapshot moment.
	 */
	@Id
	@Column(columnDefinition = "TIMESTAMP")
	public LocalDateTime getCachedAt() {
		return this.cachedAt;
	}

	/**
	 * Sets the snapshot moment.
	 *
	 * @param cachedAt New snapshot moment.
	 */
	public void setCachedAt(
			final LocalDateTime cachedAt) {
		this.cachedAt = cachedAt;
	}

	/**
	 * Gets the payload.
	 *
	 * @return The payload.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "JSONB")
	public Map<String, Object> getData() {
		return this.data;
	}

	/**
	 * Sets the payload.
	 *
	 * @param data New payload.
	 */
	public void setData(
			final Map<String, Object> data) {
		this.data = data;
	}

	/**
	 * Gets the creation.
	 *
	 * @return The creation.
	 */
	@Column(
			columnDefinition = "TIMESTAMPTZ",
			nullable = false
	)
	public LocalDateTime getCreatedAt() {
		return this.createdAt;
	}

	/**
	 * Sets the creation.
	 *
	 * @param createdAt New creation.
	 */
	public void setCreatedAt(
			final LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	/**
	 * Gets the last update.
	 *
	 * @return The last update.
	 */
	@Column(
			columnDefinition = "TIMESTAMP",
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
	 * Gets the last access.
	 *
	 * @return The last access.
	 */
	@Column(columnDefinition = "TIMESTAMP")
	public LocalDateTime getLastAccessedAt() {
		return this.lastAccessedAt;
	}

	/**
	 * Sets the last access.
	 *
	 * @param lastAccessedAt New last access.
	 */
	public void setLastAccessedAt(
			final LocalDateTime lastAccessedAt) {
		this.lastAccessedAt = lastAccessedAt;
	}

	/**
	 * Gets the archive moment.
	 *
	 * @return The archive moment.
	 */
	@Column(columnDefinition = "TIMESTAMP")
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
