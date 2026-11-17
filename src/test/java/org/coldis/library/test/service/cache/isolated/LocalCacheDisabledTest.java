package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A service without Redis that turned local caching off.
 */
public class LocalCacheDisabledTest {

	/**
	 * Tests that the switch removes the local cache configuration and the cached methods are called through.
	 */
	@Test
	public void testLocalCacheDisabled() {
		IsolatedCacheContext.withoutRedis().withPropertyValues(LocalCacheAutoConfiguration.ENABLED_PROPERTY + "=false").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertTrue(context.getBeansOfType(LocalCacheAutoConfiguration.class).isEmpty());
			final CacheClient cacheClient = context.getBean(CacheClient.class);
			for (int call = 0; call < 3; call++) {
				Assertions.assertEquals(call + 1, cacheClient.getFromLocalCache("key"));
				Assertions.assertEquals(call + 1, cacheClient.getFromCentralCache("key"));
			}
		});
	}

}
