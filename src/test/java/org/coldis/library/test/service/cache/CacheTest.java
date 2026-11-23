package org.coldis.library.test.service.cache;

import java.io.Serializable;

import org.coldis.library.service.cache.CacheManagerExpiration;
import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.coldis.library.service.cache.MeteredCaffeineCacheManager;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Test.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class CacheTest extends ContainerTestHelper {

	/**
	 * Expiration.
	 */
	@Value("${org.coldis.configuration.cache.millis-expiration}")
	private Long expiration;

	/**
	 * Cached service.
	 */
	@Autowired
	private CachedService cachedService;

	/**
	 * Application context.
	 */
	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * Meter registry of the context.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	public class TestData implements Serializable {

		/**
		 * Serial.
		 */
		private static final long serialVersionUID = 5374869661899804799L;

		private String testAttribute1;

		private Integer testAttribute2;

		public TestData() {
			super();
		}

		public TestData(final String testAttribute1, final Integer testAttribute2) {
			super();
			this.testAttribute1 = testAttribute1;
			this.testAttribute2 = testAttribute2;
		}

		public String getTestAttribute1() {
			return this.testAttribute1;
		}

		public void setTestAttribute1(
				final String testAttribute1) {
			this.testAttribute1 = testAttribute1;
		}

		public Integer getTestAttribute2() {
			return this.testAttribute2;
		}

		public void setTestAttribute2(
				final Integer testAttribute2) {
			this.testAttribute2 = testAttribute2;
		}
	}

	/**
	 * Tests the local cache.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testCentralCache() throws Exception {
		final TestData test = new TestData("test1", 1);
		final Integer attr1 = this.cachedService.getFromCentralCache1(test);
		Assertions.assertEquals(attr1, this.cachedService.getFromCentralCache1(test));
		Assertions.assertEquals(attr1, this.cachedService.getFromCentralCache1(test));
		Assertions.assertEquals(attr1, this.cachedService.getFromCentralCache1(test));
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromCentralCache1(test));
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromCentralCache1(test));
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromCentralCache1(test));

		final Integer attr2 = this.cachedService.getFromCentralCache2(test, test).getAttribute();
		Assertions.assertEquals(attr2, this.cachedService.getFromCentralCache2(test, test).getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromCentralCache2(test, test).getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromCentralCache2(test, test).getAttribute());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromCentralCache2(test, test).getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromCentralCache2(test, test).getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromCentralCache2(test, test).getAttribute());

		final Integer attr3 = this.cachedService.getFromCentralCache3().intValue();
		Assertions.assertEquals(attr3, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromCentralCache3().intValue());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromCentralCache3().intValue());

		final Integer attr4 = this.cachedService.getFromCentralCache4().getAttribute().intValue();
		Assertions.assertEquals(attr4, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromCentralCache4().getAttribute().intValue());

	}

	/**
	 * Tests the local cache.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testLocalCache() throws Exception {
		final Integer attr1 = this.cachedService.getFromLocalCache1();
		Assertions.assertEquals(attr1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1, this.cachedService.getFromLocalCache1());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromLocalCache1());

		final Integer attr2 = this.cachedService.getFromLocalCache2().getAttribute();
		Assertions.assertEquals(attr2, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromLocalCache2().getAttribute());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromLocalCache2().getAttribute());

		final Integer attr3 = this.cachedService.getFromLocalCache3().intValue();
		Assertions.assertEquals(attr3, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromLocalCache3().intValue());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromLocalCache3().intValue());

		final Integer attr4 = this.cachedService.getFromLocalCache4().getAttribute().intValue();
		Assertions.assertEquals(attr4, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Thread.sleep(this.expiration);
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromLocalCache4().getAttribute().intValue());

	}

	/**
	 * Tests that, with Redis and local caching on, the central cache managers are the Redis ones, the local ones are
	 * Caffeine, and the scanned fallback registrar registered no cache manager: the library declares exactly the twelve
	 * names the fallback knows.
	 */
	@Test
	@DisplayName("With Redis and local caching on, the cache managers are the Redis and Caffeine ones, no fallback")
	public void testCacheManagersWithRedis() {
		Assertions.assertEquals(1, this.applicationContext.getBeansOfType(CacheManagerFallbackRegistrar.class).size());
		Assertions.assertEquals(CacheManagerExpiration.values().length * 2, this.applicationContext.getBeansOfType(CacheManager.class).size());
		for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
			Assertions.assertInstanceOf(RedisCacheManager.class, this.applicationContext.getBean(expiration.getCentralCacheManagerName()));
			Assertions.assertInstanceOf(CaffeineCacheManager.class, this.applicationContext.getBean(expiration.getLocalCacheManagerName()));
		}
		Assertions.assertTrue(this.applicationContext.getBeansOfType(CacheManager.class).values().stream()
				.noneMatch(cacheManager -> cacheManager instanceof NoOpCacheManager));
	}

	/**
	 * Gets the gets of the metered local cache with a result.
	 *
	 * @param  result Result (hit or miss).
	 * @return        The gets with the result.
	 */
	private double getMeteredLocalCacheGets(
			final String result) {
		final FunctionCounter gets = this.meterRegistry.find("cache.gets").tag("cache", "cachedService-getFromMeteredLocalCache")
				.tag(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, "minutesExpirationLocalCacheManager").tag("result", result).functionCounter();
		Assertions.assertNotNull(gets);
		return gets.count();
	}

	/**
	 * Tests that a local cache created on first use publishes its hits, misses and size to the context's meter
	 * registry, tagged with the cache and cache manager names.
	 */
	@Test
	@DisplayName("A local cache publishes hits, misses and size tagged with the cache and cache manager names")
	public void testLocalCacheMetrics() {
		final String key = "metrics-" + System.nanoTime();
		final Integer firstValue = this.cachedService.getFromMeteredLocalCache(key);
		final double hitsBefore = this.getMeteredLocalCacheGets("hit");
		final double missesBefore = this.getMeteredLocalCacheGets("miss");
		Assertions.assertTrue(missesBefore >= 1);
		Assertions.assertEquals(firstValue, this.cachedService.getFromMeteredLocalCache(key));
		Assertions.assertEquals(firstValue, this.cachedService.getFromMeteredLocalCache(key));
		this.cachedService.getFromMeteredLocalCache(key + "-other");
		Assertions.assertEquals(hitsBefore + 2, this.getMeteredLocalCacheGets("hit"));
		Assertions.assertEquals(missesBefore + 1, this.getMeteredLocalCacheGets("miss"));
		Assertions.assertTrue(this.meterRegistry.get("cache.size").tag("cache", "cachedService-getFromMeteredLocalCache")
				.tag(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, "minutesExpirationLocalCacheManager").gauge().value() >= 2);
		Assertions.assertNotNull(this.meterRegistry.find("cache.evictions").tag("cache", "cachedService-getFromMeteredLocalCache")
				.tag(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, "minutesExpirationLocalCacheManager").functionCounter());
	}

}
