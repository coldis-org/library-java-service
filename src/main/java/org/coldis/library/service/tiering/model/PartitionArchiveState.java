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
	STALE,

	/**
	 * A reload of a swept partition holds fewer rows than its archive: not exported (it would
	 * replace a complete archive with a partial one), not swept; an operator decides.
	 */
	NEEDS_ATTENTION;

	/**
	 * Whether the partition may be swept.
	 *
	 * @return Sweepable.
	 */
	public Boolean isSweepable() {
		return this == PartitionArchiveState.VERIFIED;
	}

}
