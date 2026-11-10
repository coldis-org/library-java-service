package org.coldis.library.service.tiering.model;

import java.util.List;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;

/**
 * A range-partitioned hot table archived one leaf partition at a time as sharded objects. The
 * shard key is the first key column (and must lead the primary key); the partition column is a
 * key column and the table's range partition key. Always versioned on the partition column,
 * always {@link TtlClock#LAST_ACCESS} and {@link Rehydration#INSERT}.
 */
public class PartitionTieredTable extends TieredTable {

	/** Partition column. */
	private String partitionColumn;

	/**
	 * Constructor.
	 *
	 * @param id         Table id.
	 * @param tableName  Parent table name.
	 * @param keyColumns Primary key columns, the shard key first.
	 */
	public PartitionTieredTable(
			final String id,
			final String tableName,
			final List<String> keyColumns) {
		super(id, tableName, keyColumns);
		this.withVersioned();
		this.partitionColumn = this.getLastKeyColumn();
	}

	/**
	 * @see TieredTable#getArchiveUnit()
	 */
	@Override
	public ArchiveUnit getArchiveUnit() {
		return ArchiveUnit.PARTITION;
	}

	/**
	 * Shard key column: the first key column.
	 *
	 * @return Column.
	 */
	public String getShardKeyColumn() {
		return this.getKeyColumns().get(0);
	}

	/**
	 * Partition column (the range partition key; the last key column by default).
	 *
	 * @return Column.
	 */
	public String getPartitionColumn() {
		return this.partitionColumn;
	}

	/**
	 * Sets the partition column.
	 *
	 * @param  partitionColumn Column, one of the key columns.
	 * @return                 This.
	 */
	public PartitionTieredTable withPartitionColumn(
			final String partitionColumn) {
		if (!this.getKeyColumns().contains(TieredTable.identifier(partitionColumn))) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.partition.column.invalid", new Object[] { this.getId(), partitionColumn }));
		}
		this.partitionColumn = partitionColumn;
		return this;
	}

	/**
	 * Sets the shard key column, which must be the first key column.
	 *
	 * @param  shardKeyColumn Column.
	 * @return                This.
	 */
	public PartitionTieredTable withShardKeyColumn(
			final String shardKeyColumn) {
		if (!this.getShardKeyColumn().equals(TieredTable.identifier(shardKeyColumn))) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.shard.column.invalid", new Object[] { this.getId(), shardKeyColumn }));
		}
		return this;
	}

	/**
	 * @see TieredTable#withTtlClock(TtlClock, String)
	 */
	@Override
	public TieredTable withTtlClock(
			final TtlClock ttlClock,
			final String clockColumn) {
		if (ttlClock != TtlClock.LAST_ACCESS) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.partition.clock.invalid", new Object[] { this.getId() }));
		}
		return super.withTtlClock(ttlClock, clockColumn);
	}

	/**
	 * @see TieredTable#withRehydration(Rehydration)
	 */
	@Override
	public TieredTable withRehydration(
			final Rehydration rehydration) {
		if (rehydration != Rehydration.INSERT) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.partition.rehydration.invalid", new Object[] { this.getId() }));
		}
		return super.withRehydration(rehydration);
	}

	/**
	 * @see TieredTable#withEntity(Class, List)
	 */
	@Override
	public PartitionTieredTable withEntity(
			final Class<?> entityClass,
			final List<String> keyProperties) {
		super.withEntity(entityClass, keyProperties);
		return this;
	}

}
