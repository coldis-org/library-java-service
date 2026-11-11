package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.model.ArchiveUnit;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.PayloadCodec;
import org.coldis.library.service.tiering.model.Rehydration;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.repository.ColdStorageRepository;
import org.coldis.library.service.tiering.repository.HotTierRepository;
import org.coldis.library.service.tiering.repository.HotTierRepository.HotRow;
import org.coldis.library.service.tiering.repository.TieredRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cold tiering policy: write-through and backfill archives of row-tiered tables, touches, and
 * the tiered reads of every table (hot first, then the archive unit's {@link ColdLookupStrategy},
 * then rehydration when the table says so). The component reaches the repositories
 * ({@link TieredRepository}, {@link HotTierRepository}, {@link ColdStorageRepository}), never the
 * other way round; batches live in {@link ColdTieringBatchServiceComponent}.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class ColdTieringServiceComponent {

	/** Upload queue (write-through). */
	private static final String UPLOAD_QUEUE = "cold-tiering/upload";

	/** Backfill queue. */
	public static final String BACKFILL_QUEUE = "cold-tiering/backfill";

	/** Touch queue. */
	private static final String TOUCH_QUEUE = "cold-tiering/touch";

	/** Object metadata: watermark. */
	public static final String UPDATED_AT_METADATA = "updated-at";

	/** Object metadata: table. */
	public static final String TABLE_METADATA = "table";

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ColdTieringServiceComponent.class);

	/** Registry. */
	@Autowired
	private TieredTableRegistry registry;

	/** Properties. */
	@Autowired
	private ColdTieringProperties properties;

	/** Tiered repository. */
	@Autowired
	private TieredRepository tieredRepository;

	/** Hot tier. */
	@Autowired
	private HotTierRepository hotTierRepository;

	/** Cold storage. */
	@Autowired
	private ColdStorageRepository coldStorageRepository;

	/** Row payload codec. */
	@Autowired
	private PayloadCodec codec;

	/** JMS. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** JMS helper. */
	@Autowired
	private JmsTemplateHelper jmsTemplateHelper;

	/** Cold lookup strategies, one per archive unit. */
	@Autowired
	private List<ColdLookupStrategy> strategies;

	/** Strategies by unit. */
	private final Map<ArchiveUnit, ColdLookupStrategy> strategiesByUnit = new EnumMap<>(ArchiveUnit.class);

	/**
	 * Sends a key to a queue after the commit.
	 *
	 * @param queue Queue.
	 * @param key   Key.
	 */
	private void sendAfterCommit(
			final String queue,
			final TieredRowKey key) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<Serializable>().withDestination(queue).withMessage(key).withAfterCommit(true));
	}

	/**
	 * Sends a key to a queue now.
	 *
	 * @param queue Queue.
	 * @param key   Key.
	 */
	private void sendNow(
			final String queue,
			final TieredRowKey key) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<Serializable>().withDestination(queue).withMessage(key).withAfterCommit(false));
	}

	/**
	 * Requests a touch when the row's clock is older than the threshold.
	 *
	 * @param table           Table.
	 * @param tableProperties Properties.
	 * @param key             Key.
	 */
	private void requestTouchIfStale(
			final TieredTable table,
			final TableProperties tableProperties,
			final TieredRowKey key) {
		if (table.isTouching() && tableProperties.getEnabled()) {
			final LocalDateTime staleBefore = DateTimeHelper.getCurrentLocalDateTime().minus(tableProperties.getTouchThreshold());
			final Optional<LocalDateTime> clock = this.hotTierRepository.clock(table, key);
			if (clock.isPresent() && clock.get().isBefore(staleBefore)) {
				this.sendAfterCommit(ColdTieringServiceComponent.TOUCH_QUEUE, key);
			}
		}
	}

	/**
	 * Whether cold reads are on for a table.
	 *
	 * @param  tableProperties Properties.
	 * @return                 Cold readable.
	 */
	private static Boolean isColdReadable(
			final TableProperties tableProperties) {
		return tableProperties.getEnabled() && tableProperties.getColdReadEnabled();
	}

	/**
	 * Strategy of a table's archive unit.
	 *
	 * @param  table Table.
	 * @return       Strategy.
	 */
	private ColdLookupStrategy strategyOf(
			final TieredTable table) {
		synchronized (this.strategiesByUnit) {
			if (this.strategiesByUnit.isEmpty()) {
				for (final ColdLookupStrategy strategy : this.strategies) {
					this.strategiesByUnit.put(strategy.unit(), strategy);
				}
			}
		}
		return Optional.ofNullable(this.strategiesByUnit.get(table.getArchiveUnit()))
				.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.table.unit.unsupported", new Object[] { table.getId() })));
	}

	/**
	 * Sort key of a row in a timeline: the last key column's segment, then the whole key's
	 * segments (newest first when sorted descending).
	 *
	 * @param  table Table.
	 * @param  key   Key.
	 * @return       Sort key.
	 */
	private static String timelineKeyOf(
			final TieredTable table,
			final TieredRowKey key) {
		return ColumnValues.lastSegmentOf(table, key) + "/" + String.join("/", ColumnValues.toSegments(table, table.getColumnTypes(), key.values()));
	}

	/**
	 * Validates and formats a key prefix: known columns only, leading and gap-free (the first key
	 * column present, no column after a missing one).
	 *
	 * @param  table     Table.
	 * @param  keyPrefix Prefix (Java values by column).
	 * @return           Formatted values by column, in key column order.
	 */
	private static Map<String, String> validatedPrefix(
			final TieredTable table,
			final Map<String, Object> keyPrefix) {
		for (final String column : keyPrefix.keySet()) {
			if (!table.getKeyColumns().contains(column)) {
				throw new IntegrationException(new SimpleMessage("coldTiering.key.prefix.invalid", new Object[] { table.getId(), column }));
			}
		}
		final Map<String, String> formatted = new LinkedHashMap<>();
		Boolean isColumnMissing = false;
		for (final String column : table.getKeyColumns()) {
			if (keyPrefix.containsKey(column)) {
				if (isColumnMissing) {
					throw new IntegrationException(new SimpleMessage("coldTiering.key.prefix.invalid", new Object[] { table.getId(), column }));
				}
				formatted.put(column, ColumnValues.format(ColumnValues.toColumnValue(table.getColumnTypes().get(column), keyPrefix.get(column))));
			}
			else {
				isColumnMissing = true;
			}
		}
		if (formatted.isEmpty()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.key.prefix.invalid", new Object[] { table.getId(), table.getKeyColumns().get(0) }));
		}
		return formatted;
	}

	/**
	 * Serves a cold row: rehydrated and read back hot on an {@code INSERT} table, as the cold row
	 * otherwise (or when the insert could not be read back).
	 *
	 * @param  <Type>          Entity type.
	 * @param  table           Table.
	 * @param  tableProperties Properties.
	 * @param  entityClass     Entity class.
	 * @param  coldRow         Cold row.
	 * @return                 Hit.
	 */
	private <Type> TieredHit<Type> serveCold(
			final TieredTable table,
			final TableProperties tableProperties,
			final Class<Type> entityClass,
			final ColdRow coldRow) {
		TieredHit<Type> hit = new TieredHit<>(null, coldRow);
		if (tableProperties.getRehydration() == Rehydration.INSERT) {
			this.tieredRepository.rehydrate(table, coldRow, DateTimeHelper.getCurrentLocalDateTime());
			hit = this.tieredRepository.findHot(table, entityClass, coldRow.key()).map(entity -> new TieredHit<>(entity, (ColdRow) null)).orElse(hit);
		}
		ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} tier=cold rehydrated={}", table.getId(), hit.hot().isPresent());
		return hit;
	}

	/**
	 * Row key of a table from column values.
	 *
	 * @param  tableId Table id.
	 * @param  values  Values by column.
	 * @return         Key.
	 */
	public TieredRowKey keyOf(
			final String tableId,
			final Map<String, Object> values) {
		return this.tieredRepository.keyOf(this.registry.getTable(tableId), values);
	}

	/**
	 * Row key of an entity id.
	 *
	 * @param  entityClass Entity class.
	 * @param  id          Id.
	 * @return             Key.
	 */
	public TieredRowKey keyOf(
			final Class<?> entityClass,
			final Object id) {
		return this.tieredRepository.keyOf(this.registry.getTable(entityClass), id);
	}

	/**
	 * Object key of a row (row tiering).
	 *
	 * @param  key Key.
	 * @return     Object key.
	 */
	public String objectKeyOf(
			final TieredRowKey key) {
		return this.tieredRepository.objectKeyOf(this.registry.getTable(key.tableId()), key);
	}

	/**
	 * After the consumer's write: resets the lifecycle columns and, on a row-tiered table,
	 * requests the upload after the commit.
	 *
	 * @param key Key.
	 */
	public void afterWrite(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		if (this.properties.of(table).getEnabled()) {
			this.hotTierRepository.afterWrite(table, key, DateTimeHelper.getCurrentLocalDateTime());
			if (table.getArchiveUnit() == ArchiveUnit.ROW) {
				this.sendAfterCommit(ColdTieringServiceComponent.UPLOAD_QUEUE, key);
			}
		}
	}

	/**
	 * Enqueues a key to the backfill queue.
	 *
	 * @param key Key.
	 */
	public void requestArchive(
			final TieredRowKey key) {
		this.sendNow(ColdTieringServiceComponent.BACKFILL_QUEUE, key);
	}

	/**
	 * Archives a row (row tiering): locks it, uploads its current content, marks it archived at
	 * the uploaded watermark. A plain read first drops, without locking, uploading or writing, a
	 * request whose row is no longer hot (evicted or deleted) or already archived at its current
	 * watermark (a duplicate); the locked re-check stays the authority for the rest.
	 *
	 * @param  key Key.
	 * @return     Whether an upload happened.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public Boolean archive(
			final TieredRowKey key) {
		Boolean isUploaded = false;
		final TieredTable table = this.registry.getTable(key.tableId());
		final Boolean isUploadNeeded = this.hotTierRepository.findArchived(table, key).map(isArchived -> !isArchived).orElse(false);
		final Optional<HotRow> hotRow = isUploadNeeded ? this.hotTierRepository.lockForArchive(table, key) : Optional.empty();
		if (hotRow.isPresent() && !hotRow.get().isArchived()) {
			this.coldStorageRepository.store(this.tieredRepository.objectKeyOf(table, key), this.codec.encode(hotRow.get().rowJson()),
					Map.of(ColdTieringServiceComponent.TABLE_METADATA, table.getId(), ColdTieringServiceComponent.UPDATED_AT_METADATA, hotRow.get().watermark(),
							ColdStorageRepository.CONTENT_TYPE_METADATA, this.codec.contentType()));
			isUploaded = true;
			final Integer marked = this.hotTierRepository.markArchived(table, key, hotRow.get().watermark());
			if (marked == 0) {
				ColdTieringServiceComponent.LOGGER.error(
						"[COLD_TIERING_UPLOAD] table={} archive mark did not match the uploaded watermark (the row is locked, so a round-trip mismatch of its value); the row stays unarchived",
						table.getId());
			}
		}
		ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_UPLOAD] table={} uploadNeeded={} uploaded={}", key.tableId(), isUploadNeeded, isUploaded);
		return isUploaded;
	}

	/**
	 * Write-through upload listener.
	 *
	 * @param key Key.
	 */
	@JmsListener(
			destination = ColdTieringServiceComponent.UPLOAD_QUEUE,
			concurrency = "${" + ColdTieringProperties.PREFIX + ".upload.concurrency:1}",
			containerFactory = "${org.coldis.library.service.batch.container-factory:jmsListenerContainerFactory}"
	)
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public void upload(
			final TieredRowKey key) {
		this.archive(key);
	}

	/**
	 * Backfill listener: one row per transaction, so a row's lock covers only its own upload.
	 *
	 * @param key Key.
	 */
	@JmsListener(
			destination = ColdTieringServiceComponent.BACKFILL_QUEUE,
			concurrency = "${" + ColdTieringProperties.PREFIX + ".backfill.concurrency:1}",
			containerFactory = "${org.coldis.library.service.batch.container-factory:jmsListenerContainerFactory}"
	)
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public void archiveBackfill(
			final TieredRowKey key) {
		this.archive(key);
	}

	/**
	 * Touch listener.
	 *
	 * @param key Key.
	 */
	@JmsListener(
			destination = ColdTieringServiceComponent.TOUCH_QUEUE,
			concurrency = "${" + ColdTieringProperties.PREFIX + ".touch.concurrency:1-2}",
			containerFactory = "${org.coldis.library.service.batch.container-factory:jmsListenerContainerFactory}"
	)
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public void touch(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		if (this.properties.of(table).getEnabled() && table.isTouching()) {
			this.hotTierRepository.touch(table, key, DateTimeHelper.getCurrentLocalDateTime());
		}
	}

	/**
	 * Cold row by key, when cold reads are on.
	 *
	 * @param  key Key.
	 * @return     Row.
	 */
	public Optional<ColdRow> readCold(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		Optional<ColdRow> coldRow = Optional.empty();
		if (ColdTieringServiceComponent.isColdReadable(this.properties.of(table))) {
			coldRow = this.strategyOf(table).findCold(table, key);
			ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} found={}", table.getId(), coldRow.isPresent());
		}
		return coldRow;
	}

	/**
	 * Cold row by entity id.
	 *
	 * @param  entityClass Entity class.
	 * @param  id          Id.
	 * @return             Row.
	 */
	public Optional<ColdRow> findColdRow(
			final Class<?> entityClass,
			final Object id) {
		return this.readCold(this.keyOf(entityClass, id));
	}

	/**
	 * Row by id: hot (touched when stale), else cold and rehydrated on an {@code INSERT} table.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class.
	 * @param  id          Id.
	 * @return             Entity.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public <Type> Optional<Type> findByIdTiered(
			final Class<Type> entityClass,
			final Object id) {
		final TieredTable table = this.registry.getTable(entityClass);
		final TableProperties tableProperties = this.properties.of(table);
		final TieredRowKey key = this.tieredRepository.keyOf(table, id);
		Optional<Type> entity = this.tieredRepository.findHot(table, entityClass, key);
		if (entity.isPresent()) {
			this.requestTouchIfStale(table, tableProperties, key);
		}
		else if ((tableProperties.getRehydration() == Rehydration.INSERT) && ColdTieringServiceComponent.isColdReadable(tableProperties)) {
			final Optional<ColdRow> coldRow = this.strategyOf(table).findCold(table, key);
			entity = coldRow.flatMap(row -> this.serveCold(table, tableProperties, entityClass, row).hot());
			ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} found={}", table.getId(), coldRow.isPresent());
		}
		return entity;
	}

	/**
	 * Newest row of a key prefix on or before a moment, across tiers.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class.
	 * @param  keyPrefix   Key prefix.
	 * @param  asOf        Inclusive upper bound, or null.
	 * @return             Hit.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public <Type> Optional<TieredHit<Type>> findNewestTiered(
			final Class<Type> entityClass,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final TieredTable table = this.registry.getTable(entityClass);
		final TableProperties tableProperties = this.properties.of(table);
		final Map<String, String> prefix = ColdTieringServiceComponent.validatedPrefix(table, keyPrefix);
		final Optional<Type> newestHot = this.tieredRepository.findNewestHot(table, entityClass, keyPrefix, asOf);
		final Optional<TieredRowKey> hotKey = newestHot.map(entity -> this.tieredRepository.keyOfEntity(table, entity));
		final Optional<ColdRow> newestCold = ColdTieringServiceComponent.isColdReadable(tableProperties)
				? this.strategyOf(table).findNewestCold(table, prefix, asOf, hotKey)
				: Optional.empty();
		Optional<TieredHit<Type>> hit;
		if (newestCold.isPresent()) {
			hit = Optional.of(this.serveCold(table, tableProperties, entityClass, newestCold.get()));
		}
		else {
			hit = newestHot.map(entity -> new TieredHit<>(entity, (ColdRow) null));
			hotKey.ifPresent(key -> this.requestTouchIfStale(table, tableProperties, key));
		}
		return hit;
	}

	/**
	 * First row of a key prefix with the version in {@code (after, upTo]}, across tiers.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class.
	 * @param  keyPrefix   Key prefix.
	 * @param  after       Exclusive lower bound, or null.
	 * @param  upTo        Inclusive upper bound, or null.
	 * @return             Hit.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public <Type> Optional<TieredHit<Type>> findFirstTiered(
			final Class<Type> entityClass,
			final Map<String, Object> keyPrefix,
			final LocalDateTime after,
			final LocalDateTime upTo) {
		final TieredTable table = this.registry.getTable(entityClass);
		final TableProperties tableProperties = this.properties.of(table);
		final Map<String, String> prefix = ColdTieringServiceComponent.validatedPrefix(table, keyPrefix);
		final Optional<Type> firstHot = this.tieredRepository.findFirstHot(table, entityClass, keyPrefix, after, upTo);
		final Optional<TieredRowKey> hotKey = firstHot.map(entity -> this.tieredRepository.keyOfEntity(table, entity));
		final Optional<ColdRow> firstCold = ColdTieringServiceComponent.isColdReadable(tableProperties)
				? this.strategyOf(table).findFirstCold(table, prefix, after, upTo, hotKey)
				: Optional.empty();
		Optional<TieredHit<Type>> hit;
		if (firstCold.isPresent()) {
			hit = Optional.of(this.serveCold(table, tableProperties, entityClass, firstCold.get()));
		}
		else {
			hit = firstHot.map(entity -> new TieredHit<>(entity, (ColdRow) null));
			hotKey.ifPresent(key -> this.requestTouchIfStale(table, tableProperties, key));
		}
		return hit;
	}

	/**
	 * Every row of a key prefix on or before a moment, newest first (by the last key column, then
	 * the whole key): the hot rows, plus the cold rows whose key no hot row holds (hot wins per
	 * key), served through the table's rehydration.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class.
	 * @param  keyPrefix   Key prefix.
	 * @param  asOf        Inclusive upper bound, or null.
	 * @return             Hits.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public <Type> List<TieredHit<Type>> findTimelineTiered(
			final Class<Type> entityClass,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final TieredTable table = this.registry.getTable(entityClass);
		final TableProperties tableProperties = this.properties.of(table);
		final Map<String, String> prefix = ColdTieringServiceComponent.validatedPrefix(table, keyPrefix);
		final Map<String, TieredHit<Type>> hits = new LinkedHashMap<>();
		for (final Type entity : this.tieredRepository.findAllHot(table, entityClass, keyPrefix, asOf)) {
			final TieredRowKey key = this.tieredRepository.keyOfEntity(table, entity);
			hits.put(ColdTieringServiceComponent.timelineKeyOf(table, key), new TieredHit<>(entity, (ColdRow) null));
			this.requestTouchIfStale(table, tableProperties, key);
		}
		if (ColdTieringServiceComponent.isColdReadable(tableProperties)) {
			for (final ColdRow row : this.strategyOf(table).findTimelineCold(table, prefix, asOf)) {
				final String sortKey = ColdTieringServiceComponent.timelineKeyOf(table, row.key());
				if (!hits.containsKey(sortKey)) {
					hits.put(sortKey, this.serveCold(table, tableProperties, entityClass, row));
				}
			}
		}
		final List<String> segments = new ArrayList<>(hits.keySet());
		segments.sort(Comparator.reverseOrder());
		final List<TieredHit<Type>> timeline = new ArrayList<>();
		for (final String segment : segments) {
			timeline.add(hits.get(segment));
		}
		return timeline;
	}

	/**
	 * Rows by entity ids, one lookup each: hot hits, then the missing ids read cold and rehydrated (through
	 * {@link #findByIdTiered}, so on a {@link Rehydration#NONE} table only hot rows are
	 * returned). Keys with no row are left out.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class.
	 * @param  ids         Entity ids.
	 * @return             Hits, in input order.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public <Type> List<TieredHit<Type>> findAllByIdsTiered(
			final Class<Type> entityClass,
			final Collection<?> ids) {
		final List<TieredHit<Type>> hits = new ArrayList<>();
		for (final Object id : ids) {
			this.findByIdTiered(entityClass, id).ifPresent(entity -> hits.add(new TieredHit<>(entity, (ColdRow) null)));
		}
		return hits;
	}

	/**
	 * Deletes a row from both tiers (row tiering). On a partition-tiered table the archive holds
	 * whole partitions, so a row of an archived partition cannot be deleted
	 * ({@code coldTiering.delete.archived}); a row of a partition not archived loses its hot row.
	 *
	 * @param key Key.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public void delete(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		if (table.getArchiveUnit() == ArchiveUnit.ROW) {
			this.tieredRepository.delete(table, key);
		}
		else if (this.strategyOf(table).isArchived(table, key)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.delete.archived", new Object[] { table.getId() }));
		}
		else {
			this.hotTierRepository.delete(table, key);
		}
	}

}
