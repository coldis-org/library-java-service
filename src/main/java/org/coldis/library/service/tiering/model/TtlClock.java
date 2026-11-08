package org.coldis.library.service.tiering.model;

/**
 * Which timestamp of a hot row drives its hot-tier TTL.
 */
public enum TtlClock {

	/**
	 * Sliding window after the last write or read:
	 * {@code COALESCE(<last accessed column>, <watermark column>)}. Reads touch the row.
	 */
	LAST_ACCESS,

	/** Window after the last write only ({@code <watermark column>}). Reads never touch. */
	LAST_WRITE,

	/** Fixed age from a column of the consumer's choice ({@link TieredTable#getClockColumn()}). Reads never touch. */
	COLUMN;

}
