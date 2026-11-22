package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * A service with Redis that turns the central cache connection pool off: every cache command goes through the one native
 * connection the Lettuce connection factory shares.
 */
public class CentralCachePoolDisabledTest {

	/**
	 * Tests that, with the pool turned off, the pool configuration stays off and the connection factory keeps sharing its
	 * native connection.
	 */
	@Test
	@DisplayName("With the pool turned off, the Redis connection factory keeps one shared connection")
	public void testCentralCachePoolDisabled() {
		IsolatedCacheContext.withBootRedis().withPropertyValues(RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY + "=false").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertTrue(context.getBeansOfType(RedisConnectionPoolAutoConfiguration.class).isEmpty());
			Assertions.assertFalse(context.containsBean("redisConnectionPoolCustomizer"));
			Assertions.assertFalse(context.containsBean("redisConnectionPoolSharingPostProcessor"));
			Assertions.assertTrue(context.getBean(LettuceConnectionFactory.class).getShareNativeConnection());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertInstanceOf(RedisCacheManager.class, context.getBean(expiration.getCentralCacheManagerName()));
			}
		});
	}

}
