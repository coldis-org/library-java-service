package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * A service that turns the central cache connection pool on but declares its own Lettuce connection factory without a
 * pool: the factory keeps sharing its native connection (unshared and without a pool, it would open a connection for
 * every command).
 */
public class CentralCachePoolOwnConnectionFactoryTest {

	/**
	 * Tests that a connection factory without a pool keeps sharing its native connection.
	 */
	@Test
	@DisplayName("With the pool on, a service's own connection factory without a pool keeps one shared connection")
	public void testCentralCachePoolOwnConnectionFactory() {
		IsolatedCacheContext.withRedis().withPropertyValues(RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertTrue(context.containsBean("redisConnectionPoolSharingPostProcessor"));
			Assertions.assertTrue(context.getBean(LettuceConnectionFactory.class).getShareNativeConnection());
		});
	}

}
