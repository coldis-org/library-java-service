package org.coldis.library.service.tiering.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Index of one shard: the blocks object's layout, so a key is located with no read of the object.
 */
public record ShardIndex(Integer formatVersion, String tableId, String partition, Integer shard, String exportStartedAt, String blockCodec, List<String> keyColumns, Long rows,
		Long sizeBytes, List<BlockEntry> blocks) {

	/** Current format version. */
	public static final Integer FORMAT_VERSION = 1;

	/**
	 * Blocks that may hold rows starting with the given key prefix (segments): the block whose
	 * first key is the greatest not above the prefix, and every following block whose first key
	 * still matches the prefix.
	 *
	 * @param  prefix Canonical key segments (a prefix of the key).
	 * @return        Candidate blocks, in order; empty when the prefix falls in a gap or outside.
	 */
	public List<BlockEntry> blocksFor(
			final List<String> prefix) {
		final List<BlockEntry> candidates = new ArrayList<>();
		int low = 0;
		int high = this.blocks.size() - 1;
		int found = -1;
		while (low <= high) {
			final int middle = (low + high) >>> 1;
			if (BlockEntry.compareKeys(this.blocks.get(middle).first(), prefix) <= 0) {
				found = middle;
				low = middle + 1;
			}
			else {
				high = middle - 1;
			}
		}
		if ((found >= 0) && (BlockEntry.compareKeys(this.blocks.get(found).last(), prefix) >= 0)) {
			candidates.add(this.blocks.get(found));
		}
		for (int index = found + 1; (index < this.blocks.size()) && (BlockEntry.compareKeys(this.blocks.get(index).first(), prefix) == 0); index++) {
			candidates.add(this.blocks.get(index));
		}
		return candidates;
	}

}
