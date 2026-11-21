package org.coldis.library.service.cache;

import java.time.Duration;

import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis cache manager whose caches are {@link SingleFlightRedisCache}s. Otherwise as the one
 * {@code RedisCacheManager.builder(connectionFactory).cacheDefaults(configuration).build()} gives: caches created at
 * runtime, not transaction aware.
 */
public class SingleFlightRedisCacheManager extends RedisCacheManager {

	/**
	 * How long a thread (or instance) waits for another one's load of the same key.
	 */
	private final Duration waitTimeout;

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
	 * @param cacheWriter               Cache writer.
	 * @param defaultCacheConfiguration Default cache configuration.
	 * @param waitTimeout               How long a thread (or instance) waits for another one's load of the same key.
	 * @param lockConnectionFactory     Connection factory for the cross-instance lock (null without the lock).
	 * @param lockTimeout               Cross-instance lock timeout.
	 * @param lockPollInterval          Cross-instance lock poll interval.
	 */
	public SingleFlightRedisCacheManager(
			final RedisCacheWriter cacheWriter,
			final RedisCacheConfiguration defaultCacheConfiguration,
			final Duration waitTimeout,
			final RedisConnectionFactory lockConnectionFactory,
			final Duration lockTimeout,
			final Duration lockPollInterval) {
		super(cacheWriter, defaultCacheConfiguration);
		this.waitTimeout = waitTimeout;
		this.lockConnectionFactory = lockConnectionFactory;
		this.lockTimeout = lockTimeout;
		this.lockPollInterval = lockPollInterval;
	}

	/**
	 * Creates a single-flight cache.
	 *
	 * @see org.springframework.data.redis.cache.RedisCacheManager#createRedisCache(java.lang.String,
	 *      org.springframework.data.redis.cache.RedisCacheConfiguration)
	 */
	@Override
	protected RedisCache createRedisCache(
			final String name,
			final RedisCacheConfiguration cacheConfiguration) {
		return new SingleFlightRedisCache(name, this.getCacheWriter(), (cacheConfiguration == null) ? this.getDefaultCacheConfiguration() : cacheConfiguration,
				this.waitTimeout, this.lockConnectionFactory, this.lockTimeout, this.lockPollInterval);
	}

}
