package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.SingleFlightCacheLoader;
import org.coldis.library.service.cache.SingleFlightRedisCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

/**
 * A service with Redis that turns the central cross-instance lock on: the central caches take it, with the configured
 * wait timeout (and the lock timeout plus the wait timeout for the threads of the instance).
 */
public class CacheSingleFlightLockEnabledTest {

	/**
	 * Tests that, with the lock on, the central caches take the cross-instance lock.
	 */
	@Test
	@DisplayName("With the cross-instance lock on, the central caches take it")
	public void testCacheSingleFlightLockEnabled() {
		IsolatedCacheContext.withRedis()
				.withPropertyValues(SingleFlightCacheLoader.CENTRAL_LOCK_ENABLED_PROPERTY + "=true", SingleFlightCacheLoader.WAIT_TIMEOUT_PROPERTY + "=7s")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
						final SingleFlightRedisCache centralCache = Assertions.assertInstanceOf(SingleFlightRedisCache.class,
								context.getBean(expiration.getCentralCacheManagerName(), CacheManager.class).getCache("single-flight"));
						Assertions.assertTrue(centralCache.isCrossInstanceLockEnabled());
						Assertions.assertEquals(7, centralCache.getWaitTimeout().getSeconds());
						// The threads of the instance wait for its loading thread up to the lock timeout (60s by default) plus the wait timeout.
						Assertions.assertEquals(67, centralCache.getSingleFlightLoader().getWaitTimeout().getSeconds());
					}
				});
	}

}
