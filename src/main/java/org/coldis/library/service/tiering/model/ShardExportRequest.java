package org.coldis.library.service.tiering.model;

import java.io.Serializable;

/**
 * One shard to export: the JMS message of {@code cold-tiering/export}. Bounds are canonical
 * shard key segments; {@code to} is null on the last shard.
 */
public record ShardExportRequest(String tableId, String partition, String generation, Integer shard, String from, String to, String exportStartedAt)
		implements Serializable {

	/**
	 * @see Object#toString()
	 */
	@Override
	public String toString() {
		return "ShardExportRequest[tableId=" + this.tableId + ", partition=" + this.partition + ", generation=" + this.generation + ", shard=" + this.shard + "]";
	}

}
