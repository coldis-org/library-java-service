package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A service with a meter registry that turned the local cache metrics off: the caches still record stats (for the
 * debug log) and publish nothing, the local fallback ones included.
 */
public class LocalCacheMetricsDisabledTest {

	/**
	 * Tests that no cache meter is published with the metrics off.
	 */
	@Test
	@DisplayName("With the local cache metrics off, caches record stats and publish no meter")
	public void testLocalCacheMetricsDisabled() {
		IsolatedCacheContext.withoutRedis().withBean(MeterRegistry.class, SimpleMeterRegistry::new)
				.withPropertyValues(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "=local",
						LocalCacheAutoConfiguration.METRICS_ENABLED_PROPERTY + "=false")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					final CacheClient cacheClient = context.getBean(CacheClient.class);
					Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
					Assertions.assertEquals(1, cacheClient.getFromCentralCache("key"));
					Assertions.assertTrue(((CaffeineCache) ((CaffeineCacheManager) context.getBean("minutesExpirationLocalCacheManager")).getCache("cacheClient-local"))
							.getNativeCache().policy().isRecordingStats());
					Assertions.assertTrue(context.getBean(MeterRegistry.class).find("cache.gets").meters().isEmpty());
				});
	}

}
