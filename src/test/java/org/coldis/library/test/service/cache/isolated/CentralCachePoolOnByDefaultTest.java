package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * A service with Redis that does not set the pool property: the central cache connection pool is on, with the default
 * sizes.
 */
public class CentralCachePoolOnByDefaultTest {

	/**
	 * Tests that, without the pool property, the connection factory pools connections with the default sizes.
	 */
	@Test
	@DisplayName("Without the pool property, the Redis connection factory pools connections with the default sizes")
	public void testCentralCachePoolOnByDefault() {
		IsolatedCacheContext.withBootRedis().run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertFalse(context.getBeansOfType(RedisConnectionPoolAutoConfiguration.class).isEmpty());
			final LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
			Assertions.assertFalse(connectionFactory.getShareNativeConnection());
			final LettucePoolingClientConfiguration clientConfiguration = Assertions.assertInstanceOf(LettucePoolingClientConfiguration.class,
					connectionFactory.getClientConfiguration());
			Assertions.assertEquals(RedisConnectionPoolAutoConfiguration.DEFAULT_MAX_TOTAL, clientConfiguration.getPoolConfig().getMaxTotal());
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertInstanceOf(RedisCacheManager.class, context.getBean(expiration.getCentralCacheManagerName()));
			}
		});
	}

}
