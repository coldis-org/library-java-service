package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.model.Rehydration;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.PayloadCodec;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
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
 * The policy of the cold tiering: when a write is uploaded, when a read goes to the cold tier and
 * whether it rehydrates, when a read touches, how a row is archived. Everything it does with the
 * database and the store goes through the repositories ({@link TieredRepository},
 * {@link HotTierRepository}, {@link ColdStorageRepository}), never the other way round. Batches
 * (backfill, eviction) are in {@link ColdTieringBatchServiceComponent}. Design:
 * {@code doc/cold-storage-tiering.md}.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class ColdTieringServiceComponent {

	/** Write-through upload queue. */
	private static final String UPLOAD_QUEUE = "cold-tiering/upload";

	/** Backfill archive queue: its own, so write-through uploads never wait behind a backfill. */
	private static final String BACKFILL_QUEUE = "cold-tiering/backfill";

	/** Touch queue. */
	private static final String TOUCH_QUEUE = "cold-tiering/touch";

	/** Object metadata: the row's watermark when archived. */
	public static final String UPDATED_AT_METADATA = "updated-at";

	/** Object metadata: the table id. */
	public static final String TABLE_METADATA = "table";

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ColdTieringServiceComponent.class);

	/** Registry. */
	@Autowired
	private TieredTableRegistry registry;

	/** Properties. */
	@Autowired
	private ColdTieringProperties properties;

	/** Tiered access. */
	@Autowired
	private TieredRepository tieredRepository;

	/** Hot tier. */
	@Autowired
	private HotTierRepository hotTierRepository;

	/** Cold tier. */
	@Autowired
	private ColdStorageRepository coldStorageRepository;

	/** Codec. */
	@Autowired
	private PayloadCodec codec;

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** JMS template helper. */
	@Autowired
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Posts a row key to a queue after the commit when inside a transaction, so the consumer never
	 * reads the row before the write is visible.
	 *
	 * @param queue Queue.
	 * @param key   Row key.
	 */
	private void sendAfterCommit(
			final String queue,
			final TieredRowKey key) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<Serializable>().withDestination(queue).withMessage(key).withAfterCommit(true));
	}

	/**
	 * Posts a row key to a queue without deferring it to a commit callback (the JMS template's own
	 * session transaction, if any, still decides when the broker releases it).
	 *
	 * @param queue Queue.
	 * @param key   Row key.
	 */
	private void sendNow(
			final String queue,
			final TieredRowKey key) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<Serializable>().withDestination(queue).withMessage(key).withAfterCommit(false));
	}

	/**
	 * Requests a touch when the row's clock is older than the threshold, so a hot row read many
	 * times a day costs one update.
	 *
	 * @param table           Table.
	 * @param tableProperties Table properties.
	 * @param key             Row key.
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
	 * Whether a table's cold tier may be read.
	 *
	 * @param  tableProperties Table properties.
	 * @return                 Whether cold reads are on.
	 */
	private static Boolean isColdReadable(
			final TableProperties tableProperties) {
		return tableProperties.getEnabled() && tableProperties.getColdReadEnabled();
	}

	/**
	 * The row key of a table from column values (any JDBC-compatible objects).
	 *
	 * @param  tableId Table id.
	 * @param  values  Key column values.
	 * @return         The key.
	 */
	public TieredRowKey keyOf(
			final String tableId,
			final Map<String, Object> values) {
		return this.tieredRepository.keyOf(this.registry.getTable(tableId), values);
	}

	/**
	 * The row key of an entity id.
	 *
	 * @param  entityClass Entity class bound to a table.
	 * @param  id          Entity id.
	 * @return             The key.
	 */
	public TieredRowKey keyOf(
			final Class<?> entityClass,
			final Object id) {
		return this.tieredRepository.keyOf(this.registry.getTable(entityClass), id);
	}

	/**
	 * The cold-tier object key of a row.
	 *
	 * @param  key Row key.
	 * @return     The object key.
	 */
	public String objectKeyOf(
			final TieredRowKey key) {
		return this.tieredRepository.objectKeyOf(this.registry.getTable(key.tableId()), key);
	}

	/**
	 * To be called after a row is written (in the same transaction): starts its hot-tier clock,
	 * drops its archive mark and requests its write-through upload after the commit.
	 *
	 * @param key Row key.
	 */
	public void afterWrite(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		if (this.properties.of(table).getEnabled()) {
			this.hotTierRepository.afterWrite(table, key, DateTimeHelper.getCurrentLocalDateTime());
			this.sendAfterCommit(ColdTieringServiceComponent.UPLOAD_QUEUE, key);
		}
	}

	/**
	 * Requests the archive of a row through the backfill queue: the backfill's request, not tied to
	 * a business write, so not deferred to a commit callback (a session-transacted JMS template
	 * still releases it when its transaction commits). The backfill listener archives it in its
	 * own transaction; a duplicate request (a restarted run re-enqueueing pending keys, a key also
	 * written meanwhile) is harmless, since {@link #archive(TieredRowKey)} uploads nothing for
	 * content already archived.
	 *
	 * @param key Row key.
	 */
	public void requestArchive(
			final TieredRowKey key) {
		this.sendNow(ColdTieringServiceComponent.BACKFILL_QUEUE, key);
	}

	/**
	 * Archives a row in the caller's transaction (the upload listener, for write-through and
	 * backfill requests): under the row's lock, uploads the whole row to its derived key and marks
	 * the hot row archived at the watermark uploaded, if it was not rewritten meanwhile. The lock is
	 * what orders concurrent archives of one key, since every one of them writes the same object:
	 * the holder reads the current content, so an object is never overwritten with older content.
	 * A row whose current content is already archived is left alone (a duplicate request).
	 *
	 * @param  key Row key.
	 * @return     Whether an object was written (false: the row is not hot, or already archived).
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public Boolean archive(
			final TieredRowKey key) {
		Boolean isUploaded = false;
		final TieredTable table = this.registry.getTable(key.tableId());
		final Optional<HotRow> hotRow = this.hotTierRepository.lockForArchive(table, key);
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
		ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_UPLOAD] table={} uploaded={}", key.tableId(), isUploaded);
		return isUploaded;
	}

	/**
	 * Write-through upload consumer: one row per message, in its own transaction.
	 *
	 * @param key Row key.
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
	 * Backfill archive consumer: one row per message, in its own transaction, so the row lock
	 * covers only that row's upload; its concurrency sets the backfill's throughput.
	 *
	 * @param key Row key.
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
	 * Touch consumer: extends the hot-tier clock, never backwards.
	 *
	 * @param key Row key.
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
		if (table.isTouching()) {
			this.hotTierRepository.touch(table, key, DateTimeHelper.getCurrentLocalDateTime());
		}
	}

	/**
	 * Reads a row from the cold tier, whatever its hot state, when the table allows cold reads.
	 * Never writes the hot table (the read path of {@link Rehydration#NONE} tables).
	 *
	 * @param  key Row key.
	 * @return     The cold row, or empty when there is no object or cold reads are off.
	 */
	public Optional<ColdRow> readCold(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		Optional<ColdRow> coldRow = Optional.empty();
		if (ColdTieringServiceComponent.isColdReadable(this.properties.of(table))) {
			coldRow = this.tieredRepository.loadCold(table, key);
			ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} found={}", table.getId(), coldRow.isPresent());
		}
		return coldRow;
	}

	/**
	 * Reads a row from the cold tier by entity id.
	 *
	 * @param  entityClass Entity class bound to a table.
	 * @param  id          Entity id.
	 * @return             The cold row, or empty.
	 */
	public Optional<ColdRow> findColdRow(
			final Class<?> entityClass,
			final Object id) {
		return this.readCold(this.keyOf(entityClass, id));
	}

	/**
	 * Reads a row: hot (touching its clock when stale), else, on a table that rehydrates and
	 * allows cold reads, from the cold tier, rehydrated and read again. On a
	 * {@link Rehydration#NONE} table a cold row is a miss here: {@link #findColdRow(Class, Object)}
	 * serves it.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class bound to a table.
	 * @param  id          Entity id.
	 * @return             The entity, or empty.
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
			final Optional<ColdRow> coldRow = this.tieredRepository.loadCold(table, key);
			if (coldRow.isPresent()) {
				this.tieredRepository.rehydrate(table, coldRow.get(), DateTimeHelper.getCurrentLocalDateTime());
				entity = this.tieredRepository.findHot(table, entityClass, key);
			}
			ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} found={}", table.getId(), coldRow.isPresent());
		}
		return entity;
	}

	/**
	 * The newest row of a key prefix on or before a moment, hot or cold (a versioned table): the
	 * newer of the newest hot row and the newest archived one. A cold winner is rehydrated and
	 * served hot on a table that rehydrates, served as a cold row otherwise.
	 *
	 * @param  <Type>      Entity type.
	 * @param  entityClass Entity class bound to a table.
	 * @param  keyPrefix   Values of every key column but the last, by column name.
	 * @param  asOf        Upper bound of the last key column, or null for the newest.
	 * @return             The hit, or empty.
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
		final Optional<Type> newestHot = this.tieredRepository.findNewestHot(table, entityClass, keyPrefix, asOf);
		final Optional<TieredRowKey> newestColdKey = ColdTieringServiceComponent.isColdReadable(tableProperties)
				? this.tieredRepository.findNewestColdKey(table, keyPrefix, asOf)
				: Optional.empty();
		Optional<TieredHit<Type>> hit = newestHot.map(entity -> new TieredHit<>(entity, null));
		if (newestColdKey.isPresent()) {
			final String lastColumn = table.getLastKeyColumn();
			final String lastSegment = ColumnValues.toSegment(table.getColumnTypes().get(lastColumn), newestColdKey.get().values().get(lastColumn));
			final Boolean isColdNewer = newestHot.isEmpty() || (ColumnValues.toSegment(table.getColumnTypes().get(lastColumn),
					this.tieredRepository.keyOfEntity(table, newestHot.get()).values().get(lastColumn)).compareTo(lastSegment) < 0);
			if (isColdNewer) {
				final Optional<ColdRow> coldRow = this.tieredRepository.loadCold(table, newestColdKey.get());
				if (coldRow.isPresent() && (tableProperties.getRehydration() == Rehydration.INSERT)) {
					this.tieredRepository.rehydrate(table, coldRow.get(), DateTimeHelper.getCurrentLocalDateTime());
					hit = this.tieredRepository.findHot(table, entityClass, newestColdKey.get()).map(entity -> new TieredHit<>(entity, null));
				}
				else if (coldRow.isPresent()) {
					hit = Optional.of(new TieredHit<>(null, coldRow.get()));
				}
				ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} found={}", table.getId(), coldRow.isPresent());
			}
			else if (newestHot.isPresent()) {
				this.requestTouchIfStale(table, tableProperties, this.tieredRepository.keyOfEntity(table, newestHot.get()));
			}
		}
		else if (newestHot.isPresent()) {
			this.requestTouchIfStale(table, tableProperties, this.tieredRepository.keyOfEntity(table, newestHot.get()));
		}
		return hit;
	}

	/**
	 * Removes a row from both tiers (a business delete must not leave the cold copy behind).
	 *
	 * @param key Row key.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public void delete(
			final TieredRowKey key) {
		this.tieredRepository.delete(this.registry.getTable(key.tableId()), key);
	}

}
