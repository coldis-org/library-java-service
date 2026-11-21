package org.coldis.library.service.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.types.Expiration;

/**
 * Redis cache whose synchronized loads ({@link #get(Object, Callable)}, used by {@code @Cacheable(sync = true)}) are
 * deduplicated per key within the JVM by a {@link SingleFlightCacheLoader}, without holding a Redis connection while a
 * load runs ({@link RedisCache} loads inside the cache writer's connection callback and does not deduplicate with the
 * non-locking writer). Every other method is {@link RedisCache}'s, unchanged, and a hit is the same single read.
 * Optionally (a lock connection factory given), the instances that miss the same key also take a short Redis lock per
 * key ({@code SET NX PX}), so only one instance loads it: the others read the key every poll interval (trying the
 * lock again, which also covers an expired lock) until it appears, and after the wait timeout load it by themselves.
 * The loaded value is returned as {@link RedisCache} returns it: serialized once, and read back from the serialized
 * bytes by each caller (the loading thread and every waiter get a copy of their own), and null values are stored as
 * {@link RedisCache} stores them. Without the lock, a load does not read Redis again before it runs (so single-flight
 * adds no Redis command): a thread that missed just before another thread's load ended may load the key once more. With
 * the lock, the threads of this instance wait for its loading thread up to the lock timeout plus the wait timeout (the
 * loading thread may wait the wait timeout for another instance, then load). An eviction or a clear through this
 * instance that runs during a synchronized load of this instance wins: the load gives its value to its callers but
 * does not keep it in Redis. An eviction through another instance is not seen by this instance's loads (as with
 * {@link RedisCache} and the non-locking writer).
 */
public class SingleFlightRedisCache extends RedisCache {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(SingleFlightRedisCache.class);

	/**
	 * Suffix of the cross-instance lock key (after the cache key, so clearing the cache clears its locks too).
	 */
	public static final String LOCK_KEY_SUFFIX = "~single-flight-lock";

	/**
	 * Releases the lock only if this load still holds it (it may have expired and been taken by another instance).
	 */
	private static final byte[] RELEASE_LOCK_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
			.getBytes(StandardCharsets.UTF_8);

	/**
	 * Single-flight loader.
	 */
	private final SingleFlightCacheLoader singleFlightLoader;

	/**
	 * How long an instance waits for another instance's load of the same key (with the cross-instance lock).
	 */
	private final Duration crossInstanceWaitTimeout;

	/**
	 * Connection factory for the cross-instance lock (null without the lock).
	 */
	private final RedisConnectionFactory lockConnectionFactory;

	/**
	 * Cross-instance lock timeout.
	 */
	private final Duration lockTimeout;

	/**
	 * Cross-instance lock poll interval.
	 */
	private final Duration lockPollInterval;

	/**
	 * Constructor.
	 *
	 * @param name                  Cache name.
	 * @param cacheWriter           Cache writer.
	 * @param cacheConfiguration    Cache configuration.
	 * @param waitTimeout           How long a thread (or instance) waits for another one's load of the same key (with
	 *                                  the cross-instance lock, the threads of this instance wait up to the lock
	 *                                  timeout plus this).
	 * @param lockConnectionFactory Connection factory for the cross-instance lock (null without the lock).
	 * @param lockTimeout           Cross-instance lock timeout.
	 * @param lockPollInterval      Cross-instance lock poll interval.
	 */
	public SingleFlightRedisCache(
			final String name,
			final RedisCacheWriter cacheWriter,
			final RedisCacheConfiguration cacheConfiguration,
			final Duration waitTimeout,
			final RedisConnectionFactory lockConnectionFactory,
			final Duration lockTimeout,
			final Duration lockPollInterval) {
		super(name, cacheWriter, cacheConfiguration);
		this.singleFlightLoader = new SingleFlightCacheLoader((lockConnectionFactory == null) ? waitTimeout : lockTimeout.plus(waitTimeout));
		this.crossInstanceWaitTimeout = waitTimeout;
		this.lockConnectionFactory = lockConnectionFactory;
		this.lockTimeout = lockTimeout;
		this.lockPollInterval = lockPollInterval;
	}

	/**
	 * Gets the single-flight loader.
	 *
	 * @return The single-flight loader.
	 */
	public SingleFlightCacheLoader getSingleFlightLoader() {
		return this.singleFlightLoader;
	}

