package org.coldis.library.service.tiering.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts equi-depth shard boundaries from a stream of shard-key segments in key order: a new
 * shard starts at the first key change after the target row count is reached, so every key's
 * rows stay in one shard. Boundaries are lower bounds; the first is the empty string.
 */
public class EquiDepthShardCutter {

	/** Target rows per shard. */
	private final Long target;

	/** Boundaries. */
	private final List<String> boundaries = new ArrayList<>(List.of(""));

	/** Rows in the current shard. */
	private Long rowsInShard = 0L;

	/** Rows seen. */
	private Long rows = 0L;

	/** Previous segment. */
	private String previous;

	/**
	 * Constructor.
	 *
	 * @param target Rows per shard.
	 */
	public EquiDepthShardCutter(
			final Long target) {
		this.target = Math.max(1L, target);
	}

	/**
	 * Offers the next key segment (key order).
	 *
	 * @param segment Shard key segment.
	 */
	public void offer(
			final String segment) {
		if ((this.rowsInShard >= this.target) && !segment.equals(this.previous)) {
			this.boundaries.add(segment);
			this.rowsInShard = 0L;
		}
		this.rowsInShard++;
		this.rows++;
		this.previous = segment;
	}

	/**
	 * Boundaries so far.
	 *
	 * @return Boundaries.
	 */
	public List<String> getBoundaries() {
		return List.copyOf(this.boundaries);
	}

	/**
	 * Rows seen.
	 *
	 * @return Rows.
	 */
	public Long getRows() {
		return this.rows;
	}

	/**
	 * Fixed-prefix boundaries: every prefix of the given number of digits.
	 *
	 * @param  digits Digits.
	 * @return        Boundaries (the first is the empty string, then {@code 00..1}, ...).
	 */
	public static List<String> prefixBoundaries(
			final Integer digits) {
		final List<String> boundaries = new ArrayList<>();
		boundaries.add("");
		final int count = (int) Math.pow(10, digits);
		for (int prefix = 1; prefix < count; prefix++) {
			boundaries.add(String.format("%0" + digits + "d", prefix));
		}
		return boundaries;
	}

}
