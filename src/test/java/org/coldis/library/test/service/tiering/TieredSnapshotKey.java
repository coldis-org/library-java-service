package org.coldis.library.test.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Composite key of {@link TieredSnapshot}.
 */
public class TieredSnapshotKey implements Serializable {

	private static final long serialVersionUID = 4723318596210745287L;

	/** Owner. */
	private String ownerId;

	/** Snapshot moment. */
	private LocalDateTime cachedAt;

	/**
	 * No-arguments constructor.
	 */
	public TieredSnapshotKey() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param ownerId  Owner.
	 * @param cachedAt Snapshot moment.
	 */
	public TieredSnapshotKey(
			final String ownerId,
			final LocalDateTime cachedAt) {
		this.ownerId = ownerId;
		this.cachedAt = cachedAt;
	}

	/**
	 * Gets the owner.
	 *
	 * @return The owner.
	 */
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
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.cachedAt, this.ownerId);
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object otherObject) {
		return (this == otherObject) || ((otherObject instanceof final TieredSnapshotKey other) && Objects.equals(this.cachedAt, other.cachedAt)
				&& Objects.equals(this.ownerId, other.ownerId));
	}

}
