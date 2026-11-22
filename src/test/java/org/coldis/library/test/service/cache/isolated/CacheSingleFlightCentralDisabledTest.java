package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.SingleFlightCacheLoader;
import org.coldis.library.service.cache.SingleFlightCaffeineCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.cache.RedisCache;

/**
 * A service with central single-flight off: the central caches are the plain Redis ones, the local caches stay
 * single-flight (each switch acts on its own tier only).
 */
public class CacheSingleFlightCentralDisabledTest {

	/**
	 * Tests that, with central single-flight off, only that tier goes back to the plain Spring caches.
	 */
	@Test
	@DisplayName("With central single-flight off, only that tier goes back to the plain Spring caches")
	public void testCentralDisabled() {
		IsolatedCacheContext.withRedis().withPropertyValues(SingleFlightCacheLoader.CENTRAL_ENABLED_PROPERTY + "=false").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertEquals(RedisCache.class,
						context.getBean(expiration.getCentralCacheManagerName(), CacheManager.class).getCache("single-flight").getClass());
				Assertions.assertEquals(SingleFlightCaffeineCache.class,
						context.getBean(expiration.getLocalCacheManagerName(), CacheManager.class).getCache("single-flight").getClass());
			}
		});
	}

}
