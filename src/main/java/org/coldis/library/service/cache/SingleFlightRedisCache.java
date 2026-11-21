package org.coldis.library.service.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

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
 * The loaded value is returned as {@link RedisCache} returns it: serialized and read back, and null values are stored
 * as {@link RedisCache} stores them. Without the lock, a load does not read Redis again before it runs (so single-flight
 * adds no Redis command): a thread that missed just before another thread's load ended may load the key once more.
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
	 * @param waitTimeout           How long a thread (or instance) waits for another one's load of the same key.
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
		this.singleFlightLoader = new SingleFlightCacheLoader(waitTimeout);
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
	 * Gets whether the cache takes the cross-instance lock.
	 *
	 * @return Whether the cache takes the cross-instance lock.
	 */
	public boolean isCrossInstanceLockEnabled() {
		return this.lockConnectionFactory != null;
	}

	/**
	 * Loads a key and puts it in Redis, as {@link RedisCache#get(Object, Callable)} does: the value is serialized once,
	 * written, and read back from the serialized bytes.
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @return             The store value.
	 */
	private Object loadAndPutValue(
			final Object key,
			final Callable<?> valueLoader) {
		final byte[] binaryValue = this.serializeCacheValue(this.toStoreValue(this.loadCacheValue(key, valueLoader)));
		this.getCacheWriter().put(this.getName(), this.serializeCacheKey(this.createCacheKey(key)), binaryValue,
				this.getCacheConfiguration().getTtlFunction().getTimeToLive(key, null));
		return this.deserializeCacheValue(binaryValue);
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
	 * Releases the cross-instance lock of a key, if this load still holds it.
	 *
	 * @param lockKey   Lock key.
	 * @param lockToken Token of this load.
	 */
	private void releaseLock(
			final byte[] lockKey,
			final byte[] lockToken) {
		try (RedisConnection connection = this.lockConnectionFactory.getConnection()) {
			connection.scriptingCommands().eval(SingleFlightRedisCache.RELEASE_LOCK_SCRIPT, ReturnType.INTEGER, 1, lockKey, lockToken);
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
	 * @return             The store value.
	 */
	private Object loadAndPutAcrossInstances(
			final Object key,
			final Callable<?> valueLoader) {
		final byte[] lockKey = this.serializeCacheKey(this.createCacheKey(key) + SingleFlightRedisCache.LOCK_KEY_SUFFIX);
		final byte[] lockToken = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
		final long deadline = System.nanoTime() + this.singleFlightLoader.getWaitTimeout().toNanos();
		Object storeValue = null;
		boolean isLoaded = false;
		while (!isLoaded && ((System.nanoTime() - deadline) < 0)) {
			if (this.acquireLock(lockKey, lockToken)) {
				try {
					storeValue = this.lookup(key);
					if (storeValue == null) {
						storeValue = this.loadAndPutValue(key, valueLoader);
					}
				}
				finally {
					this.releaseLock(lockKey, lockToken);
				}
				isLoaded = true;
			}
			else {
				storeValue = this.lookup(key);
				isLoaded = (storeValue != null);
				if (!isLoaded) {
					this.waitPollInterval(key, valueLoader);
				}
			}
		}
		if (!isLoaded) {
			SingleFlightRedisCache.LOGGER.debug("Waited {} for another instance to load a key of cache '{}', loading it.",
					this.singleFlightLoader.getWaitTimeout(), this.getName());
			storeValue = this.loadAndPutValue(key, valueLoader);
		}
		return storeValue;
	}

	/**
	 * Loads a key and puts it in Redis, under the cross-instance lock if it is on.
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @return             The store value.
	 */
	private Object loadAndPut(
			final Object key,
			final Callable<?> valueLoader) {
		return (this.lockConnectionFactory == null) ? this.loadAndPutValue(key, valueLoader) : this.loadAndPutAcrossInstances(key, valueLoader);
	}

	/**
	 * Reads the key; on a miss, loads it single-flight.
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
			storeValue = this.singleFlightLoader.load(key, valueLoader, () -> this.loadAndPut(key, valueLoader));
		}
		return (T) this.fromStoreValue(storeValue);
	}

}
