package org.coldis.library.service.tiering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The tiered tables (one {@link TieredTable} bean each), validated against the database at
 * startup so a wrong definition fails the boot, not the first upload. With
 * {@code create-schema=true} the lifecycle columns and the eviction index are created first
 * (tests).
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class TieredTableRegistry implements SmartInitializingSingleton {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(TieredTableRegistry.class);

	/** Definitions. */
	@Autowired(required = false)
	private List<TieredTable> definitions = new ArrayList<>();

	/** Properties. */
	@Autowired
	private ColdTieringProperties properties;

	/** Hot tier repository. */
	@Autowired
	private HotTierRepository hotTierRepository;

	/** Tables by id. */
	private final Map<String, TieredTable> tablesById = new HashMap<>();

	/** Tables by entity class. */
	private final Map<Class<?>, TieredTable> tablesByEntity = new HashMap<>();

	/** Column types by table id. */
	private final Map<String, Map<String, String>> columnTypesById = new HashMap<>();

	/** Whether the registry is initialized. */
	private Boolean initialized = false;

	/**
	 * Fails when a column is missing.
	 *
	 * @param table       Table.
	 * @param columnTypes Column types.
	 * @param column      Column.
	 */
	private static void requireColumn(
			final TieredTable table,
			final Map<String, String> columnTypes,
			final String column) {
		if ((column == null) || !columnTypes.containsKey(column)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.column.missing", new Object[] { table.getId(), table.getTableName(), column }));
		}
	}

	/**
	 * Validates a definition against the database.
	 *
	 * @param table       Table.
	 * @param columnTypes Hot table column types.
	 */
	private static void validate(
			final TieredTable table,
			final Map<String, String> columnTypes) {
		if (columnTypes.isEmpty()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.missing", new Object[] { table.getId(), table.getTableName() }));
		}
		for (final String column : table.getKeyColumns()) {
			TieredTableRegistry.requireColumn(table, columnTypes, column);
			ColumnValues.lowest(columnTypes.get(column));
		}
		TieredTableRegistry.requireColumn(table, columnTypes, table.getWatermarkColumn());
		TieredTableRegistry.requireColumn(table, columnTypes, table.getArchivedAtColumn());
		if (table.isTouching()) {
			TieredTableRegistry.requireColumn(table, columnTypes, table.getLastAccessedColumn());
		}
		if (table.getTtlClock() == TtlClock.COLUMN) {
			TieredTableRegistry.requireColumn(table, columnTypes, table.getClockColumn());
		}
		if (table.getKeyProperties().size() != table.getKeyColumns().size()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.key.properties.invalid", new Object[] { table.getId() }));
		}
	}

	/**
	 * Registers a definition.
	 *
	 * @param table Table.
	 */
	private void register(
			final TieredTable table) {
		if (this.tablesById.containsKey(table.getId())) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.duplicate", new Object[] { table.getId() }));
		}
		Map<String, String> columnTypes = this.hotTierRepository.columnTypes(table.getTableName());
		if (this.properties.getCreateSchema() && !columnTypes.isEmpty()) {
			for (final String statement : ColdTieringSchema.statements(table, false)) {
				this.hotTierRepository.execute(statement);
			}
			columnTypes = this.hotTierRepository.columnTypes(table.getTableName());
		}
		TieredTableRegistry.validate(table, columnTypes);
		this.tablesById.put(table.getId(), table);
		this.columnTypesById.put(table.getId(), columnTypes);
		if (table.getEntityClass() != null) {
			this.tablesByEntity.put(table.getEntityClass(), table);
		}
		TieredTableRegistry.LOGGER.info("[COLD_TIERING] table={} registered: hot={} clock={} versioned={} rehydration={}", table.getId(), table.getTableName(),
				table.clockExpression(), table.isVersioned(), this.properties.of(table).getRehydration());
	}

	/**
	 * Registers every definition once.
	 */
	private synchronized void initialize() {
		if (!this.initialized) {
			for (final TieredTable table : this.definitions) {
				this.register(table);
			}
			this.initialized = true;
		}
	}

	/**
	 * @see SmartInitializingSingleton#afterSingletonsInstantiated()
	 */
	@Override
	public void afterSingletonsInstantiated() {
		this.initialize();
	}

	/**
	 * Every registered table.
	 *
	 * @return The tables.
	 */
	public List<TieredTable> getTables() {
		this.initialize();
		return List.copyOf(this.tablesById.values());
	}

	/**
	 * A table by id.
	 *
	 * @param  tableId Table id.
	 * @return         The table.
	 */
	public TieredTable getTable(
			final String tableId) {
		this.initialize();
		final TieredTable table = this.tablesById.get(tableId);
		if (table == null) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.unknown", new Object[] { tableId }));
		}
		return table;
	}

	/**
	 * A table by entity class.
	 *
	 * @param  entityClass Entity class.
	 * @return             The table, or empty.
	 */
	public Optional<TieredTable> findTable(
			final Class<?> entityClass) {
		this.initialize();
		return Optional.ofNullable(this.tablesByEntity.get(entityClass));
	}

	/**
	 * The column types of a table.
	 *
	 * @param  table Table.
	 * @return       The types by column.
	 */
	public Map<String, String> getColumnTypes(
			final TieredTable table) {
		this.initialize();
		return this.columnTypesById.get(table.getId());
	}

}
