package org.coldis.library.test.service.cache;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.coldis.library.service.cache.SingleFlightCaffeineCache;
import org.coldis.library.service.cache.SingleFlightRedisCache;
import org.coldis.library.service.cache.SingleFlightRedisCacheManager;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Single-flight loads of synchronized cached methods ({@code @Cacheable(sync = true)}) on the central (Redis) and the
 * local (Caffeine) cache managers, through the AspectJ cache aspect and a Redis container, plus the central
 * cross-instance lock (two cache managers on the same Redis), evictions during loads, the hit path structure (one
 * delegate read) and the hit path cost against the plain caches (logged only).
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class CacheSingleFlightTest extends ContainerTestHelper {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(CacheSingleFlightTest.class);

	/**
	 * Concurrent calls.
	 */
	private static final int CONCURRENT_CALLS = 20;

	/**
	 * Slow load duration.
	 */
	private static final long SLOW_LOAD_MILLIS = 1000;

	/**
	 * Service.
	 */
	@Autowired
	private SingleFlightCachedService cachedService;

	/**
	 * Central cache manager.
	 */
	@Autowired
	@Qualifier("minutesExpirationCentralCacheManager")
	private CacheManager centralCacheManager;

	/**
	 * Local cache manager.
	 */
	@Autowired
	@Qualifier("minutesExpirationLocalCacheManager")
	private CacheManager localCacheManager;

	/**
	 * Redis connection factory.
	 */
	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	/**
	 * Outcome of a call.
	 *
	 * @param value         Value.
	 * @param exception     Exception.
	 * @param elapsedMillis Elapsed time.
	 */
	private record CallOutcome(
			Object value,
			Throwable exception,
			long elapsedMillis
	) {}

	/**
	 * Makes calls concurrently, all started together.
	 *
	 * @param  callCount   Number of calls.
	 * @param  callFactory Gives the call of each index.
	 * @return             The outcomes, by index.
	 * @throws Exception   If the calls cannot be made.
	 */
	private List<CallOutcome> callConcurrently(
			final int callCount,
			final IntFunction<Callable<?>> callFactory) throws Exception {
		final ExecutorService executor = Executors.newFixedThreadPool(callCount);
		final CountDownLatch startLatch = new CountDownLatch(1);
		final List<Future<CallOutcome>> futures = new ArrayList<>();
		for (int callIndex = 0; callIndex < callCount; callIndex++) {
			final Callable<?> call = callFactory.apply(callIndex);
			futures.add(executor.submit(() -> {
				startLatch.await();
				final long start = System.nanoTime();
				CallOutcome outcome;
				try {
					outcome = new CallOutcome(call.call(), null, (System.nanoTime() - start) / 1_000_000);
				}
				catch (final Exception exception) {
					outcome = new CallOutcome(null, exception, (System.nanoTime() - start) / 1_000_000);
				}
				return outcome;
			}));
		}
		startLatch.countDown();
		final List<CallOutcome> outcomes = new ArrayList<>();
		for (final Future<CallOutcome> future : futures) {
			outcomes.add(future.get(2, TimeUnit.MINUTES));
		}
		executor.shutdownNow();
		return outcomes;
	}

	/**
	 * Gets the number of loads in flight of a cache.
	 *
	 * @param  cache Cache.
	 * @return       The number of loads in flight.
	 */
	private static int getInFlightLoadCount(
			final Cache cache) {
		return (cache instanceof final SingleFlightRedisCache redisCache) ? redisCache.getSingleFlightLoader().getInFlightLoadCount()
				: ((SingleFlightCaffeineCache) cache).getSingleFlightLoader().getInFlightLoadCount();
	}

	/**
	 * Tests that the library cache managers load single-flight by default, without the central cross-instance lock.
	 */
	@Test
	@DisplayName("The central and local cache managers load single-flight by default, without the cross-instance lock")
	public void testSingleFlightByDefault() {
		CacheSingleFlightTest.LOGGER.info("Redis connections pooled (native connection not shared): {}.",
				(this.redisConnectionFactory instanceof final LettuceConnectionFactory lettuceConnectionFactory)
						&& !lettuceConnectionFactory.getShareNativeConnection());
		final SingleFlightRedisCache centralCache = Assertions.assertInstanceOf(SingleFlightRedisCache.class,
				this.centralCacheManager.getCache(SingleFlightCachedService.CENTRAL_CACHE));
		Assertions.assertFalse(centralCache.isCrossInstanceLockEnabled());
		Assertions.assertInstanceOf(SingleFlightCaffeineCache.class, this.localCacheManager.getCache(SingleFlightCachedService.LOCAL_CACHE));
	}

	/**
	 * Tests that concurrent calls for the same key load it once and all get the value.
	 *
	 * @param  cachedCall Cached call (key, load duration).
	 * @param  cache      Cache.
	 * @throws Exception  If the test fails.
	 */
	private void testSameKeyLoadsOnce(
			final BiFunction<String, Long, Integer> cachedCall,
			final Cache cache) throws Exception {
		final String key = "same-" + UUID.randomUUID();
		final List<CallOutcome> outcomes = this.callConcurrently(CacheSingleFlightTest.CONCURRENT_CALLS,
				callIndex -> () -> cachedCall.apply(key, CacheSingleFlightTest.SLOW_LOAD_MILLIS));
		Assertions.assertEquals(1, SingleFlightCachedService.getLoads(key));
		for (final CallOutcome outcome : outcomes) {
			Assertions.assertNull(outcome.exception());
			Assertions.assertEquals(1, outcome.value());
		}
		Assertions.assertEquals(0, CacheSingleFlightTest.getInFlightLoadCount(cache));
		Assertions.assertEquals(1, cachedCall.apply(key, CacheSingleFlightTest.SLOW_LOAD_MILLIS));
		Assertions.assertEquals(1, SingleFlightCachedService.getLoads(key));
	}

	/**
	 * Tests that concurrent central calls for the same key load it once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: 20 concurrent calls for the same key load it once and all get the value")
	public void testCentralSameKeyLoadsOnce() throws Exception {
		this.testSameKeyLoadsOnce(this.cachedService::getFromCentralCache, this.centralCacheManager.getCache(SingleFlightCachedService.CENTRAL_CACHE));
	}

	/**
	 * Tests that concurrent local calls for the same key load it once.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Local: 20 concurrent calls for the same key load it once and all get the value")
	public void testLocalSameKeyLoadsOnce() throws Exception {
		this.testSameKeyLoadsOnce(this.cachedService::getFromLocalCache, this.localCacheManager.getCache(SingleFlightCachedService.LOCAL_CACHE));
	}

	/**
	 * Tests that concurrent calls for different keys load in parallel.
	 *
	 * @param  cachedCall Cached call (key, load duration).
	 * @throws Exception  If the test fails.
	 */
	private void testDistinctKeysLoadInParallel(
			final BiFunction<String, Long, Integer> cachedCall) throws Exception {
		final String keyPrefix = "distinct-" + UUID.randomUUID() + "-";
		final long start = System.nanoTime();
		final List<CallOutcome> outcomes = this.callConcurrently(CacheSingleFlightTest.CONCURRENT_CALLS,
				callIndex -> () -> cachedCall.apply(keyPrefix + callIndex, CacheSingleFlightTest.SLOW_LOAD_MILLIS));
		final long wallMillis = (System.nanoTime() - start) / 1_000_000;
		for (int callIndex = 0; callIndex < CacheSingleFlightTest.CONCURRENT_CALLS; callIndex++) {
			Assertions.assertNull(outcomes.get(callIndex).exception());
			Assertions.assertEquals(1, outcomes.get(callIndex).value());
			Assertions.assertEquals(1, SingleFlightCachedService.getLoads(keyPrefix + callIndex));
		}
		CacheSingleFlightTest.LOGGER.info("{} distinct keys loaded in {}ms (one load takes {}ms).", CacheSingleFlightTest.CONCURRENT_CALLS, wallMillis,
				CacheSingleFlightTest.SLOW_LOAD_MILLIS);
		// Loaded one after the other, the keys would take CONCURRENT_CALLS loads: half of that proves they ran in parallel.
		Assertions.assertTrue(wallMillis < ((CacheSingleFlightTest.CONCURRENT_CALLS * CacheSingleFlightTest.SLOW_LOAD_MILLIS) / 2),
				"Distinct keys took " + wallMillis + "ms.");
	}

	/**
	 * Tests that concurrent central calls for different keys load in parallel.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: 20 concurrent calls for different keys load in parallel")
	public void testCentralDistinctKeysLoadInParallel() throws Exception {
		this.testDistinctKeysLoadInParallel(this.cachedService::getFromCentralCache);
	}

	/**
	 * Tests that concurrent local calls for different keys load in parallel.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Local: 20 concurrent calls for different keys load in parallel")
	public void testLocalDistinctKeysLoadInParallel() throws Exception {
		this.testDistinctKeysLoadInParallel(this.cachedService::getFromLocalCache);
	}

	/**
	 * Gets whether every slow load of the unrelated-calls test has started (one load of the shared key and one of each
	 * key of its own).
	 *
	 * @param  keyPrefix Key prefix of the test.
	 * @return           Whether every slow load has started.
	 */
	private static boolean slowLoadsStarted(
			final String keyPrefix) {
		boolean isStarted = SingleFlightCachedService.getLoads(keyPrefix + "slow") == 1;
		for (int callIndex = 1; callIndex < CacheSingleFlightTest.CONCURRENT_CALLS; callIndex += 2) {
			isStarted = isStarted && (SingleFlightCachedService.getLoads(keyPrefix + "slow-" + callIndex) == 1);
		}
		return isStarted;
	}

	/**
	 * Tests that unrelated cache calls are not slowed while slow loads run.
	 *
	 * @param  cachedCall Cached call (key, load duration).
	 * @param  plainCall  Plain cached call (key) on another cache.
	 * @throws Exception  If the test fails.
	 */
	private void testUnrelatedCallsNotSlowed(
			final BiFunction<String, Long, Integer> cachedCall,
			final Function<String, Integer> plainCall) throws Exception {
		final String keyPrefix = "unrelated-" + UUID.randomUUID() + "-";
		final long slowLoadMillis = 3 * CacheSingleFlightTest.SLOW_LOAD_MILLIS;
		cachedCall.apply(keyPrefix + "hit", 0L);
		final ExecutorService slowLoadsExecutor = Executors.newFixedThreadPool(CacheSingleFlightTest.CONCURRENT_CALLS);
		final List<Future<Integer>> slowLoads = new ArrayList<>();
		for (int callIndex = 0; callIndex < CacheSingleFlightTest.CONCURRENT_CALLS; callIndex++) {
			// Half the slow loads on one key, half on keys of their own.
			final String key = keyPrefix + ((callIndex % 2) == 0 ? "slow" : "slow-" + callIndex);
			slowLoads.add(slowLoadsExecutor.submit(() -> cachedCall.apply(key, slowLoadMillis)));
		}
		// Every slow load is in flight (its load started) before the timed calls: no ordering by sleep.
		final long slowLoadsDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while ((!CacheSingleFlightTest.slowLoadsStarted(keyPrefix)) && ((System.nanoTime() - slowLoadsDeadline) < 0)) {
			Thread.sleep(10);
		}
		Assertions.assertTrue(CacheSingleFlightTest.slowLoadsStarted(keyPrefix), "The slow loads did not start.");
		final long hitStart = System.nanoTime();
		Assertions.assertEquals(1, cachedCall.apply(keyPrefix + "hit", 0L));
		final long hitMillis = (System.nanoTime() - hitStart) / 1_000_000;
		final long plainStart = System.nanoTime();
		Assertions.assertEquals((keyPrefix + "plain").length(), plainCall.apply(keyPrefix + "plain"));
		final long plainMillis = (System.nanoTime() - plainStart) / 1_000_000;
		final long newKeyStart = System.nanoTime();
		Assertions.assertEquals(1, cachedCall.apply(keyPrefix + "new", 0L));
		final long newKeyMillis = (System.nanoTime() - newKeyStart) / 1_000_000;
		for (final Future<Integer> slowLoad : slowLoads) {
			slowLoad.get(1, TimeUnit.MINUTES);
		}
		slowLoadsExecutor.shutdownNow();
		CacheSingleFlightTest.LOGGER.info("During slow loads: synchronized hit {}ms, plain cached call {}ms, synchronized load of a new key {}ms.", hitMillis,
				plainMillis, newKeyMillis);
		// A call blocked by the slow loads would take most of a slow load: half of one proves it was not.
		Assertions.assertTrue(hitMillis < (slowLoadMillis / 2), "Synchronized hit took " + hitMillis + "ms.");
		Assertions.assertTrue(plainMillis < (slowLoadMillis / 2), "Plain cached call took " + plainMillis + "ms.");
		Assertions.assertTrue(newKeyMillis < (slowLoadMillis / 2), "Synchronized load of a new key took " + newKeyMillis + "ms.");
	}

	/**
	 * Tests that unrelated central cache calls are not slowed while slow loads run.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: unrelated cache calls are not slowed while slow loads run")
	public void testCentralUnrelatedCallsNotSlowed() throws Exception {
		this.testUnrelatedCallsNotSlowed(this.cachedService::getFromCentralCache, this.cachedService::getPlainFromCentralCache);
	}

	/**
	 * Tests that unrelated local cache calls are not slowed while slow loads run.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Local: unrelated cache calls are not slowed while slow loads run")
	public void testLocalUnrelatedCallsNotSlowed() throws Exception {
		this.testUnrelatedCallsNotSlowed(this.cachedService::getFromLocalCache, this.cachedService::getPlainFromLocalCache);
	}

	/**
	 * Tests that a load exception reaches every waiter and the next call loads again.
	 *
	 * @param  cachedCall Cached call (key, load duration).
	 * @param  cache      Cache.
	 * @throws Exception  If the test fails.
	 */
	private void testLoadExceptionReachesAllWaiters(
			final BiFunction<String, Long, Integer> cachedCall,
			final Cache cache) throws Exception {
		final String key = "failing-" + UUID.randomUUID();
		SingleFlightCachedService.FAILING_KEYS.add(key);
		final List<CallOutcome> outcomes = this.callConcurrently(CacheSingleFlightTest.CONCURRENT_CALLS,
				callIndex -> () -> cachedCall.apply(key, CacheSingleFlightTest.SLOW_LOAD_MILLIS / 2));
		Assertions.assertEquals(1, SingleFlightCachedService.getLoads(key));
		for (final CallOutcome outcome : outcomes) {
			final IllegalStateException exception = Assertions.assertInstanceOf(IllegalStateException.class, outcome.exception());
			Assertions.assertEquals("Load of '" + key + "' failed.", exception.getMessage());
		}
		Assertions.assertEquals(0, CacheSingleFlightTest.getInFlightLoadCount(cache));
		SingleFlightCachedService.FAILING_KEYS.remove(key);
		Assertions.assertEquals(2, cachedCall.apply(key, 0L));
		Assertions.assertEquals(2, cachedCall.apply(key, 0L));
		Assertions.assertEquals(2, SingleFlightCachedService.getLoads(key));
	}

	/**
	 * Tests that a central load exception reaches every waiter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: a load exception reaches every waiter and the next call loads again")
	public void testCentralLoadExceptionReachesAllWaiters() throws Exception {
		this.testLoadExceptionReachesAllWaiters(this.cachedService::getFromCentralCache,
				this.centralCacheManager.getCache(SingleFlightCachedService.CENTRAL_CACHE));
	}

	/**
	 * Tests that a local load exception reaches every waiter.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Local: a load exception reaches every waiter and the next call loads again")
	public void testLocalLoadExceptionReachesAllWaiters() throws Exception {
		this.testLoadExceptionReachesAllWaiters(this.cachedService::getFromLocalCache, this.localCacheManager.getCache(SingleFlightCachedService.LOCAL_CACHE));
	}

	/**
	 * Tests that local loads that load other keys of the same cache neither deadlock nor fail.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Local: synchronized loads that load other keys of the same cache neither deadlock nor fail")
	public void testLocalNestedLoads() throws Exception {
		final String keyPrefix = "nested-" + UUID.randomUUID() + "-";
		final int nestedCalls = 64;
		final List<CallOutcome> outcomes = this.callConcurrently(nestedCalls, callIndex -> () -> this.cachedService.getNestedFromLocalCache(keyPrefix + callIndex));
		for (int callIndex = 0; callIndex < nestedCalls; callIndex++) {
			Assertions.assertNull(outcomes.get(callIndex).exception());
			Assertions.assertEquals(1, outcomes.get(callIndex).value());
			Assertions.assertEquals(1, SingleFlightCachedService.getLoads(keyPrefix + callIndex));
			Assertions.assertEquals(1, SingleFlightCachedService.getLoads(keyPrefix + callIndex + "-inner"));
		}
	}

	/**
	 * Creates a central cache manager on the test Redis.
	 *
	 * @param  waitTimeout  Wait timeout.
	 * @param  isLockOn     If the cross-instance lock is on.
	 * @param  lockTimeout  Lock timeout.
	 * @return              The cache manager.
	 */
	private SingleFlightRedisCacheManager createCentralCacheManager(
			final Duration waitTimeout,
			final boolean isLockOn,
			final Duration lockTimeout) {
		return new SingleFlightRedisCacheManager(RedisCacheWriter.nonLockingRedisCacheWriter(this.redisConnectionFactory),
				RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)), waitTimeout, isLockOn ? this.redisConnectionFactory : null,
				lockTimeout, Duration.ofMillis(50));
	}

	/**
	 * Makes concurrent synchronized loads of one key on two central cache managers (instances) on the same Redis, and
	 * checks every caller's value (the load number): with the lock, every caller gets the one load's; without it, the
	 * callers of each instance get their instance's load.
	 *
	 * @param  isLockOn  If the cross-instance lock is on.
	 * @return           The number of loads.
	 * @throws Exception If the test fails.
	 */
	private int loadOnTwoInstances(
			final boolean isLockOn) throws Exception {
		final String cacheName = "cross-instance-" + UUID.randomUUID();
		final Cache firstInstanceCache = this.createCentralCacheManager(Duration.ofSeconds(30), isLockOn, Duration.ofSeconds(30)).getCache(cacheName);
		final Cache secondInstanceCache = this.createCentralCacheManager(Duration.ofSeconds(30), isLockOn, Duration.ofSeconds(30)).getCache(cacheName);
		final AtomicInteger loads = new AtomicInteger();
		final Callable<Integer> loader = () -> {
			final Integer load = loads.incrementAndGet();
			Thread.sleep(CacheSingleFlightTest.SLOW_LOAD_MILLIS);
			return load;
		};
		final List<CallOutcome> outcomes = this.callConcurrently(CacheSingleFlightTest.CONCURRENT_CALLS,
				callIndex -> () -> ((callIndex % 2) == 0 ? firstInstanceCache : secondInstanceCache).get("key", loader));
		for (int callIndex = 0; callIndex < outcomes.size(); callIndex++) {
			final CallOutcome outcome = outcomes.get(callIndex);
			Assertions.assertNull(outcome.exception());
			if (isLockOn) {
				Assertions.assertEquals(1, outcome.value());
			}
			else {
				Assertions.assertTrue(Integer.valueOf(1).equals(outcome.value()) || Integer.valueOf(2).equals(outcome.value()), "Value " + outcome.value() + ".");
				// The first two calls are one per instance (even indexes on the first, odd ones on the second).
				Assertions.assertEquals(outcomes.get(callIndex % 2).value(), outcome.value());
			}
		}
		return loads.get();
	}

	/**
	 * Tests that, with the cross-instance lock, two instances load a key once in total.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: with the cross-instance lock, two instances on the same Redis load a key once in total")
	public void testCentralCrossInstanceLock() throws Exception {
		Assertions.assertEquals(1, this.loadOnTwoInstances(true));
		// Without the lock, each instance loads once.
		Assertions.assertEquals(2, this.loadOnTwoInstances(false));
	}

	/**
	 * Tests that an instance waiting for another instance's load loads by itself after the wait timeout, and that an
	 * expired lock is taken over.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: an instance waiting on the cross-instance lock loads by itself after the wait timeout or once the lock expires")
	public void testCentralCrossInstanceLockFallbacks() throws Exception {
		final String cacheName = "cross-instance-fallback-" + UUID.randomUUID();
		final AtomicInteger loads = new AtomicInteger();
		final Callable<Integer> slowLoader = () -> {
			loads.incrementAndGet();
			Thread.sleep(3 * CacheSingleFlightTest.SLOW_LOAD_MILLIS);
			return 1;
		};
		final Callable<Integer> fastLoader = () -> {
			loads.incrementAndGet();
			return 2;
		};
		// Wait timeout: the second instance gives up waiting for the first one's lock after 500ms.
		final Cache slowInstanceCache = this.createCentralCacheManager(Duration.ofSeconds(30), true, Duration.ofSeconds(30)).getCache(cacheName);
		final Cache impatientInstanceCache = this.createCentralCacheManager(Duration.ofMillis(500), true, Duration.ofSeconds(30)).getCache(cacheName);
		List<CallOutcome> outcomes = this.callConcurrently(2,
				callIndex -> (callIndex == 0) ? () -> slowInstanceCache.get("timeout", slowLoader) : () -> {
					Thread.sleep(200);
					return impatientInstanceCache.get("timeout", fastLoader);
				});
		Assertions.assertEquals(2, outcomes.get(1).value());
		Assertions.assertNull(outcomes.get(0).exception());
		Assertions.assertEquals(1, outcomes.get(0).value());
		Assertions.assertTrue(outcomes.get(1).elapsedMillis() < (2 * CacheSingleFlightTest.SLOW_LOAD_MILLIS),
				"Waited " + outcomes.get(1).elapsedMillis() + "ms.");
		Assertions.assertEquals(2, loads.get());
		// Lock expiry: the first instance's lock lasts 300ms, so the second one takes it over while the first still loads.
		loads.set(0);
		final Cache shortLockInstanceCache = this.createCentralCacheManager(Duration.ofSeconds(30), true, Duration.ofMillis(300)).getCache(cacheName);
		final Cache patientInstanceCache = this.createCentralCacheManager(Duration.ofSeconds(30), true, Duration.ofSeconds(30)).getCache(cacheName);
		outcomes = this.callConcurrently(2, callIndex -> (callIndex == 0) ? () -> shortLockInstanceCache.get("expiry", slowLoader) : () -> {
			Thread.sleep(100);
			return patientInstanceCache.get("expiry", fastLoader);
		});
		Assertions.assertEquals(2, outcomes.get(1).value());
		Assertions.assertNull(outcomes.get(0).exception());
		Assertions.assertEquals(1, outcomes.get(0).value());
		Assertions.assertTrue(outcomes.get(1).elapsedMillis() < (2 * CacheSingleFlightTest.SLOW_LOAD_MILLIS),
				"Waited " + outcomes.get(1).elapsedMillis() + "ms.");
		Assertions.assertEquals(2, loads.get());
	}

	/**
	 * Tests that, with the cross-instance lock, a load that fails on one instance reaches its caller, and another
	 * instance waiting on the lock loads right after (well before the wait timeout), leaving no lock behind.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: with the cross-instance lock, a failed load reaches its caller and the waiting instance loads right after")
	public void testCentralCrossInstanceLockLoadFailure() throws Exception {
		final String cacheName = "cross-instance-failure-" + UUID.randomUUID();
		final Duration waitTimeout = Duration.ofSeconds(30);
		final Cache failingInstanceCache = this.createCentralCacheManager(waitTimeout, true, Duration.ofSeconds(30)).getCache(cacheName);
		final Cache waitingInstanceCache = this.createCentralCacheManager(waitTimeout, true, Duration.ofSeconds(30)).getCache(cacheName);
		final AtomicInteger loads = new AtomicInteger();
		final IllegalStateException failure = new IllegalStateException("Load failed.");
		final Callable<Integer> failingLoader = () -> {
			loads.incrementAndGet();
			Thread.sleep(CacheSingleFlightTest.SLOW_LOAD_MILLIS);
			throw failure;
		};
		final Callable<Integer> loader = () -> {
			loads.incrementAndGet();
			return 2;
		};
		final List<CallOutcome> outcomes = this.callConcurrently(2, callIndex -> (callIndex == 0) ? () -> failingInstanceCache.get("key", failingLoader) : () -> {
			Thread.sleep(200);
			return waitingInstanceCache.get("key", loader);
		});
		final Cache.ValueRetrievalException exception = Assertions.assertInstanceOf(Cache.ValueRetrievalException.class, outcomes.get(0).exception());
		Assertions.assertSame(failure, exception.getCause());
		Assertions.assertNull(outcomes.get(1).exception());
		Assertions.assertEquals(2, outcomes.get(1).value());
		Assertions.assertTrue(outcomes.get(1).elapsedMillis() < (5 * CacheSingleFlightTest.SLOW_LOAD_MILLIS), "Waited " + outcomes.get(1).elapsedMillis() + "ms.");
		Assertions.assertEquals(2, loads.get());
		try (RedisConnection connection = this.redisConnectionFactory.getConnection()) {
			Assertions.assertFalse(connection.keyCommands().exists((cacheName + "::key" + SingleFlightRedisCache.LOCK_KEY_SUFFIX).getBytes(StandardCharsets.UTF_8)));
			Assertions.assertTrue(connection.keyCommands().exists((cacheName + "::key").getBytes(StandardCharsets.UTF_8)));
			// Written with the cache's time to live (5 minutes here), not without expiry.
			final Long timeToLiveMillis = connection.keyCommands().pTtl((cacheName + "::key").getBytes(StandardCharsets.UTF_8));
			Assertions.assertTrue((timeToLiveMillis != null) && (timeToLiveMillis > 0) && (timeToLiveMillis <= Duration.ofMinutes(5).toMillis()),
					"Time to live " + timeToLiveMillis + "ms.");
		}
	}

	/**
	 * Tests that a central synchronized load that gives null is cached (as the null placeholder) and loaded once.
	 */
	@Test
	@DisplayName("Central: a synchronized load that gives null is loaded once and returns null")
	public void testCentralNullValueLoadsOnce() {
		final Cache cache = this.createCentralCacheManager(Duration.ofSeconds(30), false, Duration.ofSeconds(30)).getCache("null-value-" + UUID.randomUUID());
		final AtomicInteger loads = new AtomicInteger();
		final Callable<Object> nullLoader = () -> {
			loads.incrementAndGet();
			return null;
		};
		Assertions.assertNull(cache.get("key", nullLoader));
		Assertions.assertNull(cache.get("key", nullLoader));
		Assertions.assertEquals(1, loads.get());
		final Cache.ValueWrapper storedValue = cache.get("key");
		Assertions.assertNotNull(storedValue);
		Assertions.assertNull(storedValue.get());
	}

	/**
	 * Tests that concurrent central callers of one load each get a copy of their own of the value.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: the callers of one synchronized load each get a copy of their own of the value")
	public void testCentralCallersGetOwnCopies() throws Exception {
		final Cache cache = this.createCentralCacheManager(Duration.ofSeconds(30), false, Duration.ofSeconds(30)).getCache("copies-" + UUID.randomUUID());
		final AtomicInteger loads = new AtomicInteger();
		final Callable<ArrayList<String>> loader = () -> {
			loads.incrementAndGet();
			Thread.sleep(CacheSingleFlightTest.SLOW_LOAD_MILLIS / 2);
			return new ArrayList<>(List.of("first", "second"));
		};
		final List<CallOutcome> outcomes = this.callConcurrently(CacheSingleFlightTest.CONCURRENT_CALLS, callIndex -> () -> cache.get("key", loader));
		Assertions.assertEquals(1, loads.get());
		final Set<Object> distinctValues = Collections.newSetFromMap(new IdentityHashMap<>());
		for (final CallOutcome outcome : outcomes) {
			Assertions.assertNull(outcome.exception());
			Assertions.assertEquals(List.of("first", "second"), outcome.value());
			distinctValues.add(outcome.value());
		}
		Assertions.assertEquals(CacheSingleFlightTest.CONCURRENT_CALLS, distinctValues.size());
	}

	/**
	 * Tests that an invalidation (an eviction or a clear) during a slow synchronized load wins: the load's caller and
	 * waiter get its value, but the value is not kept, so the next call loads again.
	 *
	 * @param  cache        Cache.
	 * @param  invalidation Invalidation (cache, key).
	 * @throws Exception    If the test fails.
	 */
	private void testInvalidationDuringLoadWins(
			final Cache cache,
			final BiConsumer<Cache, String> invalidation) throws Exception {
		final String key = "invalidated-" + UUID.randomUUID();
		final AtomicInteger loads = new AtomicInteger();
		final CountDownLatch loadStarted = new CountDownLatch(1);
		final Callable<Integer> slowLoader = () -> {
			final Integer load = loads.incrementAndGet();
			loadStarted.countDown();
			Thread.sleep(CacheSingleFlightTest.SLOW_LOAD_MILLIS / 2);
			return load;
		};
		final ExecutorService executor = Executors.newFixedThreadPool(2);
		final Future<Integer> slowLoad = executor.submit(() -> cache.get(key, slowLoader));
		Assertions.assertTrue(loadStarted.await(10, TimeUnit.SECONDS));
		final Future<Integer> waiter = executor.submit(() -> cache.get(key, slowLoader));
		Thread.sleep(50);
		invalidation.accept(cache, key);
		Assertions.assertEquals(1, slowLoad.get(1, TimeUnit.MINUTES));
		Assertions.assertEquals(1, waiter.get(1, TimeUnit.MINUTES));
		executor.shutdownNow();
		Assertions.assertNull(cache.get(key));
		Assertions.assertEquals(2, cache.get(key, slowLoader));
		Assertions.assertEquals(2, cache.get(key, slowLoader));
		Assertions.assertEquals(2, loads.get());
		Assertions.assertEquals(0, CacheSingleFlightTest.getInFlightLoadCount(cache));
	}

	/**
	 * Tests that an eviction or a clear during a slow local synchronized load wins.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Local: an eviction or a clear during a slow synchronized load wins (the next call loads again)")
	public void testLocalInvalidationDuringLoadWins() throws Exception {
		final Cache cache = new SingleFlightCaffeineCache("invalidation", Caffeine.newBuilder().build(), true, Duration.ofSeconds(30));
		this.testInvalidationDuringLoadWins(cache, Cache::evict);
		this.testInvalidationDuringLoadWins(cache, Cache::evictIfPresent);
		this.testInvalidationDuringLoadWins(cache, (invalidatedCache, key) -> invalidatedCache.clear());
		this.testInvalidationDuringLoadWins(cache, (invalidatedCache, key) -> invalidatedCache.invalidate());
	}

	/**
	 * Tests that an eviction or a clear during a slow central synchronized load wins.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Central: an eviction or a clear during a slow synchronized load wins (the next call loads again)")
	public void testCentralInvalidationDuringLoadWins() throws Exception {
		final Cache cache = this.createCentralCacheManager(Duration.ofSeconds(30), false, Duration.ofSeconds(30)).getCache("invalidation-" + UUID.randomUUID());
		this.testInvalidationDuringLoadWins(cache, Cache::evict);
		this.testInvalidationDuringLoadWins(cache, Cache::evictIfPresent);
		this.testInvalidationDuringLoadWins(cache, (invalidatedCache, key) -> invalidatedCache.clear());
		this.testInvalidationDuringLoadWins(cache, (invalidatedCache, key) -> invalidatedCache.invalidate());
	}

	/**
	 * Wraps an object in a proxy of one of its interfaces that records the name of every method called on it.
	 *
	 * @param  type     Interface.
	 * @param  delegate Object.
	 * @param  calls    Called method names.
	 * @return          The proxy.
	 */
	private static Object recordCalls(
			final Class<?> type,
			final Object delegate,
			final List<String> calls) {
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, arguments) -> {
			calls.add(method.getName());
			try {
				return method.invoke(delegate, arguments);
			}
			catch (final InvocationTargetException exception) {
				throw exception.getCause();
			}
		});
	}

	/**
	 * Tests that a single-flight hit (synchronized or not) is one read of the delegate cache: no load and no in-flight
	 * registration (a registered load would read the delegate again or call the loader).
	 */
	@Test
	@DisplayName("A single-flight hit is one read of the delegate cache (synchronized and not), with no load")
	@SuppressWarnings("unchecked")
	public void testHitIsOneDelegateRead() {
		final Callable<Integer> loader = () -> {
			throw new IllegalStateException("A hit must not load.");
		};
		final List<String> localCalls = new CopyOnWriteArrayList<>();
		final SingleFlightCaffeineCache localCache = new SingleFlightCaffeineCache("hit-structure",
				(com.github.benmanes.caffeine.cache.Cache<Object, Object>) CacheSingleFlightTest.recordCalls(com.github.benmanes.caffeine.cache.Cache.class,
						Caffeine.newBuilder().build(), localCalls),
				true, Duration.ofSeconds(30));
		localCache.put("key", 42);
		localCalls.clear();
		Assertions.assertEquals(42, localCache.get("key", loader));
		Assertions.assertEquals(List.of("getIfPresent"), localCalls);
		localCalls.clear();
		Assertions.assertEquals(42, localCache.get("key").get());
		Assertions.assertEquals(List.of("getIfPresent"), localCalls);
		Assertions.assertEquals(0, localCache.getSingleFlightLoader().getInFlightLoadCount());
		final List<String> centralCalls = new CopyOnWriteArrayList<>();
		final SingleFlightRedisCache centralCache = (SingleFlightRedisCache) new SingleFlightRedisCacheManager(
				(RedisCacheWriter) CacheSingleFlightTest.recordCalls(RedisCacheWriter.class, RedisCacheWriter.nonLockingRedisCacheWriter(this.redisConnectionFactory),
						centralCalls),
				RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)), Duration.ofSeconds(30), null, Duration.ofSeconds(30),
				Duration.ofMillis(50)).getCache("hit-structure-" + UUID.randomUUID());
		centralCache.put("key", 42);
		centralCalls.clear();
		Assertions.assertEquals(42, centralCache.get("key", loader));
		Assertions.assertEquals(List.of("get"), centralCalls);
		centralCalls.clear();
		Assertions.assertEquals(42, centralCache.get("key").get());
		Assertions.assertEquals(List.of("get"), centralCalls);
		Assertions.assertEquals(0, centralCache.getSingleFlightLoader().getInFlightLoadCount());
	}

	/**
	 * Measures the mean time of a cache read over some iterations.
	 *
	 * @param  read       Cache read.
	 * @param  iterations Iterations.
	 * @return            The mean time, in nanoseconds.
	 * @throws Exception  If a read fails.
	 */
	private static double measureNanosPerRead(
			final Callable<Object> read,
			final int iterations) throws Exception {
		long checksum = 0;
		final long start = System.nanoTime();
		for (int iterationIndex = 0; iterationIndex < iterations; iterationIndex++) {
			checksum += read.call().hashCode();
		}
		final double nanosPerRead = (double) (System.nanoTime() - start) / iterations;
		Assertions.assertNotEquals(0, checksum);
		return nanosPerRead;
	}

	/**
	 * Measures the hit path of a plain cache and its single-flight version, synchronized and not. Each round measures
	 * every variant once, in a rotating order (so drift and noise hit all of them alike), after a warm-up round; the
	 * best mean of each variant is kept.
	 *
	 * @param  label             Label.
	 * @param  plainCache        Plain cache.
	 * @param  singleFlightCache Single-flight cache.
	 * @param  iterations        Iterations per variant per round.
	 * @param  rounds            Rounds.
	 * @return                   Nanoseconds per read: plain synchronized, single-flight synchronized, plain, single-flight.
	 * @throws Exception         If a read fails.
	 */
	private static double[] measureHitPath(
			final String label,
			final Cache plainCache,
			final Cache singleFlightCache,
			final int iterations,
			final int rounds) throws Exception {
		final Callable<Integer> loader = () -> {
			throw new IllegalStateException("A hit must not load.");
		};
		plainCache.put("key", 42);
		singleFlightCache.put("key", 42);
		final List<Callable<Object>> reads = List.of(() -> plainCache.get("key", loader), () -> singleFlightCache.get("key", loader),
				() -> plainCache.get("key").get(), () -> singleFlightCache.get("key").get());
		final double[] nanosPerRead = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		for (int roundIndex = 0; roundIndex <= rounds; roundIndex++) {
			for (int readIndex = 0; readIndex < reads.size(); readIndex++) {
				final int variantIndex = (readIndex + roundIndex) % reads.size();
				final double roundNanosPerRead = CacheSingleFlightTest.measureNanosPerRead(reads.get(variantIndex), iterations);
				// Round 0 is the warm-up.
				if (roundIndex > 0) {
					nanosPerRead[variantIndex] = Math.min(nanosPerRead[variantIndex], roundNanosPerRead);
				}
			}
		}
		CacheSingleFlightTest.LOGGER.info(
				"Hit path, {} (ns per read, best of {} interleaved rounds of {}): synchronized get(key, loader): plain {}, single-flight {}; get(key): plain {}, single-flight {}.",
				label, rounds, iterations, String.format(Locale.ROOT, "%.1f", nanosPerRead[0]), String.format(Locale.ROOT, "%.1f", nanosPerRead[1]),
				String.format(Locale.ROOT, "%.1f", nanosPerRead[2]), String.format(Locale.ROOT, "%.1f", nanosPerRead[3]));
		return nanosPerRead;
	}

	/**
	 * Measures and logs the hit path of the single-flight caches (synchronized and not) against the plain caches'. Log
	 * only: timing does not decide the build ({@link #testHitIsOneDelegateRead()} checks the hit path structurally).
	 *
	 * @throws Exception If a read fails.
	 */
	@Test
	@DisplayName("The single-flight hit path cost against the plain caches' (synchronized and not) is measured and logged")
	public void testHitPathOverhead() throws Exception {
		// A measurement (logged for the merge request), not a check: opt in with -Dorg.coldis.test.benchmark=true.
		// testHitIsOneDelegateRead guards the hit path structurally on every build.
		Assumptions.assumeTrue(Boolean.getBoolean("org.coldis.test.benchmark"), "Benchmark off (-Dorg.coldis.test.benchmark=true turns it on).");
		CacheSingleFlightTest.measureHitPath("local",
				new CaffeineCache("hit-path-plain", Caffeine.newBuilder().recordStats().build(), true),
				new SingleFlightCaffeineCache("hit-path-single-flight", Caffeine.newBuilder().recordStats().build(), true, Duration.ofSeconds(60)), 1_000_000, 7);
		final String cacheName = "hit-path-" + UUID.randomUUID();
		CacheSingleFlightTest.measureHitPath("central",
				RedisCacheManager.builder(this.redisConnectionFactory).cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)))
						.build().getCache(cacheName + "-plain"),
				this.createCentralCacheManager(Duration.ofSeconds(60), false, Duration.ofSeconds(60)).getCache(cacheName + "-single-flight"), 1_000, 15);
	}

}
