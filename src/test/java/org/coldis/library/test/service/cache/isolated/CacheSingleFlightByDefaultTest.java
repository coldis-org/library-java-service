package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.SingleFlightCaffeineCache;
import org.coldis.library.service.cache.SingleFlightRedisCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

/**
 * A service with Redis that sets no single-flight property: the central and local caches load single-flight, without
 * the central cross-instance lock.
 */
public class CacheSingleFlightByDefaultTest {

	/**
	 * Tests that the central and local caches load single-flight by default, without the cross-instance lock.
	 */
	@Test
	@DisplayName("By default, the central and local caches load single-flight, without the cross-instance lock")
	public void testCacheSingleFlightByDefault() {
		IsolatedCacheContext.withRedis().run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				final SingleFlightRedisCache centralCache = Assertions.assertInstanceOf(SingleFlightRedisCache.class,
						context.getBean(expiration.getCentralCacheManagerName(), CacheManager.class).getCache("single-flight"));
				Assertions.assertFalse(centralCache.isCrossInstanceLockEnabled());
				Assertions.assertInstanceOf(SingleFlightCaffeineCache.class,
						context.getBean(expiration.getLocalCacheManagerName(), CacheManager.class).getCache("single-flight"));
			}
		});
	}

}
