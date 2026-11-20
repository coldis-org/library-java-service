package org.coldis.library.test.service.cache.isolated;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A service without a meter registry: local caching works and records stats (the default), and nothing is published.
 */
public class LocalCacheMetricsWithoutRegistryTest {

	/**
	 * Tests that local caches are created and cache without a meter registry.
	 */
	@Test
	@DisplayName("Without a meter registry, local caches record stats and cache, and publishing is skipped")
	public void testLocalCacheWithoutRegistry() {
		IsolatedCacheContext.withoutRedis().run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(0, context.getBeanNamesForType(MeterRegistry.class).length);
			final CacheClient cacheClient = context.getBean(CacheClient.class);
			Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
			Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
			Assertions.assertEquals(1, cacheClient.getLocalCalls());
			final CaffeineCache cache = (CaffeineCache) ((CaffeineCacheManager) context.getBean("minutesExpirationLocalCacheManager")).getCache("cacheClient-local");
			Assertions.assertTrue(cache.getNativeCache().policy().isRecordingStats());
			Assertions.assertEquals(1, cache.getNativeCache().stats().hitCount());
		});
	}

}
