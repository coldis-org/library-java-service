package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * A service with Redis but without commons-pool2 on its class path: the pool, on by default, stays off and the service
 * starts with the one shared connection.
 */
public class CentralCachePoolWithoutCommonsPoolTest {

	/**
	 * Tests that, without commons-pool2, the pool configuration stays off and the service starts.
	 */
	@Test
	@DisplayName("Without commons-pool2, the pool stays off and the Redis connection factory keeps one shared connection")
	public void testCentralCachePoolWithoutCommonsPool() {
		IsolatedCacheContext.withBootRedis().withClassLoader(new FilteredClassLoader("org.apache.commons.pool2")).run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertTrue(context.getBeansOfType(RedisConnectionPoolAutoConfiguration.class).isEmpty());
			Assertions.assertTrue(context.getBean(LettuceConnectionFactory.class).getShareNativeConnection());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertInstanceOf(RedisCacheManager.class, context.getBean(expiration.getCentralCacheManagerName()));
			}
		});
	}

}
