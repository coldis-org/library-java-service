package org.coldis.library.test.service.cache.isolated;

import org.coldis.library.service.cache.RedisConnectionPoolAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;

/**
 * A service that turns the central cache connection pool on but turns Spring Boot's Lettuce pooling off: it does not
 * start, instead of silently keeping one shared connection.
 */
public class CentralCachePoolWithoutBootPoolTest {

	/**
	 * Tests that the service fails to start and names the pool property.
	 */
	@Test
	@DisplayName("With the pool on and Spring Boot's Lettuce pooling off, the service does not start")
	public void testCentralCachePoolWithoutBootPool() {
		IsolatedCacheContext.withBootRedis()
				.withPropertyValues(RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY + "=true", "spring.data.redis.lettuce.pool.enabled=false")
				.run(context -> {
					Assertions.assertNotNull(context.getStartupFailure());
					final Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
					Assertions.assertInstanceOf(IllegalStateException.class, rootCause);
					Assertions.assertTrue(rootCause.getMessage().contains(RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY));
				});
	}

}
