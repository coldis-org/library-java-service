package org.coldis.library.service.tiering.model;

/**
 * What one archive operation covers: a row (one object per row, row tiering) or a leaf partition
 * (sharded, indexed objects, partition tiering).
 */
public enum ArchiveUnit {

	/** One row, one object. */
	ROW,

	/** One leaf partition, N shards of blocks plus indexes and a manifest. */
	PARTITION;

}
