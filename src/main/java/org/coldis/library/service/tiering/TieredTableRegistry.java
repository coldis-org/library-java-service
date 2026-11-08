package org.coldis.library.service.tiering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.tiering.repository.HotTierRepository;
import org.coldis.library.service.tiering.repository.TieredRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.persistence.IdClass;

/**
 * The tiered tables (one {@link TieredTable} bean each), validated against the database and the
 * properties at startup so a wrong definition fails the boot, not the first upload. With
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

	/** Whether every definition is registered. */
	private Boolean isInitialized = false;

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
	 * Fails when the entity's key properties do not exist (on the {@code @IdClass} for a
	 * composite key, on the entity for a single one).
	 *
	 * @param table Table.
	 */
	private static void validateEntity(
			final TieredTable table) {
		final Class<?> entityClass = table.getEntityClass();
		if (entityClass != null) {
			final IdClass idClass = entityClass.getAnnotation(IdClass.class);
			if (table.getKeyColumns().size() > 1) {
				if (idClass == null) {
					throw new IntegrationException(new SimpleMessage("coldTiering.table.id.class.missing", new Object[] { table.getId(), entityClass.getName() }));
				}
				for (final String property : table.getKeyProperties()) {
					TieredRepository.fieldOf(idClass.value(), property);
				}
			}
			for (final String property : table.getKeyProperties()) {
				TieredRepository.fieldOf(entityClass, property);
			}
		}
	}

	/**
	 * Validates a definition against the database and its properties.
	 *
	 * @param table       Table.
	 * @param columnTypes Hot table column types.
	 * @param nullable    Nullable columns.
	 * @param rehydration Resolved rehydration.
	 */
	private static void validate(
			final TieredTable table,
			final Map<String, String> columnTypes,
			final Set<String> nullable,
			final Rehydration rehydration) {
		if (columnTypes.isEmpty()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.missing", new Object[] { table.getId(), table.getTableName() }));
		}
		for (final String column : table.getKeyColumns()) {
			TieredTableRegistry.requireColumn(table, columnTypes, column);
			ColumnValues.lowest(columnTypes.get(column));
		}
		TieredTableRegistry.requireColumn(table, columnTypes, table.getWatermarkColumn());
		if (nullable.contains(table.getWatermarkColumn())) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.watermark.nullable", new Object[] { table.getId(), table.getWatermarkColumn() }));
		}
		TieredTableRegistry.requireColumn(table, columnTypes, table.getArchivedAtColumn());
		if (table.isTouching()) {
			TieredTableRegistry.requireColumn(table, columnTypes, table.getLastAccessedColumn());
		}
		if (table.getTtlClock() == TtlClock.COLUMN) {
			TieredTableRegistry.requireColumn(table, columnTypes, table.getClockColumn());
		}
		if (table.isVersioned() && !ColumnValues.TEMPORAL_TYPES.contains(columnTypes.get(table.getLastKeyColumn()))) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.version.type.invalid", new Object[] { table.getId(), table.getLastKeyColumn() }));
		}
		if ((rehydration == Rehydration.INSERT) && !table.isTouching()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.rehydration.clock.invalid", new Object[] { table.getId() }));
		}
		if (table.getKeyProperties().size() != table.getKeyColumns().size()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.key.properties.invalid", new Object[] { table.getId() }));
		}
		TieredTableRegistry.validateEntity(table);
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
		final Rehydration rehydration = this.properties.of(table).getRehydration();
		TieredTableRegistry.validate(table, columnTypes, this.hotTierRepository.nullableColumns(table.getTableName()), rehydration);
		this.tablesById.put(table.getId(), table);
		this.columnTypesById.put(table.getId(), columnTypes);
		if (table.getEntityClass() != null) {
			this.tablesByEntity.put(table.getEntityClass(), table);
		}
		TieredTableRegistry.LOGGER.info("[COLD_TIERING] table={} registered: hot={} clock={} versioned={} rehydration={}", table.getId(), table.getTableName(),
				table.clockExpression(), table.isVersioned(), rehydration);
	}

	/**
	 * Registers every definition once.
	 */
	private synchronized void initialize() {
		if (!this.isInitialized) {
			for (final TieredTable table : this.definitions) {
				this.register(table);
			}
			this.isInitialized = true;
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
	 * @return             The table.
	 */
	public TieredTable getTable(
			final Class<?> entityClass) {
		this.initialize();
		return Optional.ofNullable(this.tablesByEntity.get(entityClass))
				.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.table.unbound", new Object[] { entityClass.getName() })));
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
