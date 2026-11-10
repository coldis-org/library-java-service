package org.coldis.library.service.tiering.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The per-partition archive record (the archive guard of partition tiering). {@code manifestKey}
 * points at the readable export; {@code generation} names the export in progress or last done.
 */
public record PartitionArchive(String tableId, String partitionName, String rangeFrom, String rangeTo, PartitionArchiveState state, LocalDateTime loadedAt,
		LocalDateTime exportStartedAt, LocalDateTime exportedAt, LocalDateTime verifiedAt, LocalDateTime sweptAt, Integer formatVersion, Integer shardCount,
		Integer shardsDone, Long rowCount, Long loadedRows, Long sizeBytes, String generation, Boolean reexportConfirmed, List<String> boundaries,
		List<PartitionManifest.ShardSummary> shards, Long sweepBlocks, Long sweepCursor, Long sweepDeleted, Long sweepKept, String manifestKey) {

	/**
	 * Whether cold reads may use the partition's objects: a manifest exists (the previous export
	 * stays readable while a new one runs).
	 *
	 * @return Readable.
	 */
	public Boolean isReadable() {
		return this.manifestKey != null;
	}

	/**
	 * Whether a sweep started on the current archive (rows may have left the hot table).
	 *
	 * @return Swept or sweeping.
	 */
	public Boolean isSweepStarted() {
		return (this.sweptAt != null) || (this.sweepCursor != null);
	}

	/**
	 * Moment the sweep hold counts from: the loader's signal, else the verification.
	 *
	 * @return Hold start, null when neither is set.
	 */
	public LocalDateTime holdStart() {
		return this.loadedAt == null ? this.verifiedAt : this.loadedAt;
	}

}
