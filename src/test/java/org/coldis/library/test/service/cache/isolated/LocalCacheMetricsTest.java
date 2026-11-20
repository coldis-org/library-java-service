package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.coldis.library.service.cache.MeteredCaffeineCacheManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A service with a meter registry, without Redis, with the local central fallback and caches of one entry: the local
 * and the local fallback caches publish their hits, misses, evictions and size.
 */
public class LocalCacheMetricsTest {

	/**
	 * Gets a cache function counter.
	 *
	 * @param  meterRegistry    Meter registry.
	 * @param  name             Meter name.
	 * @param  cacheName        Cache name.
	 * @param  cacheManagerName Cache manager name.
	 * @param  result           Get result (null for meters without it).
	 * @return                  The count.
	 */
	private static double getCount(
			final MeterRegistry meterRegistry,
			final String name,
			final String cacheName,
			final String cacheManagerName,
			final String result) {
		return (result == null
				? meterRegistry.get(name).tag("cache", cacheName).tag(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, cacheManagerName).functionCounter()
				: meterRegistry.get(name).tag("cache", cacheName).tag(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, cacheManagerName).tag("result", result)
						.functionCounter()).count();
	}

	/**
	 * Tests the hit, miss, eviction and size meters of a local cache and of a local fallback cache.
	 */
	@Test
	@DisplayName("Local and local fallback caches publish hits, misses, evictions and size, tagged with the cache and cache manager names")
	public void testLocalCacheMetrics() {
		IsolatedCacheContext.withoutRedis().withBean(MeterRegistry.class, SimpleMeterRegistry::new)
				.withPropertyValues(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "=local", LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY + "=1")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					final MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
					final CacheClient cacheClient = context.getBean(CacheClient.class);
					Assertions.assertTrue(meterRegistry.find("cache.gets").meters().isEmpty());
					Assertions.assertEquals(1, cacheClient.getFromLocalCache("first"));
					Assertions.assertEquals(1, cacheClient.getFromLocalCache("first"));
					Assertions.assertEquals(2, cacheClient.getFromLocalCache("second"));
					((CaffeineCache) ((CaffeineCacheManager) context.getBean("minutesExpirationLocalCacheManager")).getCache("cacheClient-local")).getNativeCache()
							.cleanUp();
					Assertions.assertEquals(1,
							LocalCacheMetricsTest.getCount(meterRegistry, "cache.gets", "cacheClient-local", "minutesExpirationLocalCacheManager", "hit"));
					Assertions.assertEquals(2,
							LocalCacheMetricsTest.getCount(meterRegistry, "cache.gets", "cacheClient-local", "minutesExpirationLocalCacheManager", "miss"));
					Assertions.assertEquals(1,
							LocalCacheMetricsTest.getCount(meterRegistry, "cache.evictions", "cacheClient-local", "minutesExpirationLocalCacheManager", null));
					Assertions.assertEquals(1, meterRegistry.get("cache.size").tag("cache", "cacheClient-local")
							.tag(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, "minutesExpirationLocalCacheManager").gauge().value());
					Assertions.assertEquals(1, cacheClient.getFromCentralCache("first"));
					Assertions.assertEquals(1, cacheClient.getFromCentralCache("first"));
					Assertions.assertEquals(1,
							LocalCacheMetricsTest.getCount(meterRegistry, "cache.gets", "cacheClient-central", "minutesExpirationCentralCacheManager", "hit"));
					Assertions.assertEquals(1,
							LocalCacheMetricsTest.getCount(meterRegistry, "cache.gets", "cacheClient-central", "minutesExpirationCentralCacheManager", "miss"));
				});
	}

}
