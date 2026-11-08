package org.coldis.library.service.tiering.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;

/**
 * Definition of one hot table under cold tiering: its structure (what the SQL and the object keys
 * are generated from). The tunables (TTL, flags, chunk sizes, crons) are properties under
 * {@code org.coldis.library.service.tiering.table.<id>.}. A consumer declares one bean per table.
 */
public class TieredTable implements ColdTable {

	/** Default watermark column. */
	public static final String DEFAULT_WATERMARK_COLUMN = "updated_at";

	/** Default last-access column. */
	public static final String DEFAULT_LAST_ACCESSED_COLUMN = "last_accessed_at";

	/** Default archived-at column. */
	public static final String DEFAULT_ARCHIVED_AT_COLUMN = "archived_at";

	/** What a table or column name may be (it goes into generated SQL unquoted). */
	public static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

	/** Id (property key, batch key, object key segment). */
	private final String id;

	/** Hot table name. */
	private final String tableName;

	/** Key columns, in primary key order. The last one names the object. */
	private final List<String> keyColumns;

	/** Column bumped by every write (the data pipeline's watermark). */
	private String watermarkColumn = TieredTable.DEFAULT_WATERMARK_COLUMN;

	/** Last access column (sliding TTL). */
	private String lastAccessedColumn = TieredTable.DEFAULT_LAST_ACCESSED_COLUMN;

	/** Archived-at column. */
	private String archivedAtColumn = TieredTable.DEFAULT_ARCHIVED_AT_COLUMN;

	/** Whether the last key column is a version/time, so "newest on or before" reads exist. */
	private Boolean versioned = false;

	/** TTL clock. */
	private TtlClock ttlClock = TtlClock.LAST_ACCESS;

	/** Clock column for {@link TtlClock#COLUMN}. */
	private String clockColumn;

	/** Default rehydration (the table property overrides it). */
	private Rehydration rehydration = Rehydration.INSERT;

	/** Entity class, when a {@link TieredRepository} reads the table. */
	private Class<?> entityClass;

	/** Id properties of the entity's key object, in key column order (defaults to the camel case of the key columns). */
	private List<String> keyProperties;

	/** SQL types of the hot table's columns, bound by the registry at startup. */
	private Map<String, String> columnTypes;

	/**
	 * Constructor.
	 *
	 * @param id         Id.
	 * @param tableName  Hot table name.
	 * @param keyColumns Key columns, in primary key order.
	 */
	public TieredTable(
			final String id,
			final String tableName,
			final List<String> keyColumns) {
		this.id = Objects.requireNonNull(id);
		this.tableName = TieredTable.identifier(tableName);
		this.keyColumns = List.copyOf(Objects.requireNonNull(keyColumns));
		if (this.keyColumns.isEmpty()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.key.empty", new Object[] { id }));
		}
		for (final String keyColumn : this.keyColumns) {
			TieredTable.identifier(keyColumn);
		}
	}

	/**
	 * Checks a SQL identifier before it reaches generated SQL.
	 *
	 * @param  name Name.
	 * @return      The name.
	 */
	public static String identifier(
			final String name) {
		if ((name == null) || !TieredTable.IDENTIFIER.matcher(name).matches()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.identifier.invalid", new Object[] { name }));
		}
		return name;
	}

	/**
	 * Camel case of a snake case column.
	 *
	 * @param  column Column.
	 * @return        The property name.
	 */
	public static String toProperty(
			final String column) {
		final StringBuilder property = new StringBuilder();
		Boolean isWordStart = false;
		for (final char character : column.toCharArray()) {
			if (character == '_') {
				isWordStart = true;
			}
			else {
				property.append(isWordStart ? Character.toUpperCase(character) : character);
				isWordStart = false;
			}
		}
		return property.toString();
	}

	/**
	 * Gets the id.
	 *
	 * @return The id.
	 */
	@Override
	public String getId() {
		return this.id;
	}

	/**
	 * Gets the hot table name.
	 *
	 * @return The table name.
	 */
	@Override
	public String getTableName() {
		return this.tableName;
	}

	/**
	 * Gets the key columns.
	 *
	 * @return The key columns.
	 */
	@Override
	public List<String> getKeyColumns() {
		return this.keyColumns;
	}

	/**
	 * The last key column: the object's file name and, when versioned, the version/time.
	 *
	 * @return The column.
	 */
	public String getLastKeyColumn() {
		return this.keyColumns.get(this.keyColumns.size() - 1);
	}

	/**
	 * Gets the watermark column.
	 *
	 * @return The watermark column.
	 */
	public String getWatermarkColumn() {
		return this.watermarkColumn;
	}

	/**
	 * Sets the watermark column.
	 *
	 * @param  watermarkColumn Watermark column.
	 * @return                 This.
	 */
	public TieredTable withWatermarkColumn(
			final String watermarkColumn) {
		this.watermarkColumn = TieredTable.identifier(watermarkColumn);
		return this;
	}

	/**
	 * Gets the last-access column.
	 *
	 * @return The last-access column.
	 */
	public String getLastAccessedColumn() {
		return this.lastAccessedColumn;
	}

