package org.coldis.library.service.cache;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.cache.Cache;
import org.springframework.core.env.Environment;

/**
 * Deduplicates the concurrent loads of each key of one cache within the JVM ("single flight"), for synchronized cached
 * methods ({@code @Cacheable(sync = true)}, which call {@link Cache#get(Object, Callable)}). The first thread that
 * misses a key loads it; the threads that miss the same key meanwhile wait for that load (up to the wait timeout, then
 * load by themselves) and get its value or its exception. Threads that miss different keys never wait for each other,
 * and no lock is held while a load runs. The in-flight map holds only the keys being loaded right now: each entry is
 * removed when its load ends, successfully or not. A thread that asks again, while loading, for the key it is loading
 * (a recursive load) loads it directly instead of waiting for itself.
 */
public class SingleFlightCacheLoader {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(SingleFlightCacheLoader.class);

	/**
	 * Property that turns the single-flight loads of the local (Caffeine) caches on (the default) or off. It also
	 * applies to the local caches the central fallback creates under the central cache manager names.
	 */
	public static final String LOCAL_ENABLED_PROPERTY = "org.coldis.configuration.cache.local.single-flight.enabled";

	/**
	 * Property that turns the single-flight loads of the central (Redis) caches on (the default) or off.
	 */
	public static final String CENTRAL_ENABLED_PROPERTY = "org.coldis.configuration.cache.central.single-flight.enabled";

	/**
	 * Default of {@value #LOCAL_ENABLED_PROPERTY} and {@value #CENTRAL_ENABLED_PROPERTY}.
	 */
	public static final boolean ENABLED_DEFAULT = true;

	/**
	 * Property with how long a thread waits for the load of a key another thread (or, with the central lock, another
	 * instance) runs before it loads the key by itself.
	 */
	public static final String WAIT_TIMEOUT_PROPERTY = "org.coldis.configuration.cache.single-flight.wait-timeout";

	/**
	 * Default of {@value #WAIT_TIMEOUT_PROPERTY}.
	 */
	public static final String WAIT_TIMEOUT_DEFAULT = "60s";

	/**
	 * Property that turns the central (Redis) cross-instance lock on or off (the default): on, the instances that miss
	 * the same key of a central cache take a short Redis lock per key, so only one of them loads it.
	 */
	public static final String CENTRAL_LOCK_ENABLED_PROPERTY = "org.coldis.configuration.cache.central.single-flight.lock.enabled";

	/**
	 * Default of {@value #CENTRAL_LOCK_ENABLED_PROPERTY}.
	 */
	public static final boolean CENTRAL_LOCK_ENABLED_DEFAULT = false;

	/**
	 * Property with how long the central cross-instance lock of a key lasts at most (its Redis expiration): the longest
	 * a load is expected to take. An instance that dies while loading blocks the other instances for this long at most.
	 */
	public static final String CENTRAL_LOCK_TIMEOUT_PROPERTY = "org.coldis.configuration.cache.central.single-flight.lock.timeout";

	/**
	 * Default of {@value #CENTRAL_LOCK_TIMEOUT_PROPERTY}.
	 */
	public static final String CENTRAL_LOCK_TIMEOUT_DEFAULT = "60s";

	/**
	 * Property with how often an instance that did not get the central cross-instance lock of a key reads the key (and
	 * tries the lock again) while it waits.
	 */
	public static final String CENTRAL_LOCK_POLL_INTERVAL_PROPERTY = "org.coldis.configuration.cache.central.single-flight.lock.poll-interval";

	/**
	 * Default of {@value #CENTRAL_LOCK_POLL_INTERVAL_PROPERTY}.
	 */
	public static final String CENTRAL_LOCK_POLL_INTERVAL_DEFAULT = "100ms";

	/**
	 * A load in flight: the thread that runs it and the store value it gives.
	 *
	 * @param loadingThread Thread that runs the load.
	 * @param storeValue    Store value the load gives.
	 */
	private record InFlightLoad(
			Thread loadingThread,
			CompletableFuture<Object> storeValue
	) {}

	/**
	 * Loads in flight by key.
	 */
	private final ConcurrentHashMap<Object, InFlightLoad> inFlightLoads = new ConcurrentHashMap<>();

	/**
	 * How long a thread waits for another thread's load of the same key.
	 */
	private final Duration waitTimeout;

	/**
	 * Constructor.
	 *
	 * @param waitTimeout How long a thread waits for another thread's load of the same key.
	 */
	public SingleFlightCacheLoader(final Duration waitTimeout) {
		this.waitTimeout = waitTimeout;
	}

	/**
	 * Gets a duration property.
	 *
	 * @param  environment  Environment.
	 * @param  property     Property.
	 * @param  defaultValue Default value.
	 * @return              The duration.
	 */
	private static Duration getDuration(
			final Environment environment,
			final String property,
			final String defaultValue) {
		return DurationStyle.detectAndParse(environment.getProperty(property, defaultValue));
	}

	/**
	 * Gets whether the local caches load single-flight.
	 *
	 * @param  environment Environment.
	 * @return             Whether the local caches load single-flight.
	 */
	public static boolean isLocalEnabled(
			final Environment environment) {
		return environment.getProperty(SingleFlightCacheLoader.LOCAL_ENABLED_PROPERTY, Boolean.class, SingleFlightCacheLoader.ENABLED_DEFAULT);
	}

	/**
	 * Gets whether the central caches load single-flight.
	 *
	 * @param  environment Environment.
	 * @return             Whether the central caches load single-flight.
	 */
	public static boolean isCentralEnabled(
			final Environment environment) {
		return environment.getProperty(SingleFlightCacheLoader.CENTRAL_ENABLED_PROPERTY, Boolean.class, SingleFlightCacheLoader.ENABLED_DEFAULT);
	}