	/**
	 * Gets how long this instance waits for another instance's load of a key (the cross-instance poll window). Threads
	 * of this instance wait for its own load as long as {@link SingleFlightCacheLoader#getWaitTimeout()}.
	 *
	 * @return The cross-instance wait timeout.
	 */
	public Duration getCrossInstanceWaitTimeout() {
		return this.crossInstanceWaitTimeout;
	}

	/**
	 * Gets whether the cache takes the cross-instance lock.
	 *
	 * @return Whether the cache takes the cross-instance lock.
	 */
	public boolean isCrossInstanceLockEnabled() {
		return this.lockConnectionFactory != null;
	}

	/**
	 * Reads the serialized value of a key, as {@link RedisCache#lookup(Object)} does, without deserializing it.
	 *
	 * @param  key Key.
	 * @return     The serialized store value (null if missing).
	 */
	private byte[] lookupBinary(
			final Object key) {
		final byte[] binaryKey = this.serializeCacheKey(this.createCacheKey(key));
		return this.getCacheConfiguration().isTimeToIdleEnabled()
				? this.getCacheWriter().get(this.getName(), binaryKey, this.getCacheConfiguration().getTtlFunction().getTimeToLive(key, null))
				: this.getCacheWriter().get(this.getName(), binaryKey);
	}

	/**
	 * Loads a key and puts it in Redis, as {@link RedisCache#get(Object, Callable)} does (the value is serialized once
	 * and written), unless the load was invalidated (an eviction that runs during the put removes the value again).
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @param  isInvalidated Whether the load was invalidated.
	 * @return             The serialized store value.
	 */
	private byte[] loadAndPutValue(
			final Object key,
			final Callable<?> valueLoader,
			final BooleanSupplier isInvalidated) {
		final Object loadedValue = this.loadCacheValue(key, valueLoader);
		final byte[] binaryValue = this.serializeCacheValue(this.toStoreValue(loadedValue));
		if (!isInvalidated.getAsBoolean()) {
			final byte[] binaryKey = this.serializeCacheKey(this.createCacheKey(key));
			// As RedisCache#put: the time to live is computed from the loaded value.
			this.getCacheWriter().put(this.getName(), binaryKey, binaryValue, this.getCacheConfiguration().getTtlFunction().getTimeToLive(key, loadedValue));
			if (isInvalidated.getAsBoolean()) {
				this.getCacheWriter().remove(this.getName(), binaryKey);
			}
		}
		return binaryValue;
	}

	/**
	 * Tries to take the cross-instance lock of a key.
	 *
	 * @param  lockKey   Lock key.
	 * @param  lockToken Token of this load.
	 * @return           Whether the lock was taken.
	 */
	private boolean acquireLock(
			final byte[] lockKey,
			final byte[] lockToken) {
		try (RedisConnection connection = this.lockConnectionFactory.getConnection()) {
			return Boolean.TRUE.equals(connection.stringCommands().set(lockKey, lockToken, Expiration.from(this.lockTimeout), SetOption.SET_IF_ABSENT));
		}
	}

	/**
	 * Releases the cross-instance lock of a key, if this load still holds it. A failed release does not change the
	 * outcome of the load (its value, or its own exception, stays the outcome): the lock expires by itself after the lock
	 * timeout, so the failure is logged and, when the load itself failed, kept as a suppressed exception of that failure.
	 *
	 * @param lockKey     Lock key.
	 * @param lockToken   Token of this load.
	 * @param loadFailure The load's own failure (null if the load succeeded).
	 */
	private void releaseLock(
			final byte[] lockKey,
			final byte[] lockToken,
			final Throwable loadFailure) {
		try (RedisConnection connection = this.lockConnectionFactory.getConnection()) {
			connection.scriptingCommands().eval(SingleFlightRedisCache.RELEASE_LOCK_SCRIPT, ReturnType.INTEGER, 1, lockKey, lockToken);
		}
		catch (final RuntimeException exception) {
			SingleFlightRedisCache.LOGGER.warn("Could not release the single-flight lock of a key of cache '{}'; it expires after {}.", this.getName(),
					this.lockTimeout, exception);
			if (loadFailure != null) {
				loadFailure.addSuppressed(exception);
			}
		}
	}

