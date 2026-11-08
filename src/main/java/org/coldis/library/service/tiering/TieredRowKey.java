package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.util.Map;
import java.util.TreeMap;

/**
 * Key of one row of a tiered table: the message of the upload and touch queues, the item of the
 * backfill batch and its cursor. Values travel as strings (ISO for dates and times) so the key
 * serializes the same way over JMS and in the batch record; {@link HotTierRepository} binds them
 * with the column types read from the database.
 *
 * @param tableId Tiered table id.
 * @param values  Key column values, by column name.
 */
public record TieredRowKey(String tableId, Map<String, String> values) implements Serializable {

	/**
	 * Canonical constructor: keeps the values in column-name order so equal keys compare equal.
	 *
	 * @param tableId Tiered table id.
	 * @param values  Key column values.
	 */
	public TieredRowKey {
		values = values == null ? Map.of() : Map.copyOf(new TreeMap<>(values));
	}

}
