package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.coldis.library.service.cache.LocalCacheStatsConfigurationTestSeam;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A service with a meter registry that turned local cache stats off: the caches record no stats, publish no meter and
 * the stats debug log is not loaded.
 */
public class LocalCacheStatsDisabledTest {

	/**
	 * Tests that, with stats off, no cache records stats or publishes meters, and the stats configuration is absent.
	 */
	@Test
	@DisplayName("With local cache stats off, caches record no stats, publish no meter, and the stats log is not loaded")
	public void testLocalCacheStatsDisabled() {
		IsolatedCacheContext.withoutRedis().withBean(MeterRegistry.class, SimpleMeterRegistry::new)
				.withUserConfiguration(LocalCacheStatsConfigurationTestSeam.getConfigurationClass())
				.withPropertyValues(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "=local", LocalCacheAutoConfiguration.RECORD_STATS_PROPERTY + "=false")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					Assertions.assertEquals(0, context.getBeanNamesForType(LocalCacheStatsConfigurationTestSeam.getConfigurationClass()).length);
					final CacheClient cacheClient = context.getBean(CacheClient.class);
					Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
					Assertions.assertEquals(1, cacheClient.getFromCentralCache("key"));
					Assertions.assertFalse(((CaffeineCache) ((CaffeineCacheManager) context.getBean("minutesExpirationLocalCacheManager")).getCache("cacheClient-local"))
							.getNativeCache().policy().isRecordingStats());
					Assertions.assertFalse(((CaffeineCache) ((CaffeineCacheManager) context.getBean("minutesExpirationCentralCacheManager")).getCache("cacheClient-central"))
							.getNativeCache().policy().isRecordingStats());
					Assertions.assertTrue(context.getBean(MeterRegistry.class).find("cache.gets").meters().isEmpty());
				});
	}

}
