package org.coldis.library.service.tiering;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.persistence.bean.StaticContextAccessor;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.util.ReflectionUtils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * Reads of a tiered entity: hot first, then the cold tier (rehydrating when the table says so).
 * A consumer repository extends it beside its JPA repository interface; the entity is bound to
 * its {@link TieredTable} with {@link TieredTable#withEntity(Class, List)}. The methods are
 * explicit ({@code *Tiered}) on purpose: {@code findById} and the other JPA reads never reach the
 * cold tier, so a locked read or an internal lookup cannot cost an object fetch.
 *
 * @param <Type> Entity type.
 * @param <Id>   Id type (the {@code @IdClass} for composite keys, the id itself otherwise).
 */
@NoRepositoryBean
public interface TieredRepository<Type, Id> extends CrudRepository<Type, Id> {

	/**
	 * The entity type, resolved from the repository interface's generics.
	 *
	 * @return The entity type.
	 */
	@SuppressWarnings("unchecked")
	default Class<Type> tieredEntityType() {
		Class<Type> entityType = null;
		for (final Class<?> repositoryInterface : this.getClass().getInterfaces()) {
			if ((entityType == null) && TieredRepository.class.isAssignableFrom(repositoryInterface)) {
				entityType = (Class<Type>) ResolvableType.forClass(repositoryInterface).as(TieredRepository.class).getGeneric(0).resolve();
			}
		}
		if (entityType == null) {
			throw new IntegrationException(new SimpleMessage("coldTiering.repository.entity.unresolved", new Object[] { this.getClass().getName() }));
		}
		return entityType;
	}

	/**
	 * The tiered table of the entity.
	 *
	 * @return The table.
	 */
	default TieredTable tieredTable() {
		final Class<Type> entityType = this.tieredEntityType();
		return StaticContextAccessor.getBean(TieredTableRegistry.class).findTable(entityType)
				.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.repository.table.unbound", new Object[] { entityType.getName() })));
	}

	/**
	 * The row key of an id: a composite id's properties (in key column order), or the id itself
	 * for a single key column.
	 *
	 * @param  id Id.
	 * @return    The row key.
	 */
	default TieredRowKey tieredKey(
			final Id id) {
		final TieredTable table = this.tieredTable();
		final Map<String, Object> values = new LinkedHashMap<>();
		if (table.getKeyColumns().size() == 1) {
			values.put(table.getKeyColumns().get(0), id);
		}
		else {
			for (int position = 0; position < table.getKeyColumns().size(); position++) {
				final Field field = ReflectionUtils.findField(id.getClass(), table.getKeyProperties().get(position));
				if (field == null) {
					throw new IntegrationException(new SimpleMessage("coldTiering.repository.key.property.missing",
							new Object[] { id.getClass().getName(), table.getKeyProperties().get(position) }));
				}
				ReflectionUtils.makeAccessible(field);
				values.put(table.getKeyColumns().get(position), ReflectionUtils.getField(field, id));
			}
		}
		return StaticContextAccessor.getBean(ColdTieringServiceComponent.class).keyOf(table.getId(), values);
	}

	/**
	 * Finds an entity: hot (touching its clock when stale), else cold, rehydrated and read again
	 * when the table rehydrates. On a {@link Rehydration#NONE} table a cold row is a miss here:
	 * see {@link #findColdRow(Object)}.
	 *
	 * @param  id Id.
	 * @return    The entity, or empty.
	 */
	default Optional<Type> findByIdTiered(
			final Id id) {
		return StaticContextAccessor.getBean(ColdTieringServiceComponent.class).readThrough(this.tieredKey(id), () -> this.findById(id));
	}

	/**
	 * Reads a row from the cold tier without touching the hot table.
	 *
	 * @param  id Id.
	 * @return    The cold row, or empty.
	 */
	default Optional<ColdRow> findColdRow(
			final Id id) {
		return StaticContextAccessor.getBean(ColdTieringServiceComponent.class).readCold(this.tieredKey(id));
	}

	/**
	 * The newest hot entity matching column values, on or before a moment, through JPQL on the
	 * entity's key properties.
	 *
	 * @param  table        Table.
	 * @param  columnValues Key column values to match (JDBC objects), by column.
	 * @param  asOf         Upper bound of the order column, or null.
	 * @return              The entity, or empty.
	 */
	private Optional<Type> findHot(
			final TieredTable table,
			final Map<String, Object> columnValues,
			final LocalDateTime asOf) {
		final EntityManager entityManager = StaticContextAccessor.getBean(EntityManager.class);
		final List<String> keyColumns = table.getKeyColumns();
		final String orderProperty = table.getKeyProperties().get(keyColumns.size() - 1);
		final StringBuilder jpql = new StringBuilder("SELECT entity FROM ").append(this.tieredEntityType().getSimpleName()).append(" entity WHERE 1 = 1");
		for (int position = 0; position < keyColumns.size(); position++) {
			if (columnValues.containsKey(keyColumns.get(position))) {
				jpql.append(" AND entity.").append(table.getKeyProperties().get(position)).append(" = :p").append(position);
			}
		}
		if (asOf != null) {
			jpql.append(" AND entity.").append(orderProperty).append(" <= :asOf");
		}
		jpql.append(" ORDER BY entity.").append(orderProperty).append(" DESC");
		final TypedQuery<Type> query = entityManager.createQuery(jpql.toString(), this.tieredEntityType()).setMaxResults(1);
		for (int position = 0; position < keyColumns.size(); position++) {
			if (columnValues.containsKey(keyColumns.get(position))) {
				query.setParameter("p" + position, columnValues.get(keyColumns.get(position)));
			}
		}
		if (asOf != null) {
			query.setParameter("asOf", asOf);
		}
		return query.getResultList().stream().findFirst();
	}

	/**
	 * The order column value of a hot entity.
	 *
	 * @param  table  Table.
	 * @param  entity Entity.
	 * @return        The value.
	 */
	private Object orderValue(
			final TieredTable table,
			final Type entity) {
		final Field field = ReflectionUtils.findField(entity.getClass(), table.getKeyProperties().get(table.getKeyColumns().size() - 1));
		ReflectionUtils.makeAccessible(field);
		return ReflectionUtils.getField(field, entity);
	}

	/**
	 * The newest entity of a key prefix on or before a moment, hot or cold (a versioned table):
	 * the newer of the newest archived row and the newest hot row (one written before its first
	 * archive is not archived yet), read with {@link #findByIdTiered(Object)} semantics.
	 *
	 * @param  keyPrefix Values of every key column but the last, by column name.
	 * @param  asOf      Upper bound of the last key column, or null for the newest.
	 * @return           The entity, or empty.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	default Optional<Type> findNewestTiered(
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final TieredTable table = this.tieredTable();
		final ColdTieringServiceComponent component = StaticContextAccessor.getBean(ColdTieringServiceComponent.class);
		final TieredTableRegistry registry = StaticContextAccessor.getBean(TieredTableRegistry.class);
		final Optional<TieredRowKey> newestCold = component.findNewestCold(table.getId(), keyPrefix, asOf);
		final Optional<Type> newestHot = this.findHot(table, keyPrefix, asOf);
		Optional<Type> newest = newestHot;
		if (newestCold.isPresent()) {
			final Map<String, String> columnTypes = registry.getColumnTypes(table);
			final String lastColumn = table.getLastKeyColumn();
			final Comparable coldOrder = (Comparable) ColumnValues.parse(columnTypes.get(lastColumn), newestCold.get().values().get(lastColumn));
			final Comparable hotOrder = newestHot.isEmpty() ? null
					: (Comparable) ColumnValues.parse(columnTypes.get(lastColumn), ColumnValues.format(this.orderValue(table, newestHot.get())));
			if ((hotOrder == null) || (hotOrder.compareTo(coldOrder) < 0)) {
				final Map<String, Object> keyValues = ColumnValues.parseKey(table, columnTypes, newestCold.get());
				newest = component.readThrough(newestCold.get(), () -> this.findHot(table, keyValues, null));
			}
		}
		return newest;
	}

}
