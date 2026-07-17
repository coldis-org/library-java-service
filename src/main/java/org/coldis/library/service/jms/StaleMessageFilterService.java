package org.coldis.library.service.jms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.coldis.library.helper.DateTimeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stale message filter service.
 *
 * Keeps track of when messages with a stale filter key last started successful
 * processing, so consumers can drop messages that were posted before a
 * same-key processing started (the processing already reflected the state that
 * originated the message). Only suitable for refresh-style messages.
 *
 * Records live in an in-memory map (the per-message hot path) and are
 * periodically synchronized with a shared database table so instances learn
 * about each other's processing. The filter is lenient by design: it fails
 * open on any missing or stale information, and cross-instance knowledge lags
 * by up to one synchronization interval.
 *
 * Processing timestamps are epoch millis from the application clock
 * ({@code DateTimeHelper}); posted-at timestamps come from producers (JMS
 * timestamp or scheduled delivery time) and the clock skew margin absorbs
 * small divergences between the two.
 */
@Component
public class StaleMessageFilterService {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(StaleMessageFilterService.class);

	/**
	 * Separator between destination and key in processing record keys.
	 */
	private static final String ENTRY_KEY_SEPARATOR = "|";

	/**
	 * Stale message filter properties.
	 */
	private final StaleMessageFilterProperties properties;

	/**
	 * Data source provider (resolved lazily so the filter never forces early data
	 * source initialization).
	 */
	private final ObjectProvider<DataSource> dataSourceProvider;

	/**
	 * Processing start timestamp by entry key (destination + key).
	 */
	private final ConcurrentHashMap<String, Long> processingStartByEntryKey;

	/**
	 * Entry keys with local processing records not yet pushed to the shared
	 * store.
	 */
	private final Set<String> entryKeysPendingPush = ConcurrentHashMap.newKeySet();

	/**
	 * Shared store (lazily initialized when persistence is enabled and a data
	 * source is available).
	 */
	private volatile StaleMessageFilterJdbcStore sharedStore;

	/**
	 * If the shared store schema has been ensured.
	 */
	private volatile boolean schemaEnsured;

	/**
	 * Highest "synchronized at" value pulled from the shared store (database
	 * clock domain).
	 */
	private volatile long pullWatermark;

	/**
	 * Default constructor.
	 *
	 * @param properties         Stale message filter properties.
	 * @param dataSourceProvider Data source provider (may be null when used
	 *                               outside a Spring context).
	 */
	public StaleMessageFilterService(final StaleMessageFilterProperties properties, final ObjectProvider<DataSource> dataSourceProvider) {
		this.properties = properties;
		this.dataSourceProvider = dataSourceProvider;
		this.processingStartByEntryKey = new ConcurrentHashMap<>(properties.getInitialCapacity());
	}

	/**
	 * If the filter is enabled.
	 *
	 * @return If the filter is enabled.
	 */
	public boolean isEnabled() {
		return this.properties.getEnabled();
	}

	/**
	 * Composes the processing record key for a destination and message key.
	 */
	private static String composeEntryKey(
			final String destination,
			final String messageKey) {
		return destination + StaleMessageFilterService.ENTRY_KEY_SEPARATOR + messageKey;
	}

	/**
	 * Checks if a same-key processing started after the given message was posted
	 * (in which case the message is stale and can be dropped).
	 *
	 * @param  destination       Message destination.
	 * @param  messageKey        Stale filter key.
	 * @param  postedAtTimestamp When the message was posted (wall-clock epoch
	 *                               millis).
	 * @return                   If a same-key processing started after the
	 *                           message was posted (with the clock skew margin).
	 */
	public boolean hasNewerProcessing(
			final String destination,
			final String messageKey,
			final long postedAtTimestamp) {
		boolean newerProcessing = false;
		if (this.isEnabled()) {
			final Long lastProcessingStart = this.processingStartByEntryKey.get(StaleMessageFilterService.composeEntryKey(destination, messageKey));
			newerProcessing = ((lastProcessingStart != null)
					&& ((postedAtTimestamp + this.properties.getClockSkewMargin().toMillis()) < lastProcessingStart));
		}
		return newerProcessing;
	}

