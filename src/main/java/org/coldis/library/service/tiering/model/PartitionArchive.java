package org.coldis.library.service.tiering.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The per-partition archive record (the archive guard of partition tiering).
 */
public record PartitionArchive(String tableId, String partitionName, String rangeFrom, String rangeTo, PartitionArchiveState state, LocalDateTime loadedAt,
		LocalDateTime exportStartedAt, LocalDateTime exportedAt, LocalDateTime verifiedAt, LocalDateTime sweptAt, Integer formatVersion, Integer shardCount,
		Integer shardsDone, Long rowCount, Long sizeBytes, List<String> boundaries, List<PartitionManifest.ShardSummary> shards, Long sweepBlocks,
		Long sweepCursor, Long sweepDeleted, Long sweepKept, String manifestKey) {

	/**
	 * Moment the sweep hold counts from: the loader's signal, else the verification.
	 *
	 * @return Hold start, null when neither is set.
	 */
	public LocalDateTime holdStart() {
		return this.loadedAt == null ? this.verifiedAt : this.loadedAt;
	}

}