	/**
	 * Waits a poll interval.
	 *
	 * @param key         Key.
	 * @param valueLoader Value loader (for the exception thrown if the wait is interrupted).
	 */
	private void waitPollInterval(
			final Object key,
			final Callable<?> valueLoader) {
		try {
			Thread.sleep(this.lockPollInterval);
		}
		catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ValueRetrievalException(key, valueLoader, exception);
		}
	}

	/**
	 * Loads a key under the cross-instance lock: the instance that takes the lock loads it (unless another instance put
	 * it meanwhile); the others read it every poll interval, trying the lock again, and after the wait timeout load it
	 * by themselves.
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @param  isInvalidated Whether the load was invalidated.
	 * @return             The serialized store value.
	 */
	private byte[] loadAndPutAcrossInstances(
			final Object key,
			final Callable<?> valueLoader,
			final BooleanSupplier isInvalidated) {
		final byte[] lockKey = this.serializeCacheKey(this.createCacheKey(key) + SingleFlightRedisCache.LOCK_KEY_SUFFIX);
		final byte[] lockToken = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
		final long deadline = System.nanoTime() + this.crossInstanceWaitTimeout.toNanos();
		byte[] storeValue = null;
		boolean isLoaded = false;
		while (!isLoaded && ((System.nanoTime() - deadline) < 0)) {
			if (this.acquireLock(lockKey, lockToken)) {
				Throwable loadFailure = null;
				try {
					storeValue = this.lookupBinary(key);
					if (storeValue == null) {
						storeValue = this.loadAndPutValue(key, valueLoader, isInvalidated);
					}
				}
				catch (final RuntimeException | Error failure) {
					loadFailure = failure;
					throw failure;
				}
				finally {
					this.releaseLock(lockKey, lockToken, loadFailure);
				}
				isLoaded = true;
			}
			else {
				storeValue = this.lookupBinary(key);
				isLoaded = (storeValue != null);
				if (!isLoaded) {
					this.waitPollInterval(key, valueLoader);
				}
			}
		}
		if (!isLoaded) {
			SingleFlightRedisCache.LOGGER.debug("Waited {} for another instance to load a key of cache '{}', loading it.",
					this.crossInstanceWaitTimeout, this.getName());
			storeValue = this.loadAndPutValue(key, valueLoader, isInvalidated);
		}
		return storeValue;
	}

	/**
	 * Loads a key and puts it in Redis, under the cross-instance lock if it is on.
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @param  isInvalidated Whether the load was invalidated.
	 * @return             The serialized store value.
	 */
	private byte[] loadAndPut(
			final Object key,
			final Callable<?> valueLoader,
			final BooleanSupplier isInvalidated) {
		final byte[] storeValue;
		if (this.lockConnectionFactory == null) {
			// Read again before loading (as the lock path and the local cache do): a load that ended between this
			// thread's miss and its registration has already put the key, so it is not loaded a second time.
			final byte[] storedValue = this.lookupBinary(key);
			storeValue = (storedValue != null) ? storedValue : this.loadAndPutValue(key, valueLoader, isInvalidated);
		}
		else {
			storeValue = this.loadAndPutAcrossInstances(key, valueLoader, isInvalidated);
		}
		return storeValue;
	}

	/**
	 * Reads the key; on a miss, loads it single-flight (the serialized value is shared, each caller deserializes it).
	 *
	 * @see org.springframework.data.redis.cache.RedisCache#get(java.lang.Object, java.util.concurrent.Callable)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> T get(
			final Object key,
			final Callable<T> valueLoader) {
		Object storeValue = this.lookup(key);
		if (storeValue == null) {
			storeValue = this.deserializeCacheValue((byte[]) this.singleFlightLoader.load(key, valueLoader, isInvalidated -> this.loadAndPut(key, valueLoader, isInvalidated)));
		}
		return (T) this.fromStoreValue(storeValue);
	}

	/**
	 * Invalidates the key's load in flight, then evicts it ({@link #evictIfPresent(Object)} evicts through it).
	 *
	 * @see org.springframework.data.redis.cache.RedisCache#evict(java.lang.Object)
	 */
	@Override
	public void evict(
			final Object key) {
		this.singleFlightLoader.invalidate(key);
		super.evict(key);
	}

	/**
	 * Invalidates every load in flight, then clears the keys that match the pattern ({@link #clear()} and
	 * {@link #invalidate()} clear through it).
	 *
	 * @see org.springframework.data.redis.cache.RedisCache#clear(java.lang.String)
	 */
	@Override
	public void clear(
			final String keyPattern) {
		this.singleFlightLoader.invalidateAll();
		super.clear(keyPattern);
	}

}
