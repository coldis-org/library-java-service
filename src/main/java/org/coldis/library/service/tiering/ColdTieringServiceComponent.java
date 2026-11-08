package org.coldis.library.service.tiering;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.HotTierRepository.HotRow;
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
 * Cold tiering of the registered tables: write-through upload after a write, archive (upload
 * under the row lock, confirm on the row), touch, and reads that fall back to the cold tier
 * (object key derived from the row key) and rehydrate when the table says so. Batches (backfill,
 * eviction) are in {@link ColdTieringBatchServiceComponent}. Design:
 * {@code doc/cold-storage-tiering.md}.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class ColdTieringServiceComponent {

	/** Write-through upload queue. */
	public static final String UPLOAD_QUEUE = "cold-tiering/upload";

	/** Touch queue. */
	public static final String TOUCH_QUEUE = "cold-tiering/touch";

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

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** JMS template helper. */
	@Autowired
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Posts a row key to a queue, after the commit when inside a transaction, so the consumer never
	 * reads the row before the write is visible.
	 *
	 * @param queue Queue.
	 * @param key   Row key.
	 */
	private void send(
			final String queue,
			final TieredRowKey key) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<Serializable>().withDestination(queue).withMessage(key).withAfterCommit(true));
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
			final Optional<LocalDateTime> clock = this.hotTierRepository.clock(table, this.registry.getColumnTypes(table), key);
			if (clock.isPresent() && clock.get().isBefore(staleBefore)) {
				this.send(ColdTieringServiceComponent.TOUCH_QUEUE, key);
			}
		}
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
		final TieredTable table = this.registry.getTable(tableId);
		final Map<String, String> formatted = new LinkedHashMap<>();
		for (final String column : table.getKeyColumns()) {
			final Object value = values.get(column);
			if (value == null) {
				throw new IntegrationException(new SimpleMessage("coldTiering.key.incomplete", new Object[] { tableId, column }));
			}
			formatted.put(column, ColumnValues.format(value));
		}
		return new TieredRowKey(tableId, formatted);
	}

	/**
	 * The cold-tier object key of a row.
	 *
	 * @param  key Row key.
	 * @return     The object key.
	 */
	public String objectKeyOf(
			final TieredRowKey key) {
		final TieredTable table = this.registry.getTable(key.tableId());
		return this.objectKeyStrategy.keyOf(this.properties.getPrefix(), table, ColumnValues.toSegments(table, this.registry.getColumnTypes(table), key.values()),
				this.codec.extension());
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
		final TableProperties tableProperties = this.properties.of(table);
		if (tableProperties.getEnabled()) {
			this.hotTierRepository.afterWrite(table, this.registry.getColumnTypes(table), key, DateTimeHelper.getCurrentLocalDateTime());
			this.send(ColdTieringServiceComponent.UPLOAD_QUEUE, key);
		}
	}

	/**
	 * Archives a row in the caller's transaction (write-through upload and backfill): under the
	 * row's lock, uploads the whole row to its derived key and marks the hot row archived if it
	 * was not rewritten meanwhile. The lock is what orders concurrent archives of one key, since
	 * every one of them writes the same object: the holder reads the current content, so an
	 * object is never overwritten with older content.
	 *
	 * @param  key Row key.
	 * @return     Whether an object was written (false: the row is not hot).
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.long-timeout}"
	)
	public Boolean archive(
			final TieredRowKey key) {
		Boolean uploaded = false;
		final TieredTable table = this.registry.getTable(key.tableId());
		final Map<String, String> columnTypes = this.registry.getColumnTypes(table);
		final Optional<HotRow> hotRow = this.hotTierRepository.lockForArchive(table, columnTypes, key);
		if (hotRow.isPresent()) {
			final LocalDateTime archivedMoment = DateTimeHelper.getCurrentLocalDateTime();
			final String objectKey = this.objectKeyOf(key);
			this.coldStorageRepository.store(objectKey, this.codec.encode(hotRow.get().rowJson()),
					Map.of(ColdTieringServiceComponent.TABLE_METADATA, table.getId(), ColdTieringServiceComponent.UPDATED_AT_METADATA,
							ColumnValues.format(hotRow.get().watermark()), "content-type", this.codec.contentType()));
			uploaded = true;
			final Integer marked = this.hotTierRepository.markArchived(table, columnTypes, key, hotRow.get().watermark(), archivedMoment);
			if (marked == 0) {
				ColdTieringServiceComponent.LOGGER.warn("[COLD_TIERING_UPLOAD] table={} key={} rewritten during the archive; left for its own upload or the backfill",
						table.getId(), key.values());
			}
		}
		ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_UPLOAD] table={} key={} uploaded={}", key.tableId(), key.values(), uploaded);
		return uploaded;
	}

	/**
	 * Write-through upload consumer.
	 *
	 * @param key Row key.
	 */
	@JmsListener(
			destination = ColdTieringServiceComponent.UPLOAD_QUEUE,
			concurrency = "${" + ColdTieringProperties.PREFIX + ".upload.concurrency:1-4}",
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
			this.hotTierRepository.touch(table, this.registry.getColumnTypes(table), key, DateTimeHelper.getCurrentLocalDateTime());
		}
	}

	/**
	 * Reads a row from the cold tier, whatever its hot state: one {@code GET} on the derived key.
	 * Never writes the hot table (the read path of {@link Rehydration#NONE} tables).
	 *
	 * @param  key Row key.
	 * @return     The cold row, or empty when there is no object or cold reads are off.
	 */
	public Optional<ColdRow> readCold(
			final TieredRowKey key) {
		Optional<ColdRow> coldRow = Optional.empty();
		final TieredTable table = this.registry.getTable(key.tableId());
		final TableProperties tableProperties = this.properties.of(table);
		if (tableProperties.getEnabled() && tableProperties.getColdReadEnabled()) {
			coldRow = this.coldStorageRepository.load(this.objectKeyOf(key)).map(content -> new ColdRow(key, this.codec.decode(content)));
			ColdTieringServiceComponent.LOGGER.debug("[COLD_TIERING_COLD_READ] table={} key={} found={}", table.getId(), key.values(), coldRow.isPresent());
		}
		return coldRow;
	}

	/**
	 * Reads a row: hot (touching its clock when stale), else from the cold tier, rehydrated into
	 * the hot table and read again through the hot lookup. On a {@link Rehydration#NONE} table a
	 * cold row is a miss here; {@link #readCold(TieredRowKey)} serves it.
	 *
	 * @param  <Type>    Row type.
	 * @param  key       Row key.
	 * @param  hotLookup Hot table lookup (the consumer's JPA query).
	 * @return           The row, or empty.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.regular-timeout}"
	)
	public <Type> Optional<Type> readThrough(
			final TieredRowKey key,
			final Supplier<Optional<Type>> hotLookup) {
		final TieredTable table = this.registry.getTable(key.tableId());
		final TableProperties tableProperties = this.properties.of(table);
		Optional<Type> row = hotLookup.get();
		if (row.isPresent()) {
			this.requestTouchIfStale(table, tableProperties, key);
		}
		else if (tableProperties.getRehydration() == Rehydration.INSERT) {
			final Optional<ColdRow> coldRow = this.readCold(key);
			if (coldRow.isPresent()) {
				this.hotTierRepository.rehydrate(table, this.registry.getColumnTypes(table), key, coldRow.get().rowJson(), DateTimeHelper.getCurrentLocalDateTime());
				row = hotLookup.get();
			}
		}
		return row;
	}

	/**
	 * The newest archived row of a key prefix on or before a moment, on a versioned table: a
	 * {@code LIST} of the other key columns' prefix, picking the greatest name not after the
	 * moment. Rows written before their first archive are not there yet: compare with the hot
	 * table's own newest ({@link TieredRepository#findNewestTiered(Map, LocalDateTime)} does).
	 *
	 * @param  tableId   Table id.
	 * @param  keyPrefix Values of every key column but the last.
	 * @param  asOf      Upper bound of the last key column, or null for the newest.
	 * @return           The row key, or empty.
	 */
	public Optional<TieredRowKey> findNewestCold(
			final String tableId,
			final Map<String, Object> keyPrefix,
			final LocalDateTime asOf) {
		final TieredTable table = this.registry.getTable(tableId);
		final TableProperties tableProperties = this.properties.of(table);
		if (!table.isVersioned()) {
			throw new IntegrationException(new SimpleMessage("coldTiering.table.not.versioned", new Object[] { tableId }));
		}
		Optional<TieredRowKey> newest = Optional.empty();
		if (tableProperties.getEnabled() && tableProperties.getColdReadEnabled()) {
			final Map<String, String> columnTypes = this.registry.getColumnTypes(table);
			final Map<String, String> formatted = new LinkedHashMap<>();
			keyPrefix.forEach((
					column,
					value) -> formatted.put(column, ColumnValues.format(value)));
			final List<String> segments = ColumnValues.toSegments(table, columnTypes, formatted);
			if (segments.size() != (table.getKeyColumns().size() - 1)) {
				throw new IntegrationException(new SimpleMessage("coldTiering.key.prefix.incomplete", new Object[] { tableId }));
			}
			final String lastType = columnTypes.get(table.getLastKeyColumn());
			final String ceiling = asOf == null ? null : ColumnValues.toSegment(lastType, asOf.toString());
			String newestSegment = null;
			for (final String objectKey : this.coldStorageRepository.list(this.objectKeyStrategy.prefixOf(this.properties.getPrefix(), table, segments))) {
				final String segment = this.objectKeyStrategy.lastSegmentOf(objectKey, this.codec.extension());
				if (((ceiling == null) || (segment.compareTo(ceiling) <= 0)) && ((newestSegment == null) || (segment.compareTo(newestSegment) > 0))) {
					newestSegment = segment;
				}
			}
			if (newestSegment != null) {
				final Map<String, String> values = new LinkedHashMap<>(formatted);
				values.put(table.getLastKeyColumn(), ColumnValues.fromSegment(lastType, newestSegment));
				newest = Optional.of(new TieredRowKey(tableId, values));
			}
		}
		return newest;
	}

}
