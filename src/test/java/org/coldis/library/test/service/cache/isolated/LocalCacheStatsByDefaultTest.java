package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.LocalCacheStatsConfigurationTestSeam;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * A service that does not set the stats property: local caches record stats and the stats debug log is loaded.
 */
public class LocalCacheStatsByDefaultTest {

	/**
	 * Tests that, without the stats property, the stats configuration loads and the local caches record stats.
	 */
	@Test
	@DisplayName("Without the stats property, local caches record stats and the stats debug log is loaded")
	public void testLocalCacheStatsByDefault() {
		IsolatedCacheContext.withoutRedis().withUserConfiguration(LocalCacheStatsConfigurationTestSeam.getConfigurationClass()).run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(1, context.getBeanNamesForType(LocalCacheStatsConfigurationTestSeam.getConfigurationClass()).length);
			final CaffeineCacheManager cacheManager = (CaffeineCacheManager) context.getBean("minutesExpirationLocalCacheManager");
			Assertions.assertTrue(((CaffeineCache) cacheManager.getCache("stats")).getNativeCache().policy().isRecordingStats());
		});
	}

}
