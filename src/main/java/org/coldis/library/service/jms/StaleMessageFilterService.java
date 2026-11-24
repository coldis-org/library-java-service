package org.coldis.library.service.jms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import javax.sql.DataSource;

import org.coldis.library.helper.DateTimeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
 * about each other's processing. Only the destinations this instance has seen
 * keyed messages on are synchronized; until it sees one, the synchronization
 * does not touch the database. The filter is lenient by design: it fails open
 * on any missing or stale information, and cross-instance knowledge lags by up
 * to one synchronization interval.
 *
 * Processing timestamps are epoch millis from the application clock
 * ({@code DateTimeHelper}); posted-at timestamps come from producers (JMS
 * timestamp or scheduled delivery time) and the clock skew margin absorbs
 * small divergences between the two.
 */
@Component
public class StaleMessageFilterService {

	/**
	 * Synchronization phase duration (tags: phase, outcome).
	 */
	public static final String SYNC_METRIC = "jms.stale.filter.sync";

	/**
	 * Rows pushed, pulled or deleted by the synchronization (tag: phase).
	 */
	public static final String SYNC_ROWS_METRIC = "jms.stale.filter.sync.rows";

	/**
	 * Processing records held in memory.
	 */
	public static final String RECORDS_METRIC = "jms.stale.filter.records";

	/**
	 * Synchronization thread name.
	 */
	public static final String SYNC_THREAD_NAME = "jms-stale-filter-sync";

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(StaleMessageFilterService.class);

	/**
	 * Separator between destination and key in processing record keys.
	 */
	private static final String ENTRY_KEY_SEPARATOR = "|";

	/**
	 * Row count a synchronization phase returns when it did not run.
	 */
	private static final int SKIPPED_ROWS = -1;

	/**
	 * Where the newest processing record of a key came from.
	 */
	public enum Source {

		/** Recorded by this instance. */
		LOCAL,

		/** Pulled from the shared store (recorded by another instance). */
		PULLED;

		/**
		 * Gets the metric tag value.
		 *
		 * @return The metric tag value.
		 */
		public String getTag() {
			return this.name().toLowerCase();
		}

	}

	/**
	 * Processing start of a key, and if it was pulled from the shared store.
	 */
	private record ProcessingStart(long timestamp, boolean pulled) {}

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
	 * Meter registry provider (may be null).
	 */
	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	/**
	 * Newest processing start by entry key (destination + key).
	 */
	private final ConcurrentHashMap<String, ProcessingStart> processingStartByEntryKey;

	/**
	 * Entry keys with local processing records not yet pushed to the shared
	 * store.
	 */
	private final Set<String> entryKeysPendingPush = ConcurrentHashMap.newKeySet();

	/**
	 * Destinations this instance has seen keyed messages on (the only ones
	 * synchronized).
	 */
	private final Set<String> keyedDestinations = ConcurrentHashMap.newKeySet();

	/**
	 * Meter registry (resolved on first use).
	 */
	private volatile MeterRegistry meterRegistry;

	/**
	 * Synchronization scheduler (null when not started).
	 */
	private ScheduledExecutorService synchronizationScheduler;

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
	 * Number of keyed destinations covered by the last pull (a new destination
	 * restarts the pull from the beginning of the window).
	 */
	private volatile int pulledDestinationCount;

	/**
	 * Constructor.
	 *
	 * @param properties            Stale message filter properties.
	 * @param dataSourceProvider    Data source provider (may be null when used
	 *                                  outside a Spring context).
	 * @param meterRegistryProvider Meter registry provider (may be null).
	 */
	@Autowired
	public StaleMessageFilterService(final StaleMessageFilterProperties properties, final ObjectProvider<DataSource> dataSourceProvider,
			final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		this.properties = properties;
		this.dataSourceProvider = dataSourceProvider;
		this.meterRegistryProvider = meterRegistryProvider;
		this.processingStartByEntryKey = new ConcurrentHashMap<>(properties.getInitialCapacity());
	}

