package org.coldis.library.service.tiering.model;

/**
 * How a partition's shard boundaries are chosen at export.
 */
public enum ShardStrategy {

	/** Equal row counts: boundaries cut during a key-only pass over the primary key. */
	EQUI_DEPTH,

	/** Fixed prefixes of the shard key (for a key known to be uniform). */
	PREFIX;

}
