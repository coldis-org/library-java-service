package org.coldis.library.test.service.cache;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.coldis.library.service.cache.SingleFlightCaffeineCache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Single-flight loads of a local cache, without a Spring context: the wait timeout fallback, a recursive load of the
 * same key and null values.
 */
public class CacheSingleFlightLoaderTest {

	/**
	 * Creates a single-flight local cache.
	 *
	 * @param  waitTimeout     Wait timeout.
	 * @param  allowNullValues If null values are allowed.
	 * @return                 The cache.
	 */
	private static SingleFlightCaffeineCache createCache(
			final Duration waitTimeout,
			final boolean allowNullValues) {
		return new SingleFlightCaffeineCache("single-flight", Caffeine.newBuilder().build(), allowNullValues, waitTimeout);
	}

	/**
	 * Tests that a thread waiting for another thread's load loads by itself after the wait timeout.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A thread waiting for another thread's load loads by itself after the wait timeout")
	public void testWaitTimeoutFallback() throws Exception {
		final SingleFlightCaffeineCache cache = CacheSingleFlightLoaderTest.createCache(Duration.ofMillis(300), true);
		final AtomicInteger loads = new AtomicInteger();
		final CompletableFuture<Object> slowLoad = CompletableFuture.supplyAsync(() -> cache.get("key", () -> {
			loads.incrementAndGet();
			Thread.sleep(3000);
			return "slow";
		}));
		Thread.sleep(100);
		final long start = System.nanoTime();
		final Object value = cache.get("key", () -> {
			loads.incrementAndGet();
			return "fast";
		});
		final long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
		Assertions.assertEquals("fast", value);
		Assertions.assertTrue((elapsedMillis >= 250) && (elapsedMillis < 2000), "Waited " + elapsedMillis + "ms.");
		Assertions.assertEquals(2, loads.get());
		Assertions.assertEquals("slow", slowLoad.get(10, TimeUnit.SECONDS));
		Assertions.assertEquals(0, cache.getSingleFlightLoader().getInFlightLoadCount());
	}

	/**
	 * Tests that a load that asks for its own key loads it instead of waiting for itself.
	 */
	@Test
	@DisplayName("A load that asks for its own key loads it instead of waiting for itself")
	public void testRecursiveLoadOfTheSameKey() {
		final SingleFlightCaffeineCache cache = CacheSingleFlightLoaderTest.createCache(Duration.ofSeconds(30), true);
		final long start = System.nanoTime();
		final Object value = cache.get("key", () -> "outer-" + cache.get("key", () -> "inner"));
		Assertions.assertTrue(((System.nanoTime() - start) / 1_000_000) < 5000);
		Assertions.assertEquals("outer-inner", value);
		Assertions.assertEquals("outer-inner", cache.get("key").get());
		Assertions.assertEquals(0, cache.getSingleFlightLoader().getInFlightLoadCount());
	}

	/**
	 * Tests that null values are cached as the plain local cache caches them, and refused when not allowed.
	 */
	@Test
	@DisplayName("Null values are cached as the plain local cache caches them")
	public void testNullValues() {
		final SingleFlightCaffeineCache cache = CacheSingleFlightLoaderTest.createCache(Duration.ofSeconds(30), true);
		final AtomicInteger loads = new AtomicInteger();
		final Callable<Object> nullLoader = () -> {
			loads.incrementAndGet();
			return null;
		};
		Assertions.assertNull(cache.get("key", nullLoader));
		Assertions.assertNull(cache.get("key", nullLoader));
		Assertions.assertEquals(1, loads.get());
		Assertions.assertNotNull(cache.get("key"));
		final SingleFlightCaffeineCache cacheWithoutNulls = CacheSingleFlightLoaderTest.createCache(Duration.ofSeconds(30), false);
		Assertions.assertThrows(org.springframework.cache.Cache.ValueRetrievalException.class, () -> cacheWithoutNulls.get("key", nullLoader));
		Assertions.assertEquals(0, cacheWithoutNulls.getSingleFlightLoader().getInFlightLoadCount());
	}

}
