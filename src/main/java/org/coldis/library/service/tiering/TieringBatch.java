package org.coldis.library.service.tiering;

/**
 * Batches the cold tiering runs per table on the batch service.
 */
public enum TieringBatch {

	/** Deletes archived rows past the TTL. */
	EVICTION("eviction"),

	/** Archives rows whose current content is not in the cold tier. */
	BACKFILL("backfill"),

	/** Partition export (partition tiering). */
	EXPORT("export"),

	/** Partition sweep (partition tiering). */
	PARTITION_SWEEP("sweep");

	/** Key segment. */
	private final String keySegment;

	/**
	 * Constructor.
	 *
	 * @param keySegment Key segment.
	 */
	TieringBatch(final String keySegment) {
		this.keySegment = keySegment;
	}

	/**
	 * Batch key suffix for a table.
	 *
	 * @param  tableId Table id.
	 * @return         The key suffix.
	 */
	public String keySuffix(
			final String tableId) {
		return "cold-tiering-" + this.keySegment + "-" + tableId;
	}

}
