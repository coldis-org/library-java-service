package org.coldis.library.service.tiering.repository;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.tiering.ColdRow;
import org.coldis.library.service.tiering.ColdTieringProperties;
import org.coldis.library.service.tiering.ColumnValues;
import org.coldis.library.service.tiering.ObjectKeyStrategy;
import org.coldis.library.service.tiering.PayloadCodec;
import org.coldis.library.service.tiering.TieredRowKey;
import org.coldis.library.service.tiering.TieredTable;
import org.coldis.library.service.tiering.TieredTableRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.util.ReflectionUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

/**
 * Tiered access to a table's rows, composed from the hot tier (JPA entity reads, the native
 * statements of {@link HotTierRepository}) and the cold tier ({@link ColdStorageRepository} under
 * the derived object keys): no policy here, every call does what its name says whatever the
 * flags. {@code ColdTieringServiceComponent} decides when to call what.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class TieredRepository {

	/** JSON reader of archived rows. */
	private static final ObjectMapper OBJECT_MAPPER = ObjectMapperHelper.createMapper();

	/** Registry. */
	@Autowired
	private TieredTableRegistry registry;

	/** Properties (key prefix). */
	@Autowired
	private ColdTieringProperties properties;

	/** Hot tier. */
	@Autowired
	private HotTierRepository hotTierRepository;

	/** Cold tier. */
	@Autowired
	private ColdStorageRepository coldStorageRepository;

	/** Codec. */
	@Autowired
	private PayloadCodec codec;

	/** Object key strategy. */
	@Autowired
	private ObjectKeyStrategy objectKeyStrategy;

	/** Entity manager. */
	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * A field of a class, by name.
	 *
	 * @param  type Class.
	 * @param  name Field name.
	 * @return      The field, accessible.
	 */
	public static Field fieldOf(
			final Class<?> type,
			final String name) {
		final Field field = ReflectionUtils.findField(type, name);
		if (field == null) {
			throw new IntegrationException(new SimpleMessage("coldTiering.key.property.missing", new Object[] { type.getName(), name }));
		}
		ReflectionUtils.makeAccessible(field);
		return field;
	}

	/**
	 * The row key of a table from column values (any JDBC-compatible objects).
	 *
	 * @param  table  Table.
	 * @param  values Key column values.
	 * @return        The key.
	 */
	public TieredRowKey keyOf(
			final TieredTable table,
			final Map<String, Object> values) {
		final Map<String, String> formatted = new LinkedHashMap<>();
		for (final String column : table.getKeyColumns()) {
			final Object value = values.get(column);
			if (value == null) {
				throw new IntegrationException(new SimpleMessage("coldTiering.key.incomplete", new Object[] { table.getId(), column }));
			}
			formatted.put(column, ColumnValues.format(value));
		}
		return new TieredRowKey(table.getId(), formatted);
	}

	/**
	 * The row key of an entity id: a composite id's properties (in key column order), or the id
	 * itself for a single key column.
	 *
	 * @param  table Table.
	 * @param  id    Entity id.
	 * @return       The key.
	 */
	public TieredRowKey keyOf(
			final TieredTable table,
			final Object id) {
		final Map<String, Object> values = new LinkedHashMap<>();
		if (table.getKeyColumns().size() == 1) {
			values.put(table.getKeyColumns().get(0), id);
		}
		else {
			for (int position = 0; position < table.getKeyColumns().size(); position++) {
				values.put(table.getKeyColumns().get(position), ReflectionUtils.getField(TieredRepository.fieldOf(id.getClass(), table.getKeyProperties().get(position)), id));
			}
		}
		return this.keyOf(table, values);
	}

	/**
	 * The cold-tier object key of a row.
	 *
	 * @param  table Table.
	 * @param  key   Row key.
	 * @return       The object key.
	 */
	public String objectKeyOf(
			final TieredTable table,
			final TieredRowKey key) {
		return this.objectKeyStrategy.keyOf(this.properties.getPrefix(), table, ColumnValues.toSegments(table, this.registry.getColumnTypes(table), key.values()),
				this.codec.extension());
	}

	/**
	 * The hot entity matching column values, newest first by the last key column, on or before a
	 * moment.
	 *
	 * @param  <Type>       Entity type.
	 * @param  table        Table.
	 * @param  entityType   Entity type.
	 * @param  columnValues Key column values to match (JDBC objects), by column.
	 * @param  asOf         Upper bound of the last key column, or null.
	 * @return              The entity, or empty.
	 */
	private <Type> Optional<Type> findHot(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> columnValues,
			final Object asOf) {
		final List<String> keyColumns = table.getKeyColumns();
		final String lastProperty = table.getKeyProperties().get(keyColumns.size() - 1);
		final StringBuilder jpql = new StringBuilder("SELECT entity FROM ").append(entityType.getName()).append(" entity WHERE 1 = 1");
		for (int position = 0; position < keyColumns.size(); position++) {
			if (columnValues.containsKey(keyColumns.get(position))) {
				jpql.append(" AND entity.").append(table.getKeyProperties().get(position)).append(" = :p").append(position);
			}
		}
		if (asOf != null) {
			jpql.append(" AND entity.").append(lastProperty).append(" <= :asOf");
		}
		jpql.append(" ORDER BY entity.").append(lastProperty).append(" DESC");
		final TypedQuery<Type> query = this.entityManager.createQuery(jpql.toString(), entityType).setMaxResults(1);
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
	 * The hot entity of a row key.
	 *
	 * @param  <Type>     Entity type.
	 * @param  table      Table.
	 * @param  entityType Entity type.
	 * @param  key        Row key.
	 * @return            The entity, or empty.
	 */
	public <Type> Optional<Type> findHot(
			final TieredTable table,
			final Class<Type> entityType,
			final TieredRowKey key) {
		return this.findHot(table, entityType, ColumnValues.parseKey(table, this.registry.getColumnTypes(table), key), null);
	}

	/**
	 * The newest hot entity of a key prefix on or before a moment (versioned tables).
	 *
	 * @param  <Type>     Entity type.
	 * @param  table      Table.
	 * @param  entityType Entity type.
	 * @param  keyPrefix  Values of every key column but the last (JDBC objects), by column.
	 * @param  asOf       Upper bound of the last key column, or null for the newest.
	 * @return            The entity, or empty.
	 */
	public <Type> Optional<Type> findNewestHot(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final String lastType = this.registry.getColumnTypes(table).get(table.getLastKeyColumn());
		return this.findHot(table, entityType, keyPrefix, asOf == null ? null : ColumnValues.parse(lastType, ColumnValues.ofMoment(lastType, asOf)));
	}

	/**
	 * The row key of a hot entity.
	 *
	 * @param  table  Table.
	 * @param  entity Entity.
	 * @return        The key.
	 */
	public TieredRowKey keyOfEntity(
			final TieredTable table,
			final Object entity) {
		final Map<String, Object> values = new LinkedHashMap<>();
		for (int position = 0; position < table.getKeyColumns().size(); position++) {
			values.put(table.getKeyColumns().get(position),
					ReflectionUtils.getField(TieredRepository.fieldOf(entity.getClass(), table.getKeyProperties().get(position)), entity));
		}
		return this.keyOf(table, values);
	}

	/**
	 * Loads a row from the cold tier: one {@code GET} on the derived key, decoded.
	 *
	 * @param  table Table.
	 * @param  key   Row key.
	 * @return       The cold row, or empty when there is no object.
	 */
	public Optional<ColdRow> loadCold(
			final TieredTable table,
			final TieredRowKey key) {
		return this.coldStorageRepository.load(this.objectKeyOf(table, key)).map(content -> new ColdRow(key, this.codec.decode(content)));
	}

	/**
	 * The newest archived row key of a key prefix on or before a moment (versioned tables): a
	 * {@code LIST} of the other key columns' prefix, picking the greatest name not after the
	 * moment.
	 *
	 * @param  table     Table.
	 * @param  keyPrefix Values of every key column but the last, by column.
	 * @param  asOf      Upper bound of the last key column, or null for the newest.
	 * @return           The row key, or empty.
	 */
	public Optional<TieredRowKey> findNewestColdKey(
			final TieredTable table,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final Map<String, String> columnTypes = this.registry.getColumnTypes(table);
		final Map<String, String> formatted = new LinkedHashMap<>();
		keyPrefix.forEach((
				column,
				value) -> formatted.put(column, ColumnValues.format(value)));
		final List<String> segments = ColumnValues.toSegments(table, columnTypes, formatted);
		if (segments.size() != (table.getKeyColumns().size() - 1)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.key.prefix.incomplete", new Object[] { table.getId() }));
		}
		final String lastType = columnTypes.get(table.getLastKeyColumn());
		final String ceiling = asOf == null ? null : ColumnValues.toSegment(lastType, ColumnValues.ofMoment(lastType, asOf));
		String newestSegment = null;
		for (final String objectKey : this.coldStorageRepository.list(this.objectKeyStrategy.prefixOf(this.properties.getPrefix(), table, segments))) {
			final String segment = this.objectKeyStrategy.lastSegmentOf(objectKey, this.codec.extension());
			if (((ceiling == null) || (segment.compareTo(ceiling) <= 0)) && ((newestSegment == null) || (segment.compareTo(newestSegment) > 0))) {
				newestSegment = segment;
			}
		}
		Optional<TieredRowKey> newest = Optional.empty();
		if (newestSegment != null) {
			final Map<String, String> values = new LinkedHashMap<>(formatted);
			values.put(table.getLastKeyColumn(), ColumnValues.fromSegment(lastType, newestSegment));
			newest = Optional.of(new TieredRowKey(table.getId(), values));
		}
		return newest;
	}

	/**
	 * Rehydrates a cold row into the hot table, after checking that the row's key columns are
	 * the requested key (an object under the wrong name is refused).
	 *
	 * @param  table   Table.
	 * @param  coldRow Cold row.
	 * @param  now     Access moment.
	 * @return         Rows inserted.
	 */
	public Integer rehydrate(
			final TieredTable table,
			final ColdRow coldRow,
			final LocalDateTime now) {
		final Map<String, String> columnTypes = this.registry.getColumnTypes(table);
		final JsonNode row;
		try {
			row = TieredRepository.OBJECT_MAPPER.readTree(coldRow.rowJson());
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.cold.row.invalid", new Object[] { table.getId() }), exception);
		}
		for (final String column : table.getKeyColumns()) {
			final JsonNode value = row.get(column);
			if ((value == null) || value.isNull() || !ColumnValues.same(columnTypes.get(column), value.asText(), coldRow.key().values().get(column))) {
				throw new IntegrationException(new SimpleMessage("coldTiering.cold.row.key.mismatch", new Object[] { table.getId(), column }));
			}
		}
		return this.hotTierRepository.rehydrate(table, columnTypes, coldRow.key(), coldRow.rowJson(), now);
	}

	/**
	 * Removes a row from both tiers.
	 *
	 * @param table Table.
	 * @param key   Row key.
	 */
	public void delete(
			final TieredTable table,
			final TieredRowKey key) {
		this.hotTierRepository.delete(table, this.registry.getColumnTypes(table), key);
		this.coldStorageRepository.delete(this.objectKeyOf(table, key));
	}

}
