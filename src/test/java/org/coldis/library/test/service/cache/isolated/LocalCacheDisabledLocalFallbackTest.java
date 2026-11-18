package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.coldis.library.service.cache.LocalCacheStatsConfigurationTestSeam;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * A service without Redis that turned local caching off, chose the local fallback for the central names, turned stats
 * on and set a maximum size that is not positive.
 */
public class LocalCacheDisabledLocalFallbackTest {

	/**
	 * Tests that the service starts without the stats configuration, that the central cached method still caches
	 * (caching is enabled by the fallback configuration) and that the cache keeps the default maximum size.
	 */
	@Test
	@DisplayName("With local caching off, no Redis and the local fallback, central cached methods cache, bounded, without stats")
	public void testLocalCacheDisabledLocalFallback() {
		IsolatedCacheContext.withoutRedis()
				.withUserConfiguration(LocalCacheStatsConfigurationTestSeam.getConfigurationClass())
				.withPropertyValues(LocalCacheAutoConfiguration.ENABLED_PROPERTY + "=false", LocalCacheAutoConfiguration.RECORD_STATS_PROPERTY + "=true",
						LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY + "=0", CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "=local")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					Assertions.assertTrue(context.getBeansOfType(LocalCacheAutoConfiguration.class).isEmpty());
					Assertions.assertEquals(0,
							context.getBeanNamesForType(LocalCacheStatsConfigurationTestSeam.getConfigurationClass()).length);
					Assertions.assertInstanceOf(CaffeineCacheManager.class, context.getBean("minutesExpirationCentralCacheManager"));
					Assertions.assertEquals(LocalCacheAutoConfiguration.DEFAULT_MAXIMUM_SIZE,
							IsolatedCacheContext.getMaximumSize(context.getBean("minutesExpirationCentralCacheManager")));
					final CacheClient cacheClient = context.getBean(CacheClient.class);
					for (int call = 0; call < 3; call++) {
						Assertions.assertEquals(1, cacheClient.getFromCentralCache("key"));
						Assertions.assertEquals(call + 1, cacheClient.getFromLocalCache("key"));
					}
				});
	}

}
