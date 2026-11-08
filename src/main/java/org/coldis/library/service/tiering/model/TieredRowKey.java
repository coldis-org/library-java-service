package org.coldis.library.service.tiering.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Key of one row of a tiered table: the message of the upload and touch queues, the item of the
 * backfill batch and its cursor. Values travel as strings (ISO for dates and times) so the key
 * serializes the same way over JMS and in the batch record; the repositories bind them with the
 * column types read from the database. {@link #toString()} never shows the values (they are
 * business ids).
 *
 * @param tableId Tiered table id.
 * @param values  Key column values, by column name.
 */
public record TieredRowKey(String tableId, Map<String, String> values) implements Serializable {

	/**
	 * Canonical constructor: an immutable copy of the values, iterated in column-name order, so
	 * two keys with the same values are equal and serialize the same way.
	 *
	 * @param tableId Tiered table id.
	 * @param values  Key column values.
	 */
	public TieredRowKey {
		values = Collections.unmodifiableSortedMap(new TreeMap<>(values == null ? Map.of() : values));
	}

	/**
	 * @see java.lang.Record#toString()
	 */
	@Override
	public String toString() {
		return "TieredRowKey[tableId=" + this.tableId + ", columns=" + this.values.size() + "]";
	}

}
