package org.coldis.library.test.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Monthly snapshot key.
 */
public class TieredMonthlySnapshotKey implements Serializable {

	/** Serial. */
	private static final long serialVersionUID = 2871165404129811934L;

	/** Owner. */
	private String ownerId;

	/** Moment. */
	private LocalDateTime cachedAt;

	/**
	 * No-args constructor.
	 */
	public TieredMonthlySnapshotKey() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param ownerId  Owner.
	 * @param cachedAt Moment.
	 */
	public TieredMonthlySnapshotKey(
			final String ownerId,
			final LocalDateTime cachedAt) {
		this.ownerId = ownerId;
		this.cachedAt = cachedAt;
	}

	public String getOwnerId() {
		return this.ownerId;
	}

	public void setOwnerId(
			final String ownerId) {
		this.ownerId = ownerId;
	}

	public LocalDateTime getCachedAt() {
		return this.cachedAt;
	}

	public void setCachedAt(
			final LocalDateTime cachedAt) {
		this.cachedAt = cachedAt;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.ownerId, this.cachedAt);
	}

	@Override
	public boolean equals(
			final Object other) {
		return (other instanceof final TieredMonthlySnapshotKey key) && Objects.equals(this.ownerId, key.ownerId) && Objects.equals(this.cachedAt, key.cachedAt);
	}

}
