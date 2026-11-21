package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.SingleFlightCacheLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.cache.RedisCache;

/**
 * A service with Redis that turns single-flight off: the central and local caches are the plain Spring ones, as before
 * single-flight.
 */
public class CacheSingleFlightDisabledTest {

	/**
	 * Tests that, with single-flight off, the central and local caches are the plain Spring ones.
	 */
	@Test
	@DisplayName("With single-flight off, the central and local caches are the plain Spring ones")
	public void testCacheSingleFlightDisabled() {
		IsolatedCacheContext.withRedis()
				.withPropertyValues(SingleFlightCacheLoader.CENTRAL_ENABLED_PROPERTY + "=false", SingleFlightCacheLoader.LOCAL_ENABLED_PROPERTY + "=false",
						SingleFlightCacheLoader.CENTRAL_LOCK_ENABLED_PROPERTY + "=true")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
						Assertions.assertEquals(RedisCache.class,
								context.getBean(expiration.getCentralCacheManagerName(), CacheManager.class).getCache("single-flight").getClass());
						Assertions.assertEquals(CaffeineCache.class,
								context.getBean(expiration.getLocalCacheManagerName(), CacheManager.class).getCache("single-flight").getClass());
					}
				});
	}

}
