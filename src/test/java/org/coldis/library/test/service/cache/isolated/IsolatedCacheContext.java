package org.coldis.library.test.service.cache.isolated;

import java.time.Duration;

import org.coldis.library.service.cache.CacheHelper;
import org.coldis.library.service.cache.CacheManagerFallbackAutoConfiguration;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.coldis.library.service.cache.RedisCacheAutoConfiguration;
import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.coldis.library.service.serialization.JsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Small contexts with the library's cache configurations, for the cache set-ups the shared test context (local and
 * Redis caching on) does not have. The AspectJ cache aspect is one per JVM and keeps the cache managers of the last
 * context that configured it, so each test class here has ONE test method that builds ONE context, and runs in its own
 * JVM (the
 * {@code isolated-cache-test} execution in the pom).
 */
public final class IsolatedCacheContext {

	/**
	 * No instances.
	 */
	private IsolatedCacheContext() {
	}

	/**
	 * Context with the cache configurations a service gets from the library.
	 *
	 * @return Context runner.
	 */
	private static ApplicationContextRunner withCacheConfigurations() {
		return IsolatedCacheContext.withCacheConfigurationsButFallback().withUserConfiguration(CacheManagerFallbackAutoConfiguration.class);
	}

	/**
	 * Context with the cache configurations a service gets from the library, except the cache manager fallback.
	 *
	 * @return Context runner.
	 */
	private static ApplicationContextRunner withCacheConfigurationsButFallback() {
		return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
				.withPropertyValues("org.coldis.configuration.base-package=org.coldis")
				.withUserConfiguration(JsonMapperAutoConfiguration.class, LocalCacheAutoConfiguration.class, RedisCacheAutoConfiguration.class,
						RedisConnectionPoolAutoConfiguration.class)
				.withBean(CacheClient.class).withBean(CacheHelper.class);
	}

	/**
	 * Context of a service without Redis caching and without the cache manager fallback: what a service got before the
	 * fallback, once local caching is on.
	 *
	 * @return Context runner.
	 */
	public static ApplicationContextRunner withoutRedisNorFallback() {
		return IsolatedCacheContext.withCacheConfigurationsButFallback().withClassLoader(new FilteredClassLoader(RedisCacheManager.class));
	}

	/**
	 * Context of a service without Redis caching: spring-data-redis is not on its class path.
	 *
	 * @return Context runner.
	 */
	public static ApplicationContextRunner withoutRedis() {
		return IsolatedCacheContext.withCacheConfigurations().withClassLoader(new FilteredClassLoader(RedisCacheManager.class));
	}

	/**
	 * Gets the maximum entries of a cache of a Caffeine cache manager.
	 *
	 * @param  cacheManager Cache manager.
	 * @return              The maximum entries of the cache.
	 */
	public static long getMaximumSize(
			final Object cacheManager) {
		return ((CaffeineCache) ((CaffeineCacheManager) cacheManager).getCache("maximum-size")).getNativeCache().policy().eviction().orElseThrow().getMaximum();
	}

	/**
	 * Gets whether a cache of a Caffeine cache manager has no size bound (no size-based eviction).
	 *
	 * @param  cacheManager Cache manager.
	 * @return              Whether the cache is unbounded.
	 */
	public static boolean isUnbounded(
			final Object cacheManager) {
		return ((CaffeineCache) ((CaffeineCacheManager) cacheManager).getCache("unbounded")).getNativeCache().policy().eviction().isEmpty();
	}

	/**
	 * Gets the expiration after write of a cache of a Caffeine cache manager.
	 *
	 * @param  cacheManager Cache manager.
	 * @return              The expiration after write of the cache.
	 */
	public static Duration getExpiration(
			final Object cacheManager) {
		return ((CaffeineCache) ((CaffeineCacheManager) cacheManager).getCache("expiration")).getNativeCache().policy().expireAfterWrite().orElseThrow()
				.getExpiresAfter();
	}

	/**
	 * Context of a service with Redis caching. The connection factory never connects: no test here reads or writes a
	 * central cache.
	 *
	 * @return Context runner.
	 */
	public static ApplicationContextRunner withRedis() {
		return IsolatedCacheContext.withCacheConfigurations().withBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory("localhost", 1));
	}

	/**
	 * Context of a service with Redis caching and Spring Boot's Redis connection factory (as services get it). The
	 * connection factory never connects: no test here reads or writes a central cache.
	 *
	 * @return Context runner.
	 */
	public static ApplicationContextRunner withBootRedis() {
		return IsolatedCacheContext.withCacheConfigurations().withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
				.withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=1");
	}

}
