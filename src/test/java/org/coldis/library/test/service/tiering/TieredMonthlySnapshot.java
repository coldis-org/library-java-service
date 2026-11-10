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
 * Partition-tiered test entity: a monthly snapshot per owner, on a table the test configuration
 * recreates as {@code PARTITION BY RANGE (cached_at)}.
 */
@Entity
@Table(name = "tiered_monthly_snapshot")
@IdClass(TieredMonthlySnapshotKey.class)
public class TieredMonthlySnapshot implements Serializable {

	/** Serial. */
	private static final long serialVersionUID = -3339742211140957325L;

	/** Owner. */
	private String ownerId;

	/** Moment. */
	private LocalDateTime cachedAt;

	/** Payload. */
	private Map<String, Object> data;

	/** Created. */
	private LocalDateTime createdAt;

	/** Updated. */
	private LocalDateTime updatedAt;

	/** Last accessed. */
	private LocalDateTime lastAccessedAt;

	/** Archived. */
	private LocalDateTime archivedAt;

	/**
	 * No-args constructor.
	 */
	protected TieredMonthlySnapshot() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param ownerId  Owner.
	 * @param cachedAt Moment.
	 * @param data     Payload.
	 */
	public TieredMonthlySnapshot(
			final String ownerId,
			final LocalDateTime cachedAt,
			final Map<String, Object> data) {
		this.ownerId = ownerId;
		this.cachedAt = cachedAt;
		this.data = data;
		this.createdAt = DateTimeHelper.getCurrentLocalDateTime();
		this.updatedAt = this.createdAt;
	}

	@Id
	public String getOwnerId() {
		return this.ownerId;
	}

	public void setOwnerId(
			final String ownerId) {
		this.ownerId = ownerId;
	}

	@Id
	@Column(columnDefinition = "TIMESTAMP")
	public LocalDateTime getCachedAt() {
		return this.cachedAt;
	}

	public void setCachedAt(
			final LocalDateTime cachedAt) {
		this.cachedAt = cachedAt;
	}

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "JSONB")
	public Map<String, Object> getData() {
		return this.data;
	}

	public void setData(
			final Map<String, Object> data) {
		this.data = data;
	}

	@Column(
			columnDefinition = "TIMESTAMPTZ",
			nullable = false
	)
	public LocalDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(
			final LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	@Column(
			columnDefinition = "TIMESTAMP",
			nullable = false
	)
	public LocalDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(
			final LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	@Column(columnDefinition = "TIMESTAMP")
	public LocalDateTime getLastAccessedAt() {
		return this.lastAccessedAt;
	}

	public void setLastAccessedAt(
			final LocalDateTime lastAccessedAt) {
		this.lastAccessedAt = lastAccessedAt;
	}

	@Column(columnDefinition = "TIMESTAMP")
	public LocalDateTime getArchivedAt() {
		return this.archivedAt;
	}

	public void setArchivedAt(
			final LocalDateTime archivedAt) {
		this.archivedAt = archivedAt;
	}

}