	/**
	 * Records that a message with the given key started successful processing.
	 *
	 * @param destination              Message destination.
	 * @param messageKey               Stale filter key.
	 * @param processingStartTimestamp When the processing started (wall-clock
	 *                                     epoch millis).
	 */
	public void recordProcessing(
			final String destination,
			final String messageKey,
			final long processingStartTimestamp) {
		if (this.isEnabled()) {
			final String entryKey = StaleMessageFilterService.composeEntryKey(destination, messageKey);
			this.processingStartByEntryKey.merge(entryKey, processingStartTimestamp, Math::max);
			this.entryKeysPendingPush.add(entryKey);
		}
	}

	/**
	 * Gets the shared store, initializing it when persistence is enabled and a
	 * data source is available.
	 *
	 * @return The shared store, or null when unavailable.
	 */
	private StaleMessageFilterJdbcStore getSharedStore() {
		if ((this.sharedStore == null) && this.properties.getPersistenceEnabled() && (this.dataSourceProvider != null)) {
			final DataSource dataSource = this.dataSourceProvider.getIfAvailable();
			if (dataSource != null) {
				this.sharedStore = new StaleMessageFilterJdbcStore(dataSource, this.properties.getTableName());
			}
		}
		return this.sharedStore;
	}

	/**
	 * Ensures the shared store schema exists.
	 */
	private void ensureSchema(
			final StaleMessageFilterJdbcStore store) {
		if (!this.schemaEnsured) {
			if (this.properties.getCreateSchema()) {
				store.ensureSchema();
			}
			this.schemaEnsured = true;
		}
	}

	/**
	 * Pushes pending local processing records to the shared store. Failed pushes
	 * are re-queued for the next synchronization.
	 */
	private void pushPendingRecords(
			final StaleMessageFilterJdbcStore store) {
		final Map<String, Long> pendingRecords = new HashMap<>();
		for (final String entryKey : this.entryKeysPendingPush) {
			this.entryKeysPendingPush.remove(entryKey);
			final Long processingStart = this.processingStartByEntryKey.get(entryKey);
			if (processingStart != null) {
				pendingRecords.put(entryKey, processingStart);
			}
		}
		if (!pendingRecords.isEmpty()) {
			try {
				store.push(pendingRecords);
			}
			catch (final Exception exception) {
				this.entryKeysPendingPush.addAll(pendingRecords.keySet());
				throw exception;
			}
		}
	}

	/**
	 * Pulls processing records other instances synchronized since the last pull.
	 * The watermark is re-read with an overlap of one synchronization interval so
	 * records committed late are not missed (merges are idempotent).
	 */
	private void pullSharedRecords(
			final StaleMessageFilterJdbcStore store) {
		final long overlapMillis = this.properties.getSynchronizationInterval().toMillis();
		final List<StaleMessageFilterJdbcStore.ProcessingRecord> pulledRecords = store.pull(Math.max(0, this.pullWatermark - overlapMillis));
		long newWatermark = this.pullWatermark;
		for (final StaleMessageFilterJdbcStore.ProcessingRecord pulledRecord : pulledRecords) {
			this.processingStartByEntryKey.merge(pulledRecord.messageKey(), pulledRecord.processedAt(), Math::max);
			newWatermark = Math.max(newWatermark, pulledRecord.synchronizedAt());
		}
		this.pullWatermark = newWatermark;
	}

	/**
	 * Removes local processing records older than the window.
	 */
	private void sweepLocalRecords() {
		final long cutoff = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) - this.properties.getWindow().toMillis();
		this.processingStartByEntryKey.values().removeIf(processingStart -> processingStart < cutoff);
	}

	/**
	 * Synchronizes processing records with the shared store: pushes local
	 * records, pulls records from other instances, and expires records older
	 * than the window. Failures are logged and never propagate (the filter fails
	 * open until the next synchronization).
	 */
	@Scheduled(fixedDelayString = "${org.coldis.library.service.jms.stale-filter.synchronization-interval:10s}")
	public void synchronize() {
		if (this.isEnabled()) {
			this.sweepLocalRecords();
			try {
				final StaleMessageFilterJdbcStore store = this.getSharedStore();
				if (store != null) {
					this.ensureSchema(store);
					this.pushPendingRecords(store);
					this.pullSharedRecords(store);
					store.deleteOlderThanWindow(this.properties.getWindow().toMillis());
				}
			}
			catch (final Exception exception) {
				StaleMessageFilterService.LOGGER
						.warn("Stale message filter synchronization failed (filter fails open until the next synchronization): "
								+ exception.getLocalizedMessage());
				StaleMessageFilterService.LOGGER.debug("Stale message filter synchronization failed.", exception);
			}
		}
	}

}