	/**
	 * Sets the last-access column.
	 *
	 * @param  lastAccessedColumn Last-access column.
	 * @return                    This.
	 */
	public TieredTable withLastAccessedColumn(
			final String lastAccessedColumn) {
		this.lastAccessedColumn = TieredTable.identifier(lastAccessedColumn);
		return this;
	}

	/**
	 * Gets the archived-at column.
	 *
	 * @return The archived-at column.
	 */
	public String getArchivedAtColumn() {
		return this.archivedAtColumn;
	}

	/**
	 * Sets the archived-at column.
	 *
	 * @param  archivedAtColumn Archived-at column.
	 * @return                  This.
	 */
	public TieredTable withArchivedAtColumn(
			final String archivedAtColumn) {
		this.archivedAtColumn = TieredTable.identifier(archivedAtColumn);
		return this;
	}

	/**
	 * Whether the last key column is a version/time.
	 *
	 * @return Whether "newest on or before" reads exist.
	 */
	public Boolean isVersioned() {
		return this.versioned;
	}

	/**
	 * Declares the last key column a version/time column: "newest on or before a moment" reads
	 * list the other key columns' prefix and pick the greatest name not after the moment.
	 *
	 * @return This.
	 */
	public TieredTable withVersioned() {
		this.versioned = true;
		return this;
	}

	/**
	 * Gets the TTL clock.
	 *
	 * @return The TTL clock.
	 */
	public TtlClock getTtlClock() {
		return this.ttlClock;
	}

	/**
	 * Gets the clock column.
	 *
	 * @return The clock column ({@link TtlClock#COLUMN}).
	 */
	public String getClockColumn() {
		return this.clockColumn;
	}

	/**
	 * Sets the TTL clock.
	 *
	 * @param  ttlClock    TTL clock.
	 * @param  clockColumn Clock column, for {@link TtlClock#COLUMN}; ignored otherwise.
	 * @return             This.
	 */
	public TieredTable withTtlClock(
			final TtlClock ttlClock,
			final String clockColumn) {
		this.ttlClock = ttlClock;
		this.clockColumn = ttlClock == TtlClock.COLUMN ? TieredTable.identifier(clockColumn) : null;
		return this;
	}

	/**
	 * Gets the default rehydration.
	 *
	 * @return The rehydration.
	 */
	public Rehydration getRehydration() {
		return this.rehydration;
	}

	/**
	 * Sets the default rehydration.
	 *
	 * @param  rehydration Rehydration.
	 * @return             This.
	 */
	public TieredTable withRehydration(
			final Rehydration rehydration) {
		this.rehydration = rehydration;
		return this;
	}

	/**
	 * Gets the entity class.
	 *
	 * @return The entity class.
	 */
	public Class<?> getEntityClass() {
		return this.entityClass;
	}

	/**
	 * Gets the key properties.
	 *
	 * @return The key properties, in key column order.
	 */
	public List<String> getKeyProperties() {
		List<String> properties = this.keyProperties;
		if (properties == null) {
			properties = new ArrayList<>();
			for (final String keyColumn : this.keyColumns) {
				properties.add(TieredTable.toProperty(keyColumn));
			}
		}
		return properties;
	}

	/**
	 * Binds the table to an entity, so a {@link TieredRepository} of that entity finds it.
	 *
	 * @param  entityClass   Entity class.
	 * @param  keyProperties Id properties of the key object, in key column order; null for the
	 *                           camel case of the key columns.
	 * @return               This.
	 */
	public TieredTable withEntity(
			final Class<?> entityClass,
			final List<String> keyProperties) {
		this.entityClass = entityClass;
		this.keyProperties = keyProperties == null ? null : List.copyOf(keyProperties);
		return this;
	}

	/**
	 * The SQL types of the hot table's columns, as the registry read them at startup.
	 *
	 * @return {@code data_type} by column name.
	 */
	public Map<String, String> getColumnTypes() {
		if (this.columnTypes == null) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.unregistered", new Object[] { this.id }));
		}
		return this.columnTypes;
	}

	/**
	 * Binds the column types (the registry, once validated).
	 *
	 * @param columnTypes {@code data_type} by column name.
	 */
	public void bindColumnTypes(
			final Map<String, String> columnTypes) {
		this.columnTypes = Map.copyOf(columnTypes);
	}

	/**
	 * SQL expression of the TTL clock.
	 *
	 * @return The expression.
	 */
	public String clockExpression() {
		return switch (this.ttlClock) {
			case LAST_ACCESS -> "COALESCE(" + this.lastAccessedColumn + ", " + this.watermarkColumn + ")";
			case LAST_WRITE -> this.watermarkColumn;
			case COLUMN -> this.clockColumn;
		};
	}

	/**
	 * Whether reads keep a last-access clock on the hot row.
	 *
	 * @return Whether reads touch.
	 */
	public Boolean isTouching() {
		return this.ttlClock == TtlClock.LAST_ACCESS;
	}

}
