package org.coldis.library.test.service.cache.isolated;

import java.time.Duration;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import io.lettuce.core.api.StatefulConnection;

/**
 * A service with Redis that turns the central cache connection pool on: the Lettuce connection factory stops sharing
 * one native connection and pools them with the configured sizes.
 */
public class CentralCachePoolEnabledTest {

	/**
	 * Tests that the connection factory is pooled with the configured sizes and does not share a native connection.
	 */
	@Test
	@DisplayName("With the pool on, the Redis connection factory pools connections with the configured sizes")
	public void testCentralCachePoolEnabled() {
		IsolatedCacheContext.withBootRedis()
				.withPropertyValues(RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY + "=true", RedisConnectionPoolAutoConfiguration.MAX_TOTAL_PROPERTY + "=24",
						RedisConnectionPoolAutoConfiguration.MAX_IDLE_PROPERTY + "=12", RedisConnectionPoolAutoConfiguration.MIN_IDLE_PROPERTY + "=4",
						RedisConnectionPoolAutoConfiguration.MAX_WAIT_PROPERTY + "=250ms", "spring.data.redis.lettuce.pool.max-active=3")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					final LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
					Assertions.assertFalse(connectionFactory.getShareNativeConnection());
					final LettucePoolingClientConfiguration clientConfiguration = Assertions.assertInstanceOf(LettucePoolingClientConfiguration.class,
							connectionFactory.getClientConfiguration());
					final GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = clientConfiguration.getPoolConfig();
					Assertions.assertEquals(24, poolConfig.getMaxTotal());
					Assertions.assertEquals(12, poolConfig.getMaxIdle());
					Assertions.assertEquals(4, poolConfig.getMinIdle());
					Assertions.assertEquals(Duration.ofMillis(250), poolConfig.getMaxWaitDuration());
					Assertions.assertEquals(RedisConnectionPoolAutoConfiguration.EVICTION_RUNS_INTERVAL, poolConfig.getDurationBetweenEvictionRuns());
					for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
						Assertions.assertInstanceOf(RedisCacheManager.class, context.getBean(expiration.getCentralCacheManagerName()));
					}
				});
	}

}
