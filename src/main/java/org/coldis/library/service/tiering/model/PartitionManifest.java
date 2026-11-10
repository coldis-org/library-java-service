package org.coldis.library.service.tiering.model;

import java.util.List;
import java.util.Map;

/**
 * Manifest of one export (generation) of a partition: the shard boundaries (the authority for a
 * key's shard) and the shards' totals. Written last, under the generation's own keys; the
 * archive record points at the readable one.
 */
public record PartitionManifest(Integer formatVersion, String tableId, String partition, String generation, String rangeFrom, String rangeTo, List<String> keyColumns,
		Map<String, Object> shardStrategy, List<String> boundaries, List<ShardSummary> shards, Long rows, String exportStartedAt, String exportedAt) {

	/** Current format version. */
	public static final Integer FORMAT_VERSION = 1;

	/**
	 * One shard's totals.
	 */
	public record ShardSummary(Integer shard, Long rows, Integer blocks, Long sizeBytes) {

	}

	/**
	 * Shard of a shard-key segment: the last boundary not above it (boundaries are lower bounds,
	 * the first is the empty string).
	 *
	 * @param  boundaries Lower bounds, ascending.
	 * @param  segment    Canonical shard key segment.
	 * @return            Shard number.
	 */
	public static Integer shardOf(
			final List<String> boundaries,
			final String segment) {
		int low = 0;
		int high = boundaries.size() - 1;
		int found = 0;
		while (low <= high) {
			final int middle = (low + high) >>> 1;
			if (boundaries.get(middle).compareTo(segment) <= 0) {
				found = middle;
				low = middle + 1;
			}
			else {
				high = middle - 1;
			}
		}
		return found;
	}

	/**
	 * Shard of a shard-key segment.
	 *
	 * @param  segment Canonical shard key segment.
	 * @return         Shard number.
	 */
	public Integer shardOf(
			final String segment) {
		return PartitionManifest.shardOf(this.boundaries, segment);
	}

}
