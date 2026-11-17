package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * A service without Redis that chose the local fallback for the central cache manager names, and a smaller maximum
 * size, which the local and the central fallback caches both take.
 */
public class CentralCacheLocalFallbackTest {

	/**
	 * Tests that a central cached method caches locally, in a cache manager apart from the local ones.
	 */
	@Test
	public void testCentralCacheLocalFallback() {
		IsolatedCacheContext.withoutRedis().withPropertyValues(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "=local",
				LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY + "=5").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertInstanceOf(CaffeineCacheManager.class, context.getBean("minutesExpirationCentralCacheManager"));
			Assertions.assertEquals(5, IsolatedCacheContext.getMaximumSize(context.getBean("minutesExpirationLocalCacheManager")));
			Assertions.assertEquals(5, IsolatedCacheContext.getMaximumSize(context.getBean("minutesExpirationCentralCacheManager")));
			Assertions.assertNotSame(context.getBean("minutesExpirationLocalCacheManager"), context.getBean("minutesExpirationCentralCacheManager"));
			final CacheClient cacheClient = context.getBean(CacheClient.class);
			for (int call = 0; call < 3; call++) {
				Assertions.assertEquals(1, cacheClient.getFromCentralCache("key"));
			}
			Assertions.assertEquals(2, cacheClient.getFromCentralCache("other key"));
			Assertions.assertEquals(2, cacheClient.getCentralCalls());
		});
	}

}
