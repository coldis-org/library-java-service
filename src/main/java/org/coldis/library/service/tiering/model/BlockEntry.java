package org.coldis.library.service.tiering.model;

import java.util.List;

/**
 * One block of a shard: the canonical key segments of its first and last row, its byte range in
 * the blocks object, its row count and the CRC32C of the frame.
 */
public record BlockEntry(List<String> first, List<String> last, Long offset, Integer length, Integer rows, String crc32c) {

	/**
	 * Compares two canonical key segment lists element by element (a prefix compares as its
	 * elements only, so a shorter prefix equal to the start of a longer key compares as 0).
	 *
	 * @param  left  Left.
	 * @param  right Right.
	 * @return       Comparison.
	 */
	public static int compareKeys(
			final List<String> left,
			final List<String> right) {
		int comparison = 0;
		final int length = Math.min(left.size(), right.size());
		for (int index = 0; (index < length) && (comparison == 0); index++) {
			comparison = left.get(index).compareTo(right.get(index));
		}
		return comparison;
	}

}
