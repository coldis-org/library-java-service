package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.SingleFlightCacheLoader;
import org.coldis.library.service.cache.SingleFlightRedisCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.cache.RedisCache;

/**
 * A service with local single-flight off: the local caches are the plain Caffeine ones, the central caches stay
 * single-flight (each switch acts on its own tier only).
 */
public class CacheSingleFlightLocalDisabledTest {

	/**
	 * Tests that, with local single-flight off, only that tier goes back to the plain Spring caches.
	 */
	@Test
	@DisplayName("With local single-flight off, only that tier goes back to the plain Spring caches")
	public void testLocalDisabled() {
		IsolatedCacheContext.withRedis().withPropertyValues(SingleFlightCacheLoader.LOCAL_ENABLED_PROPERTY + "=false").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertEquals(SingleFlightRedisCache.class,
						context.getBean(expiration.getCentralCacheManagerName(), CacheManager.class).getCache("single-flight").getClass());
				Assertions.assertEquals(CaffeineCache.class,
						context.getBean(expiration.getLocalCacheManagerName(), CacheManager.class).getCache("single-flight").getClass());
			}
		});
	}

}
