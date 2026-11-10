package org.coldis.library.service.tiering.repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.tiering.model.ColdTable;
import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.ObjectKeyStrategy;
import org.coldis.library.service.tiering.model.PayloadCodec;
import org.coldis.library.service.tiering.model.TieredEntityKeys;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

/**
 * Tiered access to a table's rows, composed from the hot tier (JPA entity reads, the native
 * statements of {@link HotTierRepository}) and the cold tier ({@link ColdStorageRepository} under
 * the derived object keys): no policy here, every call does what its name says whatever the
 * flags, and everything it needs about a table is on the {@link TieredTable} definition.
 * {@code ColdTieringServiceComponent} decides when to call what.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.ENABLED,
		matchIfMissing = false
)
public class TieredRepository {

	/** JSON reader of archived rows. */
	private static final ObjectMapper OBJECT_MAPPER = ObjectMapperHelper.createMapper();

	/** JPQL parameter prefix of key properties. */
	private static final String KEY_PARAMETER_PREFIX = "key_";

	/** Object key prefix. */
	@Value(ColdTieringPropertyNames.OBJECT_PREFIX_PLACEHOLDER)
	private String objectPrefix;

	/**
	 * Object key prefix of a table: the one bound at registration, else the global one.
	 *
	 * @param  table Table.
	 * @return       Prefix.
	 */
	private String prefixOf(
			final ColdTable table) {
		final String bound = table instanceof final TieredTable tiered ? tiered.getObjectPrefix() : null;
		return bound == null ? this.objectPrefix : bound;
	}

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
	 * The row key of a table from column values (any JDBC-compatible objects, or the Java types an
	 * entity maps the columns with).
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
			formatted.put(column, ColumnValues.format(ColumnValues.toColumnValue(table.getColumnTypes().get(column), value)));
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
				values.put(table.getKeyColumns().get(position), TieredEntityKeys.valueOf(id, table.getKeyProperties().get(position)));
			}
		}
		return this.keyOf(table, values);
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
			values.put(table.getKeyColumns().get(position), TieredEntityKeys.valueOf(entity, table.getKeyProperties().get(position)));
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
		return this.objectKeyStrategy.keyOf(this.prefixOf(table), table, ColumnValues.toSegments(table, table.getColumnTypes(), key.values()),
				this.codec.extension());
	}

	/**
	 * Hot rows matching the given key column values, bounded on the last key column.
	 *
	 * @param  <Type>       Entity type.
	 * @param  table        Table.
	 * @param  entityType   Entity type.
	 * @param  columnValues Key column values (a prefix of the key, or the whole key).
	 * @param  lowerBound   Exclusive lower bound on the last key column, or null.
	 * @param  upperBound   Inclusive upper bound on the last key column, or null.
	 * @param  ascending    Order on the last key column.
	 * @param  limit        Maximum rows (null for all).
	 * @return              Rows.
	 */
	private <Type> List<Type> findHotRange(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> columnValues,
			final Object lowerBound,
			final Object upperBound,
			final Boolean ascending,
			final Integer limit) {
		final List<String> keyColumns = table.getKeyColumns();
		final List<String> keyProperties = table.getKeyProperties();
		final String lastProperty = keyProperties.get(keyColumns.size() - 1);
		final StringBuilder jpql = new StringBuilder("SELECT entity FROM ").append(entityType.getName()).append(" entity WHERE 1 = 1");
		for (int position = 0; position < keyColumns.size(); position++) {
			if (columnValues.containsKey(keyColumns.get(position))) {
				jpql.append(" AND entity.").append(keyProperties.get(position)).append(" = :").append(TieredRepository.KEY_PARAMETER_PREFIX)
						.append(keyProperties.get(position));
			}
		}
		if (lowerBound != null) {
			jpql.append(" AND entity.").append(lastProperty).append(" > :lowerBound");
		}
		if (upperBound != null) {
			jpql.append(" AND entity.").append(lastProperty).append(" <= :upperBound");
		}
		jpql.append(" ORDER BY entity.").append(lastProperty).append(ascending ? " ASC" : " DESC");
		final TypedQuery<Type> query = this.entityManager.createQuery(jpql.toString(), entityType);
		if (limit != null) {
			query.setMaxResults(limit);
		}
		for (int position = 0; position < keyColumns.size(); position++) {
			if (columnValues.containsKey(keyColumns.get(position))) {
				query.setParameter(TieredRepository.KEY_PARAMETER_PREFIX + keyProperties.get(position), ColumnValues
						.toJavaValue(columnValues.get(keyColumns.get(position)), TieredEntityKeys.fieldOf(entityType, keyProperties.get(position)).getType()));
			}
		}
		if (lowerBound != null) {
			query.setParameter("lowerBound", ColumnValues.toJavaValue(lowerBound, TieredEntityKeys.fieldOf(entityType, lastProperty).getType()));
		}
		if (upperBound != null) {
			query.setParameter("upperBound", ColumnValues.toJavaValue(upperBound, TieredEntityKeys.fieldOf(entityType, lastProperty).getType()));
		}
		return query.getResultList();
	}

	/**
	 * Newest hot row matching the values, on or before the bound.
	 *
	 * @param  <Type>       Entity type.
	 * @param  table        Table.
	 * @param  entityType   Entity type.
	 * @param  columnValues Key column values.
	 * @param  upperBound   Inclusive upper bound on the last key column, or null.
	 * @return              Row.
	 */
	private <Type> Optional<Type> findHot(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> columnValues,
			final Object upperBound) {
		return this.findHotRange(table, entityType, columnValues, null, upperBound, false, 1).stream().findFirst();
	}

	/**
	 * Converts a key prefix to column values.
	 *
	 * @param  table     Table.
	 * @param  keyPrefix Key prefix (Java values).
	 * @return           Column values.
	 */
	private static Map<String, Object> columnValuesOf(
			final TieredTable table,
			final Map<String, Object> keyPrefix) {
		final Map<String, String> columnTypes = table.getColumnTypes();
		final Map<String, Object> columnValues = new LinkedHashMap<>();
		keyPrefix.forEach((
				column,
				value) -> columnValues.put(column, ColumnValues.toColumnValue(columnTypes.get(column), value)));
		return columnValues;
	}

	/**
	 * Converts a moment to the last key column's value.
	 *
	 * @param  table  Table.
	 * @param  moment Moment, or null.
	 * @return        Column value, or null.
	 */
	private static Object lastColumnValueOf(
			final TieredTable table,
			final LocalDateTime moment) {
		final String lastType = table.getColumnTypes().get(table.getLastKeyColumn());
		return moment == null ? null : ColumnValues.parse(lastType, ColumnValues.ofMoment(lastType, moment));
	}

	/**
	 * First hot row of a key prefix with the last key column in {@code (after, upTo]}.
	 *
	 * @param  <Type>     Entity type.
	 * @param  table      Table.
	 * @param  entityType Entity type.
	 * @param  keyPrefix  Key prefix.
	 * @param  after      Exclusive lower bound, or null.
	 * @param  upTo       Inclusive upper bound, or null.
	 * @return            Row.
	 */
	public <Type> Optional<Type> findFirstHot(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> keyPrefix,
			final LocalDateTime after,
			final LocalDateTime upTo) {
		return this.findHotRange(table, entityType, TieredRepository.columnValuesOf(table, keyPrefix), TieredRepository.lastColumnValueOf(table, after),
				TieredRepository.lastColumnValueOf(table, upTo), true, 1).stream().findFirst();
	}

	/**
	 * Every hot row of a key prefix on or before a moment, newest first.
	 *
	 * @param  <Type>     Entity type.
	 * @param  table      Table.
	 * @param  entityType Entity type.
	 * @param  keyPrefix  Key prefix.
	 * @param  asOf       Inclusive upper bound, or null.
	 * @return            Rows.
	 */
	public <Type> List<Type> findAllHot(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		return this.findHotRange(table, entityType, TieredRepository.columnValuesOf(table, keyPrefix), null, TieredRepository.lastColumnValueOf(table, asOf), false, null);
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
		return this.findHot(table, entityType, ColumnValues.parseKey(table, table.getColumnTypes(), key), null);
	}

	/**
	 * The newest hot entity of a key prefix on or before a moment (versioned tables).
	 *
	 * @param  <Type>     Entity type.
	 * @param  table      Table.
	 * @param  entityType Entity type.
	 * @param  keyPrefix  Values of every key column but the last, by column.
	 * @param  asOf       Upper bound of the last key column, or null for the newest.
	 * @return            The entity, or empty.
	 */
	public <Type> Optional<Type> findNewestHot(
			final TieredTable table,
			final Class<Type> entityType,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final Map<String, String> columnTypes = table.getColumnTypes();
		final Map<String, Object> columnValues = new LinkedHashMap<>();
		keyPrefix.forEach((
				column,
				value) -> columnValues.put(column, ColumnValues.toColumnValue(columnTypes.get(column), value)));
		final String lastType = columnTypes.get(table.getLastKeyColumn());
		return this.findHot(table, entityType, columnValues, asOf == null ? null : ColumnValues.parse(lastType, ColumnValues.ofMoment(lastType, asOf)));
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
		final Map<String, String> columnTypes = table.getColumnTypes();
		final Map<String, String> formatted = new LinkedHashMap<>();
		keyPrefix.forEach((
				column,
				value) -> formatted.put(column, ColumnValues.format(ColumnValues.toColumnValue(columnTypes.get(column), value))));
		final List<String> segments = ColumnValues.toSegments(table, columnTypes, formatted);
		if (segments.size() != (table.getKeyColumns().size() - 1)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.key.prefix.incomplete", new Object[] { table.getId() }));
		}
		final String lastType = columnTypes.get(table.getLastKeyColumn());
		final String ceiling = asOf == null ? null : ColumnValues.toSegment(lastType, ColumnValues.ofMoment(lastType, asOf));
		String newestSegment = null;
		for (final String objectKey : this.coldStorageRepository.list(this.objectKeyStrategy.prefixOf(this.prefixOf(table), table, segments))) {
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
		final Map<String, String> columnTypes = table.getColumnTypes();
		final JsonNode row;
		try {
			row = TieredRepository.OBJECT_MAPPER.readTree(coldRow.rowJson());
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.cold.row.invalid", new Object[] { table.getId() }), exception);
		}
		for (final String column : table.getKeyColumns()) {
			final JsonNode value = row.get(column);
			if ((value == null) || value.isNull() || !ColumnValues.isSameValue(columnTypes.get(column), value.asText(), coldRow.key().values().get(column))) {
				throw new IntegrationException(new SimpleMessage("coldTiering.cold.row.key.mismatch", new Object[] { table.getId(), column }));
			}
		}
		return this.hotTierRepository.rehydrate(table, coldRow.key(), coldRow.rowJson(), now);
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
		this.hotTierRepository.delete(table, key);
		this.coldStorageRepository.delete(this.objectKeyOf(table, key));
	}

}
