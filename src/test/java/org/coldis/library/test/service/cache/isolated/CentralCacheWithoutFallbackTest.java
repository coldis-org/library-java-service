package org.coldis.library.test.service.cache.isolated;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/**
 * Why the cache manager fallback exists: a service without Redis, with local caching on and no fallback, starts, but
 * every call of a central cached method fails, before the method runs: the cache aspect resolves the cache manager by
 * name on the call. {@link LocalCacheByDefaultTest} is the same service with the fallback.
 */
public class CentralCacheWithoutFallbackTest {

	/**
	 * Tests that a central cached method fails on every call without the fallback.
	 */
	@Test
	public void testCentralCacheWithoutFallback() {
		IsolatedCacheContext.withoutRedisNorFallback().run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			final CacheClient cacheClient = context.getBean(CacheClient.class);
			for (int call = 0; call < 2; call++) {
				final NoSuchBeanDefinitionException exception = Assertions.assertThrows(NoSuchBeanDefinitionException.class,
						() -> cacheClient.getFromCentralCache("key"));
				Assertions.assertEquals("minutesExpirationCentralCacheManager", exception.getBeanName());
			}
			Assertions.assertEquals(0, cacheClient.getCentralCalls());
			Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
			Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
		});
	}

}
