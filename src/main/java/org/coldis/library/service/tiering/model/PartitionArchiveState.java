package org.coldis.library.service.tiering.model;

/**
 * State of a partition's archive record (the per-partition archive guard).
 */
public enum PartitionArchiveState {

	/** The consumer signalled the partition complete; not exported yet. */
	LOADED,

	/** Shards are being exported. */
	EXPORTING,

	/** Every shard exported and verified, manifest written: readable cold, sweepable. */
	VERIFIED,

	/** Rows changed after the export: readable cold (the hot row wins), never swept, re-exported. */
	STALE;

	/**
	 * Whether cold reads may use the partition's objects.
	 *
	 * @return Readable.
	 */
	public Boolean isReadable() {
		return (this == PartitionArchiveState.VERIFIED) || (this == PartitionArchiveState.STALE);
	}

}
