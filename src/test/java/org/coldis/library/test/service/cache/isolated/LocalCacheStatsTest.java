package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.coldis.library.service.cache.LocalCacheStatsConfigurationTestSeam;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * A service with local caching on (the default) that turned stats on.
 */
public class LocalCacheStatsTest {

	/**
	 * Tests that the stats configuration loads and the local caches record stats.
	 */
	@Test
	@DisplayName("With local caching on and stats on, the stats configuration loads and local caches record stats")
	public void testLocalCacheStats() {
		IsolatedCacheContext.withoutRedis().withUserConfiguration(LocalCacheStatsConfigurationTestSeam.getConfigurationClass())
				.withPropertyValues(LocalCacheAutoConfiguration.RECORD_STATS_PROPERTY + "=true").run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					Assertions.assertEquals(1, context.getBeanNamesForType(LocalCacheStatsConfigurationTestSeam.getConfigurationClass()).length);
					final CaffeineCacheManager cacheManager = (CaffeineCacheManager) context.getBean("minutesExpirationLocalCacheManager");
					Assertions.assertTrue(((CaffeineCache) cacheManager.getCache("stats")).getNativeCache().policy().isRecordingStats());
				});
	}

}
