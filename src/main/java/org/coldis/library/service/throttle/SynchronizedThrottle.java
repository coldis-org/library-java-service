package org.coldis.library.service.throttle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import org.coldis.library.service.throttle.repository.SynchronizedThrottleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Synchronized throttle: lets a key of a named throttle run at most once per
 * interval, across the instances that share a database.
 *
 * Decisions are answered from an in-memory map (no database access on the
 * calling thread). A run is acquired atomically per key, so concurrent callers
 * of the same key on one instance never both acquire it. Runs are periodically
 * synchronized with a shared database table, on a single daemon thread: local
 * runs are pushed, other instances' runs are pulled, and expired rows are
 * deleted (by one instance at a time). Until this instance acquires its first
 * run, it only pulls (from the start, so it knows other instances' live runs),
 * and only once the table exists: an instance that never uses the throttle
 * creates nothing. When the throttle stops, it pushes its pending runs one
 * last time.
 *
 * Guarantees and limits:
 * <ul>
 * <li>Exactly once per interval per key within an instance; across instances
 * the throttle is eventually consistent: another instance learns of a run
 * within about one synchronization interval (its push plus the other
 * instance's pull), and two instances may both acquire the same key within
 * that window.</li>
 * <li>A release after the run was pushed is local only: other instances keep
 * the run until it expires (see
 * {@link #release(SynchronizedThrottleReservation)}).</li>
 * <li>The throttle fails open: when the database is unavailable (or there is
 * no data source, or persistence is disabled) it keeps throttling locally, and
 * the synchronization failure is logged as a warning, never propagated.</li>
 * </ul>
 *
 * Because it fails open and is eventually consistent, this throttle must not
 * be the only control on authentication, verification or lookup-probing flows
 * (logins, codes, lookups by identifiers): those need a strict limit that holds
 * when the database is unavailable and across instances at once.
 *
 * Keys are stored as the throttle name followed by the SHA-256 (hex) of the
 * caller's key ({@link #getStoredThrottleKey(String, String)}), so the shared
 * table, pulls and database error messages never carry the raw key.
 *
 * Without a {@link SynchronizedThrottleRepository} bean (no data source, or
 * persistence disabled), the throttle is local only.
 *
 * Run times are wall-clock epoch millis ({@link #getCurrentWallClockTimestamp()}),
 * never the application clock ({@code DateTimeHelper}): runs are compared
 * across instances, and a simulated application clock would otherwise expire
 * or extend them. Hosts must be clock-synchronized well below the throttled
 * intervals. The shared table's "synchronized at" values (the pull watermark)
 * and the cleanup's "now" come from the database clock.
 */
@Component
@ConditionalOnProperty(
		name = SynchronizedThrottleProperties.ENABLED_PROPERTY,
		havingValue = "true",
		matchIfMissing = true
)
public class SynchronizedThrottle implements SmartLifecycle {

	/**
	 * Synchronization phase duration (tags: phase, outcome).
	 */
	public static final String SYNC_METRIC = "throttle.synchronized.sync";

	/**
	 * Rows pushed, pulled or deleted by the synchronization (tag: phase).
	 */
	public static final String SYNC_ROWS_METRIC = "throttle.synchronized.sync.rows";

	/**
	 * Runs held in memory.
	 */
	public static final String RECORDS_METRIC = "throttle.synchronized.records";

	/**
	 * Synchronization thread name.
	 */
	public static final String SYNC_THREAD_NAME = "synchronized-throttle-sync";

	/**
	 * Separator between throttle name and hashed key in stored throttle keys
	 * (not allowed in throttle names, so a stored key splits unambiguously).
	 */
	public static final String THROTTLE_KEY_SEPARATOR = "|";

	/**
	 * Lifecycle phase: low, so the throttle starts before and stops after the
	 * request and message consumers (web server, listener containers), and its
	 * final push includes their last runs.
	 */
	public static final int LIFECYCLE_PHASE = 0;

	/**
	 * Hash algorithm of stored keys.
	 */
	private static final String KEY_HASH_ALGORITHM = "SHA-256";

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(SynchronizedThrottle.class);

	/**
	 * Row count a synchronization phase returns when it did not run.
	 */
	private static final int SKIPPED_ROWS = -1;

	/**
	 * The current run of a key on this instance.
	 *
	 * @param lastRunAt    When the key last ran (wall-clock epoch millis).
	 * @param nextRunAfter When the key may run again (wall-clock epoch millis).
	 * @param reservation  The reservation that may release the run (null when the
	 *                         run was pulled or restored by a release).
	 * @param isPulled     If the run was pulled from the shared store (recorded by
	 *                         another instance, so not pushed back).
	 */
	record LocalRun(long lastRunAt, long nextRunAfter, SynchronizedThrottleReservation reservation, boolean isPulled) {}

	/**
	 * A run this instance released (pulls skip it, so a run pushed before its
	 * release does not come back to this instance).
	 *
	 * @param throttleKey Throttle key.
	 * @param lastRunAt   When the released run ran (wall-clock epoch millis).
	 */
	private record ReleasedRun(String throttleKey, long lastRunAt) {}

	/**
	 * Synchronized throttle properties.
	 */
	private final SynchronizedThrottleProperties properties;

	/**
	 * Repository provider (resolved lazily; may be null). Without a repository
	 * the throttle is local only.
	 */
	private final ObjectProvider<SynchronizedThrottleRepository> repositoryProvider;

	/**
	 * Meter registry provider (may be null).
	 */
	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	/**
	 * Current run by throttle key.
	 */
	private final ConcurrentHashMap<String, LocalRun> localRunByThrottleKey = new ConcurrentHashMap<>();

	/**
	 * When each released run may run again, by released run (kept until then).
	 */
	private final ConcurrentHashMap<ReleasedRun, Long> nextRunAfterByReleasedRun = new ConcurrentHashMap<>();

	/**
	 * Throttle keys with local runs not yet pushed to the shared store.
	 */
	private final Set<String> throttleKeysPendingPush = ConcurrentHashMap.newKeySet();

	/**
	 * If this instance has acquired any run (until then, the synchronization
	 * does not touch the database).
	 */
	private volatile boolean hasAcquiredAnyRun;

	/**
	 * Meter registry (resolved on first use).
	 */
	private volatile MeterRegistry meterRegistry;

	/**
	 * Synchronization scheduler (null when not started).
	 */
	private ScheduledExecutorService synchronizationScheduler;

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
	 * If the shared table was found to exist (checked, without creating it,
	 * until this instance acquires a run).
	 */
	private volatile boolean hasFoundTable;

	/**
	 * Constructor.
	 *
	 * @param properties            Synchronized throttle properties.
	 * @param repositoryProvider    Repository provider (may be null; without a
	 *                                  repository the throttle is local only).
	 * @param meterRegistryProvider Meter registry provider (may be null).
	 */
	@Autowired
	public SynchronizedThrottle(final SynchronizedThrottleProperties properties, final ObjectProvider<SynchronizedThrottleRepository> repositoryProvider,
			final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		this.properties = properties;
		this.repositoryProvider = repositoryProvider;
		this.meterRegistryProvider = meterRegistryProvider;
	}

	/**
	 * Constructor without metrics.
	 *
	 * @param properties         Synchronized throttle properties.
	 * @param repositoryProvider Repository provider (may be null; without a
	 *                               repository the throttle is local only).
	 */
	public SynchronizedThrottle(final SynchronizedThrottleProperties properties, final ObjectProvider<SynchronizedThrottleRepository> repositoryProvider) {
		this(properties, repositoryProvider, null);
	}

	/**
	 * The current wall-clock time (epoch millis), the clock runs are recorded
	 * with. It is deliberately not the application clock ({@code DateTimeHelper}):
	 * runs are compared across instances, and a simulated application clock would
	 * otherwise expire or extend them.
	 *
	 * @return The current wall-clock time (epoch millis).
	 */
	public static long getCurrentWallClockTimestamp() {
		return System.currentTimeMillis();
	}

	/**
	 * If the throttle is enabled (its synchronization runs).
	 *
	 * @return If the throttle is enabled.
	 */
	public boolean isEnabled() {
		return this.properties.getEnabled();
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
				Gauge.builder(SynchronizedThrottle.RECORDS_METRIC, this.localRunByThrottleKey, Map::size).register(resolvedMeterRegistry);
				this.meterRegistry = resolvedMeterRegistry;
			}
		}
		return this.meterRegistry;
	}

	/**
	 * Hashes a key (SHA-256, lowercase hex).
	 *
	 * @param  key Key.
	 * @return     The hashed key.
	 */
	private static String hashKey(
			final String key) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance(SynchronizedThrottle.KEY_HASH_ALGORITHM).digest(key.getBytes(StandardCharsets.UTF_8)));
		}
		catch (final NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Every Java platform supports " + SynchronizedThrottle.KEY_HASH_ALGORITHM + ".", exception);
		}
	}

	/**
	 * Gets the throttle key as stored (and held in memory) for a throttle name
	 * and key: the throttle name, the separator, and the SHA-256 (hex) of the
	 * key. Useful to find a key's row in the shared table.
	 *
	 * @param  throttleName Throttle name (without the separator).
	 * @param  key          Key.
	 * @return              The stored throttle key.
	 */
	public static String getStoredThrottleKey(
			final String throttleName,
			final String key) {
		return throttleName + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + SynchronizedThrottle.hashKey(key);
	}

	/**
	 * Gets when a key run now may run again: now plus the interval, saturated at
	 * the largest epoch millis (an interval too long to add never expires).
	 *
	 * @param  now      Now (wall-clock epoch millis).
	 * @param  interval Interval (at least 1 ms).
	 * @return          When the key may run again.
	 */
	private static long getNextRunAfter(
			final long now,
			final Duration interval) {
		final long intervalMillis = (interval.getSeconds() >= (Long.MAX_VALUE / 1000) ? Long.MAX_VALUE : interval.toMillis());
		return (intervalMillis > (Long.MAX_VALUE - now) ? Long.MAX_VALUE : now + intervalMillis);
	}

	/**
	 * Validates the arguments of an acquisition.
	 *
	 * @param throttleName Throttle name.
	 * @param key          Key.
	 * @param interval     Interval.
	 */
	private static void validate(
			final String throttleName,
			final String key,
			final Duration interval) {
		if ((throttleName == null) || throttleName.isEmpty() || throttleName.contains(SynchronizedThrottle.THROTTLE_KEY_SEPARATOR)) {
			throw new IllegalArgumentException(
					"The throttle name must be non-empty and must not contain '" + SynchronizedThrottle.THROTTLE_KEY_SEPARATOR + "': " + throttleName);
		}
		if (key == null) {
			throw new IllegalArgumentException("The throttle key must not be null.");
		}
		if ((interval == null) || (interval.compareTo(Duration.ofMillis(1)) < 0)) {
			throw new IllegalArgumentException("The throttle interval must be at least 1 ms: " + interval);
		}
	}

	/**
	 * Acquires a run of a key of a throttle: when the key never ran (as far as
	 * this instance knows) or its interval has passed, records the run (the key
	 * may run again after the interval) and returns it; otherwise returns null.
	 * Concurrent callers of the same key on this instance never both acquire it.
	 * The run is shared with other instances on the next synchronization.
	 *
	 * The key is hashed before it is stored ({@link #getStoredThrottleKey}),
	 * but throttle names are stored as they are, and keys stay in memory and in
	 * the reservation: keys should still not contain personal data (hash keys
	 * derived from it).
	 *
	 * @param  throttleName                Throttle name (non-empty, without
	 *                                         {@value #THROTTLE_KEY_SEPARATOR}).
	 * @param  key                         Key (within the throttle; not null).
	 * @param  interval                    Interval between runs of the key (at
	 *                                         least 1 ms; an interval too long to
	 *                                         add to now never expires).
	 * @return                             The run acquired, or null when the key
	 *                                     must wait.
	 * @throws IllegalArgumentException If the throttle name or key is invalid,
	 *                                      or the interval is null or below 1
	 *                                      ms.
	 */
	public SynchronizedThrottleReservation tryAcquire(
			final String throttleName,
			final String key,
			final Duration interval) {
		SynchronizedThrottle.validate(throttleName, key, interval);
		final String throttleKey = SynchronizedThrottle.getStoredThrottleKey(throttleName, key);
		final long now = SynchronizedThrottle.getCurrentWallClockTimestamp();
		final long nextRunAfter = SynchronizedThrottle.getNextRunAfter(now, interval);
		final AtomicReference<SynchronizedThrottleReservation> acquiredReservation = new AtomicReference<>();
		this.localRunByThrottleKey.compute(throttleKey, (currentThrottleKey, currentRun) -> {
			LocalRun newRun = currentRun;
			if ((currentRun == null) || (now >= currentRun.nextRunAfter())) {
				// One step of history: the previous run without its reservation (which
				// would chain every earlier run).
				final LocalRun previousRun = (currentRun == null ? null
						: new LocalRun(currentRun.lastRunAt(), currentRun.nextRunAfter(), null, currentRun.isPulled()));
				final SynchronizedThrottleReservation reservation = new SynchronizedThrottleReservation(throttleName, key, currentThrottleKey, now,
						nextRunAfter, previousRun);
				newRun = new LocalRun(now, nextRunAfter, reservation, false);
				acquiredReservation.set(reservation);
			}
			return newRun;
		});
		if (acquiredReservation.get() != null) {
			this.hasAcquiredAnyRun = true;
			this.throttleKeysPendingPush.add(throttleKey);
		}
		return acquiredReservation.get();
	}

	/**
	 * Releases a run that did not happen, giving the key back to the state it had
	 * on this instance before the run. A reservation releases only its own run,
	 * once: it does nothing when a later run of the key was acquired or pulled
	 * since, when the run expired and was swept, or when it was already released.
	 *
	 * The release is reliable across instances only before the run is pushed
	 * (the next synchronization): an unpushed run is simply never shared. Once
	 * pushed, the release is local only: this instance may run the key again,
	 * but other instances that pulled the run (or pull it later) keep refusing
	 * the key until the run expires.
	 *
	 * Only release a run whose action certainly did not reach anything outside
	 * the system (e.g. after its transaction rolled back), never after a failure
	 * that may have happened after the action (such as a timeout from an outside
	 * provider): the key would run again, repeating the action. Do not release
	 * runs of a throttle that limits security-sensitive attempts: those must
	 * count every attempt, whatever its outcome.
	 *
	 * @param  reservation The run acquired.
	 * @return             If the key was given back.
	 */
	public boolean release(
			final SynchronizedThrottleReservation reservation) {
		final AtomicBoolean isReleased = new AtomicBoolean();
		if (reservation != null) {
			this.localRunByThrottleKey.computeIfPresent(reservation.getThrottleKey(), (throttleKey, currentRun) -> {
				LocalRun newRun = currentRun;
				if (currentRun.reservation() == reservation) {
					this.nextRunAfterByReleasedRun.put(new ReleasedRun(throttleKey, currentRun.lastRunAt()), currentRun.nextRunAfter());
					final LocalRun previousRun = reservation.getPreviousRun();
					newRun = previousRun;
					isReleased.set(true);
				}
				return newRun;
			});
		}
		return isReleased.get();
	}

	/**
	 * Gets the repository, when persistence is enabled and one is available.
	 *
	 * @return The repository, or null when unavailable (local only).
	 */
	private SynchronizedThrottleRepository getRepository() {
		return ((this.properties.getPersistenceEnabled() && (this.repositoryProvider != null)) ? this.repositoryProvider.getIfAvailable() : null);
	}

	/**
	 * Ensures the shared store schema exists.
	 *
	 * @param repository Repository.
	 */
	private void ensureSchema(
			final SynchronizedThrottleRepository repository) {
		if (!this.schemaEnsured) {
			if (this.properties.getCreateSchema()) {
				repository.ensureSchema();
			}
			this.schemaEnsured = true;
		}
	}

	/**
	 * Pushes the pending local runs to the shared store (runs released or
	 * replaced by a later pulled run since are not pushed). Failed pushes are
	 * re-queued for the next synchronization.
	 *
	 * @param  repository Repository.
	 * @return       Pushed runs, or {@link #SKIPPED_ROWS} when there were none.
	 */
	private int pushPendingRuns(
			final SynchronizedThrottleRepository repository) {
		final Map<String, SynchronizedThrottleRepository.ThrottleRun> pendingRuns = new HashMap<>();
		for (final String throttleKey : this.throttleKeysPendingPush) {
			this.throttleKeysPendingPush.remove(throttleKey);
			final LocalRun localRun = this.localRunByThrottleKey.get(throttleKey);
			if ((localRun != null) && !localRun.isPulled()) {
				pendingRuns.put(throttleKey, new SynchronizedThrottleRepository.ThrottleRun(localRun.lastRunAt(), localRun.nextRunAfter()));
			}
		}
		if (!pendingRuns.isEmpty()) {
			try {
				repository.push(pendingRuns);
			}
			catch (final Exception exception) {
				this.throttleKeysPendingPush.addAll(pendingRuns.keySet());
				throw exception;
			}
		}
		return (pendingRuns.isEmpty() ? SynchronizedThrottle.SKIPPED_ROWS : pendingRuns.size());
	}

	/**
	 * Merges a pulled run into the local runs: it only replaces a local run that
	 * ran before it (never one with the same last run, so this instance's own
	 * runs pulled back stay local and releasable), and it is ignored when this
	 * instance released it.
	 *
	 * @param pulledRecord Pulled run.
	 */
	private void mergePulledRun(
			final SynchronizedThrottleRepository.ThrottleRecord pulledRecord) {
		final ReleasedRun releasedRun = new ReleasedRun(pulledRecord.throttleKey(), pulledRecord.lastRunAt());
		final LocalRun pulledRun = new LocalRun(pulledRecord.lastRunAt(), pulledRecord.nextRunAfter(), null, true);
		this.localRunByThrottleKey.compute(pulledRecord.throttleKey(), (throttleKey, currentRun) -> {
			LocalRun newRun = currentRun;
			if (!this.nextRunAfterByReleasedRun.containsKey(releasedRun) && ((currentRun == null) || (pulledRun.lastRunAt() > currentRun.lastRunAt()))) {
				newRun = pulledRun;
			}
			return newRun;
		});
	}

	/**
	 * Pulls the runs synchronized since the last pull, merging the ones that have
	 * not expired. The watermark is re-read with an overlap of one
	 * synchronization interval so rows committed late are not missed (merges are
	 * idempotent).
	 *
	 * @param  repository Repository.
	 * @return       Pulled runs.
	 */
	private int pullSharedRuns(
			final SynchronizedThrottleRepository repository) {
		final long overlapMillis = this.properties.getSynchronizationInterval().toMillis();
		final List<SynchronizedThrottleRepository.ThrottleRecord> pulledRecords = repository.pull(Math.max(0, this.pullWatermark - overlapMillis));
		final long now = SynchronizedThrottle.getCurrentWallClockTimestamp();
		long newWatermark = this.pullWatermark;
		for (final SynchronizedThrottleRepository.ThrottleRecord pulledRecord : pulledRecords) {
			if (pulledRecord.nextRunAfter() > now) {
				this.mergePulledRun(pulledRecord);
			}
			newWatermark = Math.max(newWatermark, pulledRecord.synchronizedAt());
		}
		this.pullWatermark = newWatermark;
		return pulledRecords.size();
	}

	/**
	 * Deletes the expired shared rows, from one instance at a time.
	 *
	 * @param  repository Repository.
	 * @return       Deleted rows, or {@link #SKIPPED_ROWS} when another instance
	 *               was running the cleanup.
	 */
	private int deleteExpiredSharedRuns(
			final SynchronizedThrottleRepository repository) {
		final Integer deletedRows = repository.deleteExpired();
		return (deletedRows == null ? SynchronizedThrottle.SKIPPED_ROWS : deletedRows);
	}

	/**
	 * Removes the local runs (and released run markers) whose key may already
	 * run again.
	 */
	private void sweepLocalRuns() {
		final long now = SynchronizedThrottle.getCurrentWallClockTimestamp();
		this.localRunByThrottleKey.values().removeIf(localRun -> localRun.nextRunAfter() < now);
		this.nextRunAfterByReleasedRun.values().removeIf(nextRunAfter -> nextRunAfter < now);
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
			outcome = (rows == SynchronizedThrottle.SKIPPED_ROWS ? "skipped" : "success");
			final MeterRegistry actualMeterRegistry = this.getMeterRegistry();
			if ((actualMeterRegistry != null) && (rows > 0)) {
				Counter.builder(SynchronizedThrottle.SYNC_ROWS_METRIC).tag("phase", phase).register(actualMeterRegistry).increment(rows);
			}
		}
		finally {
			final MeterRegistry actualMeterRegistry = this.getMeterRegistry();
			if (actualMeterRegistry != null) {
				Timer.builder(SynchronizedThrottle.SYNC_METRIC).tag("phase", phase).tag("outcome", outcome).register(actualMeterRegistry)
						.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
			}
		}
	}

	/**
	 * Logs a synchronization failure: at warn, only the phase and the failure
	 * class (database messages may carry bound values); the details at debug.
	 *
	 * @param phase   Phase that failed.
	 * @param failure Failure.
	 */
	private static void logSynchronizationFailure(
			final String phase,
			final Throwable failure) {
		SynchronizedThrottle.LOGGER.warn("Synchronized throttle synchronization failed in phase '" + phase + "' with " + failure.getClass().getName()
				+ " (the throttle stays local until the next synchronization).");
		SynchronizedThrottle.LOGGER.debug("Synchronized throttle synchronization failed in phase '" + phase + "'.", failure);
	}

	/**
	 * Synchronizes runs with the shared store: sweeps expired local runs, pushes
	 * local runs, pulls other instances' runs and deletes expired shared rows.
	 * Until this instance acquires a run, it only pulls, once the table exists
	 * (checked without creating it). Without a repository (persistence disabled or no
	 * data source) only the local sweep runs. Failures are logged and never
	 * propagate (the throttle keeps working locally until the next
	 * synchronization).
	 */
	public void synchronize() {
		if (this.isEnabled()) {
			String phase = "sweep";
			try {
				this.getMeterRegistry();
				this.sweepLocalRuns();
				final SynchronizedThrottleRepository repository = this.getRepository();
				if (repository == null) {
					this.throttleKeysPendingPush.clear();
				}
				else if (!this.hasAcquiredAnyRun) {
					phase = "schema";
					this.hasFoundTable = (this.hasFoundTable || repository.tableExists());
					final boolean shouldPull = this.hasFoundTable;
					this.runSynchronizationPhase("push", () -> SynchronizedThrottle.SKIPPED_ROWS);
					phase = "pull";
					this.runSynchronizationPhase(phase, () -> (shouldPull ? this.pullSharedRuns(repository) : SynchronizedThrottle.SKIPPED_ROWS));
					this.runSynchronizationPhase("delete", () -> SynchronizedThrottle.SKIPPED_ROWS);
				}
				else {
					phase = "schema";
					this.ensureSchema(repository);
					phase = "push";
					this.runSynchronizationPhase(phase, () -> this.pushPendingRuns(repository));
					phase = "pull";
					this.runSynchronizationPhase(phase, () -> this.pullSharedRuns(repository));
					phase = "delete";
					this.runSynchronizationPhase(phase, () -> this.deleteExpiredSharedRuns(repository));
				}
			}
			catch (final Exception exception) {
				SynchronizedThrottle.logSynchronizationFailure(phase, exception);
			}
		}
	}

	/**
	 * Runs a scheduled synchronization, logging anything it throws (even an
	 * error), so a failure never cancels the schedule silently.
	 */
	private void synchronizeOnSchedule() {
		try {
			this.synchronize();
		}
		catch (final Throwable throwable) {
			SynchronizedThrottle.logSynchronizationFailure("scheduled", throwable);
		}
	}

	/**
	 * Pushes the pending local runs one last time (best effort: bounded by the
	 * repository's query timeout, failures logged and never propagated).
	 */
	private void pushPendingRunsOnStop() {
		String phase = "schema";
		try {
			final SynchronizedThrottleRepository repository = this.getRepository();
			if ((repository != null) && this.hasAcquiredAnyRun && !this.throttleKeysPendingPush.isEmpty()) {
				this.ensureSchema(repository);
				phase = "push";
				this.runSynchronizationPhase(phase, () -> this.pushPendingRuns(repository));
			}
		}
		catch (final Exception exception) {
			SynchronizedThrottle.logSynchronizationFailure(phase, exception);
		}
	}

	/**
	 * Starts the synchronization on its own single daemon thread (so it never
	 * delays other scheduled jobs), when the throttle is enabled.
	 */
	@Override
	public synchronized void start() {
		if (this.isEnabled() && (this.synchronizationScheduler == null)) {
			this.getMeterRegistry();
			this.synchronizationScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
				final Thread thread = new Thread(runnable, SynchronizedThrottle.SYNC_THREAD_NAME);
				thread.setDaemon(true);
				return thread;
			});
			final long intervalMillis = this.properties.getSynchronizationInterval().toMillis();
			this.synchronizationScheduler.scheduleWithFixedDelay(this::synchronizeOnSchedule, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Waits for a running synchronization to finish (at most three query
	 * timeouts), then stops the scheduler.
	 *
	 * @param scheduler Scheduler.
	 */
	private void shutDownScheduler(
			final ScheduledExecutorService scheduler) {
		scheduler.shutdown();
		try {
			if (!scheduler.awaitTermination(this.properties.getQueryTimeout().multipliedBy(3).toMillis(), TimeUnit.MILLISECONDS)) {
				scheduler.shutdownNow();
			}
		}
		catch (final InterruptedException exception) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Stops the synchronization (letting a running one finish, for a bounded
	 * time), then pushes the pending local runs one last time, so other
	 * instances learn of this instance's last runs. Called by the application
	 * context when it closes (before any bean is destroyed, and after the
	 * request and message consumers stopped).
	 */
	@Override
	public void stop() {
		ScheduledExecutorService scheduler = null;
		synchronized (this) {
			scheduler = this.synchronizationScheduler;
			this.synchronizationScheduler = null;
		}
		if (scheduler != null) {
			this.shutDownScheduler(scheduler);
		}
		this.pushPendingRunsOnStop();
	}

	/**
	 * If the synchronization is running.
	 *
	 * @return If the synchronization is running.
	 */
	@Override
	public synchronized boolean isRunning() {
		return (this.synchronizationScheduler != null);
	}

	/**
	 * Gets the lifecycle phase ({@value #LIFECYCLE_PHASE}).
	 *
	 * @return The lifecycle phase.
	 */
	@Override
	public int getPhase() {
		return SynchronizedThrottle.LIFECYCLE_PHASE;
	}

}
