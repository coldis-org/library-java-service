package org.coldis.library.service.tiering.model;

import java.util.List;

/**
 * What every table under cold tiering has, whatever the kind (row tiering through
 * {@link TieredTable}; window tiering later): the parts the object key layout is built from. It
 * keeps {@link ObjectKeyStrategy} independent of the row-only parts of {@link TieredTable}.
 */
public interface ColdTable {

	/**
	 * Gets the id (the table's segment in object keys and its properties' name).
	 *
	 * @return The id.
	 */
	String getId();

	/**
	 * Gets the hot table name.
	 *
	 * @return The table name.
	 */
	String getTableName();

	/**
	 * Gets the key columns, in object key segment order.
	 *
	 * @return The key columns.
	 */
	List<String> getKeyColumns();

}
