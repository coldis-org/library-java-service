package org.coldis.library.test.service.cache.isolated;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.LocalCacheAutoConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * A service without Redis and without cache properties: local caching is on, each local cache is unbounded (no maximum
 * entries unless configured) and has the default expiration, and the central cache manager names do not cache. Before the fallback, the central call failed with NoSuchBeanDefinitionException ("No bean named
 * 'minutesExpirationCentralCacheManager' available"), thrown by the cache aspect when it resolved the manager on the
 * call; the context itself started.
 */
public class LocalCacheByDefaultTest {

	/**
	 * Tests that local caching works out of the box and a central cached method is called through.
	 */
	@Test
	@DisplayName("Without Redis or cache properties, local caching works, unbounded, and central cached methods are called through")
	public void testLocalCacheByDefault() {
		IsolatedCacheContext.withoutRedis().run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(1, context.getBeansOfType(LocalCacheAutoConfiguration.class).size());
			Assertions.assertInstanceOf(CaffeineCacheManager.class, context.getBean("minutesExpirationLocalCacheManager"));
			Assertions.assertInstanceOf(NoOpCacheManager.class, context.getBean("minutesExpirationCentralCacheManager"));
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				Assertions.assertTrue(IsolatedCacheContext.isUnbounded(context.getBean(expiration.getLocalCacheManagerName())));
			}
			Assertions.assertEquals(Duration.ofMillis(3100), IsolatedCacheContext.getExpiration(context.getBean("millisExpirationLocalCacheManager")));
			Assertions.assertEquals(Duration.ofSeconds(23), IsolatedCacheContext.getExpiration(context.getBean("secondsExpirationLocalCacheManager")));
			Assertions.assertEquals(Duration.ofMinutes(11), IsolatedCacheContext.getExpiration(context.getBean("minutesExpirationLocalCacheManager")));
			Assertions.assertEquals(Duration.ofHours(3), IsolatedCacheContext.getExpiration(context.getBean("hoursExpirationLocalCacheManager")));
			Assertions.assertEquals(Duration.ofDays(1), IsolatedCacheContext.getExpiration(context.getBean("dayExpirationLocalCacheManager")));
			Assertions.assertEquals(Duration.ofDays(5), IsolatedCacheContext.getExpiration(context.getBean("daysExpirationLocalCacheManager")));
			final CacheClient cacheClient = context.getBean(CacheClient.class);
			for (int call = 0; call < 3; call++) {
				Assertions.assertEquals(1, cacheClient.getFromLocalCache("key"));
				Assertions.assertEquals(call + 1, cacheClient.getFromCentralCache("key"));
			}
			Assertions.assertEquals(1, cacheClient.getLocalCalls());
			Assertions.assertEquals(3, cacheClient.getCentralCalls());
		});
	}

	/**
	 * Tests that the shipped service properties do not bound local caches. A size there applies to every service and
	 * thrashes caches whose working set is larger (2.0.308 shipped 10000 and saturated personal-lending's Redis).
	 *
	 * @throws IOException If the properties cannot be read.
	 */
	@Test
	@DisplayName("The shipped service properties leave local caches unbounded")
	public void testShippedPropertiesLeaveLocalCachesUnbounded() throws IOException {
		final Properties serviceProperties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"));
		Assertions.assertFalse(serviceProperties.containsKey(LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY));
	}

}
