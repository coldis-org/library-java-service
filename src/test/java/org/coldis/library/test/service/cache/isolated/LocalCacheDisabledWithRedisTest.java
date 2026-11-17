package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * A service with Redis that turned local caching off. Without the fallback it did not start: the Redis configuration
 * enables caching, and the cache aspect found no default among the six central cache managers
 * (NoUniqueBeanDefinitionException).
 */
public class LocalCacheDisabledWithRedisTest {

	/**
	 * Tests that the service starts, keeps its Redis cache managers and calls a local cached method through.
	 */
	@Test
	public void testLocalCacheDisabledWithRedis() {
		IsolatedCacheContext.withRedis().withPropertyValues(LocalCacheAutoConfiguration.ENABLED_PROPERTY + "=false").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertInstanceOf(RedisCacheManager.class, context.getBean(expiration.getCentralCacheManagerName()));
				Assertions.assertInstanceOf(NoOpCacheManager.class, context.getBean(expiration.getLocalCacheManagerName()));
			}
			Assertions.assertSame(context.getBean("minutesExpirationLocalCacheManager"), context.getBean(CacheManager.class));
			final CacheClient cacheClient = context.getBean(CacheClient.class);
			for (int call = 0; call < 3; call++) {
				Assertions.assertEquals(call + 1, cacheClient.getFromLocalCache("key"));
			}
		});
	}

}