	/**
	 * Gets whether the central caches take the cross-instance lock.
	 *
	 * @param  environment Environment.
	 * @return             Whether the central caches take the cross-instance lock.
	 */
	public static boolean isCentralLockEnabled(
			final Environment environment) {
		return environment.getProperty(SingleFlightCacheLoader.CENTRAL_LOCK_ENABLED_PROPERTY, Boolean.class, SingleFlightCacheLoader.CENTRAL_LOCK_ENABLED_DEFAULT);
	}

	/**
	 * Gets the wait timeout.
	 *
	 * @param  environment Environment.
	 * @return             The wait timeout.
	 */
	public static Duration getWaitTimeout(
			final Environment environment) {
		return SingleFlightCacheLoader.getDuration(environment, SingleFlightCacheLoader.WAIT_TIMEOUT_PROPERTY, SingleFlightCacheLoader.WAIT_TIMEOUT_DEFAULT);
	}

	/**
	 * Gets the central cross-instance lock timeout.
	 *
	 * @param  environment Environment.
	 * @return             The central cross-instance lock timeout.
	 */
	public static Duration getCentralLockTimeout(
			final Environment environment) {
		return SingleFlightCacheLoader.getDuration(environment, SingleFlightCacheLoader.CENTRAL_LOCK_TIMEOUT_PROPERTY,
				SingleFlightCacheLoader.CENTRAL_LOCK_TIMEOUT_DEFAULT);
	}

	/**
	 * Gets the central cross-instance lock poll interval.
	 *
	 * @param  environment Environment.
	 * @return             The central cross-instance lock poll interval.
	 */
	public static Duration getCentralLockPollInterval(
			final Environment environment) {
		return SingleFlightCacheLoader.getDuration(environment, SingleFlightCacheLoader.CENTRAL_LOCK_POLL_INTERVAL_PROPERTY,
				SingleFlightCacheLoader.CENTRAL_LOCK_POLL_INTERVAL_DEFAULT);
	}

	/**
	 * Gets the wait timeout.
	 *
	 * @return The wait timeout.
	 */
	public Duration getWaitTimeout() {
		return this.waitTimeout;
	}

	/**
	 * Gets the number of loads in flight.
	 *
	 * @return The number of loads in flight.
	 */
	public int getInFlightLoadCount() {
		return this.inFlightLoads.size();
	}

	/**
	 * Runs the load of a key this thread registered, completes its in-flight entry with the outcome and removes the
	 * entry.
	 *
	 * @param  key             Key.
	 * @param  ownLoad         The in-flight entry of this thread's load.
	 * @param  storeValueLoader Loads the store value (and puts it in the cache).
	 * @return                 The store value.
	 */
	private Object runLoad(
			final Object key,
			final InFlightLoad ownLoad,
			final Supplier<Object> storeValueLoader) {
		final Object storeValue;
		try {
			storeValue = storeValueLoader.get();
			ownLoad.storeValue().complete(storeValue);
		}
		catch (final RuntimeException | Error exception) {
			ownLoad.storeValue().completeExceptionally(exception);
			throw exception;
		}
		finally {
			this.inFlightLoads.remove(key, ownLoad);
		}
		return storeValue;
	}

	/**
	 * Waits for another thread's load of a key. After the wait timeout, loads the key by itself.
	 *
	 * @param  key              Key.
	 * @param  valueLoader      Value loader (for the exception thrown if the wait is interrupted).
	 * @param  otherLoad        The other thread's load.
	 * @param  storeValueLoader Loads the store value (and puts it in the cache).
	 * @return                  The store value.
	 */
	private Object waitForLoad(
			final Object key,
			final Callable<?> valueLoader,
			final InFlightLoad otherLoad,
			final Supplier<Object> storeValueLoader) {
		Object storeValue;
		try {
			storeValue = otherLoad.storeValue().get(this.waitTimeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (final TimeoutException exception) {
			SingleFlightCacheLoader.LOGGER.debug("Waited {} for the load of a cache key, loading it.", this.waitTimeout);
			storeValue = storeValueLoader.get();
		}
		catch (final ExecutionException exception) {
			if (exception.getCause() instanceof final Error error) {
				throw error;
			}
			throw (exception.getCause() instanceof final RuntimeException cause) ? cause
					: new Cache.ValueRetrievalException(key, valueLoader, exception.getCause());
		}
		catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new Cache.ValueRetrievalException(key, valueLoader, exception);
		}
		return storeValue;
	}

	/**
	 * Loads a key single-flight: this thread loads it, or waits for the thread that is loading it.
	 *
	 * @param  key              Key (a missed one).
	 * @param  valueLoader      Value loader (for the exceptions thrown when a wait fails).
	 * @param  storeValueLoader Loads the store value and puts it in the cache; throws
	 *                              {@link Cache.ValueRetrievalException} if the value loader fails.
	 * @return                  The store value.
	 */
	public Object load(
			final Object key,
			final Callable<?> valueLoader,
			final Supplier<Object> storeValueLoader) {
		final InFlightLoad ownLoad = new InFlightLoad(Thread.currentThread(), new CompletableFuture<>());
		final InFlightLoad otherLoad = this.inFlightLoads.putIfAbsent(key, ownLoad);
		final Object storeValue;
		if (otherLoad == null) {
			storeValue = this.runLoad(key, ownLoad, storeValueLoader);
		}
		else if (otherLoad.loadingThread() == Thread.currentThread()) {
			storeValue = storeValueLoader.get();
		}
		else {
			storeValue = this.waitForLoad(key, valueLoader, otherLoad, storeValueLoader);
		}
		return storeValue;
	}

}