	/**
	 * Constructor without metrics.
	 *
	 * @param properties         Stale message filter properties.
	 * @param dataSourceProvider Data source provider (may be null when used
	 *                               outside a Spring context).
	 */
	public StaleMessageFilterService(final StaleMessageFilterProperties properties, final ObjectProvider<DataSource> dataSourceProvider) {
		this(properties, dataSourceProvider, null);
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
	 * Gets the destinations this instance has seen keyed messages on.
	 *
	 * @return A copy of the keyed destinations.
	 */
	public Set<String> getKeyedDestinations() {
		return Set.copyOf(this.keyedDestinations);
	}

	/**
	 * Gets the meter registry, registering the records gauge when it is first
	 * resolved.
	 *
	 * @return The meter registry, or null when unavailable.
	 */
	private MeterRegistry getMeterRegistry() {
		if ((this.meterRegistry == null) && (this.meterRegistryProvider != null)) {
			final MeterRegistry resolvedMeterRegistry = this.meterRegistryProvider.getIfAvailable();
			if (resolvedMeterRegistry != null) {
				Gauge.builder(StaleMessageFilterService.RECORDS_METRIC, this.processingStartByEntryKey, Map::size).register(resolvedMeterRegistry);
				this.meterRegistry = resolvedMeterRegistry;
			}
		}
		return this.meterRegistry;
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
	 * Keeps the newer of two processing starts (the current one on a tie, so a
	 * local record pulled back from the shared store stays local).
	 */
	private static ProcessingStart newer(
			final ProcessingStart current,
			final ProcessingStart candidate) {
		return (candidate.timestamp() > current.timestamp() ? candidate : current);
	}

	/**
	 * Checks if a same-key processing started after the given message was posted
	 * (in which case the message is stale and can be dropped), and where that
	 * processing record came from. Also learns the destination as keyed (to be
	 * synchronized).
	 *
	 * @param  destination       Message destination.
	 * @param  messageKey        Stale filter key.
	 * @param  postedAtTimestamp When the message was posted (wall-clock epoch
	 *                               millis).
	 * @return                   Where the newer processing record came from, or
	 *                           null when there is none (with the clock skew
	 *                           margin).
	 */
	public Source getNewerProcessingSource(
			final String destination,
			final String messageKey,
			final long postedAtTimestamp) {
		Source source = null;
		if (this.isEnabled()) {
			this.keyedDestinations.add(destination);
			final ProcessingStart lastProcessingStart = this.processingStartByEntryKey.get(StaleMessageFilterService.composeEntryKey(destination, messageKey));
			if ((lastProcessingStart != null)
					&& ((postedAtTimestamp + this.properties.getClockSkewMargin().toMillis()) < lastProcessingStart.timestamp())) {
				source = (lastProcessingStart.pulled() ? Source.PULLED : Source.LOCAL);
			}
		}
		return source;
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
		return (this.getNewerProcessingSource(destination, messageKey, postedAtTimestamp) != null);
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
			this.keyedDestinations.add(destination);
			final String entryKey = StaleMessageFilterService.composeEntryKey(destination, messageKey);
			this.processingStartByEntryKey.merge(entryKey, new ProcessingStart(processingStartTimestamp, false), StaleMessageFilterService::newer);
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
				this.sharedStore = new StaleMessageFilterJdbcStore(dataSource, this.properties.getTableName(), this.properties.getQueryTimeout());
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
	 * Pushes pending local processing records to the shared store (records
	 * replaced by a newer pulled one are already there). Failed pushes are
	 * re-queued for the next synchronization.
	 *
	 * @return Pushed records, or {@link #SKIPPED_ROWS} when there were none.
	 */
	private int pushPendingRecords(
			final StaleMessageFilterJdbcStore store) {
		final Map<String, Long> pendingRecords = new HashMap<>();
		for (final String entryKey : this.entryKeysPendingPush) {
			this.entryKeysPendingPush.remove(entryKey);
			final ProcessingStart processingStart = this.processingStartByEntryKey.get(entryKey);
			if ((processingStart != null) && !processingStart.pulled()) {
				pendingRecords.put(entryKey, processingStart.timestamp());
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
		return (pendingRecords.isEmpty() ? StaleMessageFilterService.SKIPPED_ROWS : pendingRecords.size());
	}

	/**
	 * Pulls the processing records of the keyed destinations synchronized since
	 * the last pull. The watermark is re-read with an overlap of one
	 * synchronization interval so records committed late are not missed (merges
	 * are idempotent). A newly keyed destination restarts the pull from the
	 * beginning of the window, so its older records are not skipped.
	 *
	 * @return Pulled records.
	 */
	private int pullSharedRecords(
			final StaleMessageFilterJdbcStore store) {
		final List<String> destinations = List.copyOf(this.keyedDestinations);
		final long overlapMillis = this.properties.getSynchronizationInterval().toMillis();
		final long synchronizedAfter = (destinations.size() == this.pulledDestinationCount ? Math.max(0, this.pullWatermark - overlapMillis) : 0);
		final List<StaleMessageFilterJdbcStore.ProcessingRecord> pulledRecords = store.pull(synchronizedAfter,
				destinations.stream().map(destination -> destination + StaleMessageFilterService.ENTRY_KEY_SEPARATOR).toList());
		long newWatermark = this.pullWatermark;
		for (final StaleMessageFilterJdbcStore.ProcessingRecord pulledRecord : pulledRecords) {
			this.processingStartByEntryKey.merge(pulledRecord.messageKey(), new ProcessingStart(pulledRecord.processedAt(), true),
					StaleMessageFilterService::newer);
			newWatermark = Math.max(newWatermark, pulledRecord.synchronizedAt());
		}
		this.pullWatermark = newWatermark;
		this.pulledDestinationCount = destinations.size();
		return pulledRecords.size();
	}

	/**
	 * Deletes shared records older than the window. The cutoff comes from the
	 * application clock while "synchronized at" comes from the database clock:
	 * a small skew between the two only moves the cutoff by that much.
	 *
	 * @return Deleted records, or {@link #SKIPPED_ROWS} when another instance was
	 *         running the cleanup.
	 */
	private int deleteExpiredSharedRecords(
			final StaleMessageFilterJdbcStore store) {
		final long cutoff = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) - this.properties.getWindow().toMillis();
		final Integer deletedRows = store.deleteSynchronizedBefore(cutoff);
		return (deletedRows == null ? StaleMessageFilterService.SKIPPED_ROWS : deletedRows);
	}

	/**
	 * Removes local processing records older than the window.
	 */
	private void sweepLocalRecords() {
		final long cutoff = DateTimeHelper.toTimestamp(DateTimeHelper.getCurrentLocalDateTime()) - this.properties.getWindow().toMillis();
		this.processingStartByEntryKey.values().removeIf(processingStart -> processingStart.timestamp() < cutoff);
	}

	/**
	 * Runs a synchronization phase, timing it ({@value #SYNC_METRIC}) and counting
	 * its rows ({@value #SYNC_ROWS_METRIC}) when a meter registry is available.
	 *
	 * @param phase Phase (push, pull or delete).
	 * @param step  The phase, returning its row count or {@link #SKIPPED_ROWS}.
	 */
	private void runSynchronizationPhase(
			final String phase,
			final IntSupplier step) {
		final long startNanos = System.nanoTime();
		String outcome = "failure";
		try {
			final int rows = step.getAsInt();
			outcome = (rows == StaleMessageFilterService.SKIPPED_ROWS ? "skipped" : "success");
			final MeterRegistry actualMeterRegistry = this.getMeterRegistry();
			if ((actualMeterRegistry != null) && (rows > 0)) {
				Counter.builder(StaleMessageFilterService.SYNC_ROWS_METRIC).tag("phase", phase).register(actualMeterRegistry).increment(rows);
			}
		}
		finally {
			final MeterRegistry actualMeterRegistry = this.getMeterRegistry();
			if (actualMeterRegistry != null) {
				Timer.builder(StaleMessageFilterService.SYNC_METRIC).tag("phase", phase).tag("outcome", outcome).register(actualMeterRegistry)
						.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
			}
		}
	}

	/**
	 * Synchronizes processing records with the shared store: pushes local
	 * records, pulls records from other instances, and expires records older
	 * than the window. Until this instance sees a keyed message, every phase is
	 * skipped without touching the database. Failures are logged and never
	 * propagate (the filter fails open until the next synchronization).
	 */
	public void synchronize() {
		if (this.isEnabled()) {
			try {
				this.sweepLocalRecords();
				final StaleMessageFilterJdbcStore store = this.getSharedStore();
				if ((store != null) && this.keyedDestinations.isEmpty()) {
					this.runSynchronizationPhase("push", () -> StaleMessageFilterService.SKIPPED_ROWS);
					this.runSynchronizationPhase("pull", () -> StaleMessageFilterService.SKIPPED_ROWS);
					this.runSynchronizationPhase("delete", () -> StaleMessageFilterService.SKIPPED_ROWS);
				}
				else if (store != null) {
					this.ensureSchema(store);
					this.runSynchronizationPhase("push", () -> this.pushPendingRecords(store));
					this.runSynchronizationPhase("pull", () -> this.pullSharedRecords(store));
					this.runSynchronizationPhase("delete", () -> this.deleteExpiredSharedRecords(store));
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

	/**
	 * Starts the synchronization on its own single daemon thread (so it never
	 * delays other scheduled jobs), when the filter is enabled at startup.
	 */
	@PostConstruct
	public synchronized void start() {
		if (this.isEnabled() && (this.synchronizationScheduler == null)) {
			this.getMeterRegistry();
			this.synchronizationScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
				final Thread thread = new Thread(runnable, StaleMessageFilterService.SYNC_THREAD_NAME);
				thread.setDaemon(true);
				return thread;
			});
			final long intervalMillis = this.properties.getSynchronizationInterval().toMillis();
			this.synchronizationScheduler.scheduleWithFixedDelay(this::synchronize, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Stops the synchronization.
	 */
	@PreDestroy
	public synchronized void stop() {
		if (this.synchronizationScheduler != null) {
			this.synchronizationScheduler.shutdownNow();
			this.synchronizationScheduler = null;
		}
	}

	/**
	 * If the synchronization is running.
	 *
	 * @return If the synchronization is running.
	 */
	public synchronized boolean isRunning() {
		return (this.synchronizationScheduler != null);
	}

}
