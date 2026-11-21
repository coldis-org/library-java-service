package org.coldis.library.test.service.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Single-flight loads of synchronized cached methods ({@code @Cacheable(sync = true)}) on the central (Redis) and the
 * local (Caffeine) cache managers, through the AspectJ cache aspect and a Redis container, plus the central
 * cross-instance lock (two cache managers on the same Redis) and the hit path cost against the plain caches.
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
		Assertions.assertTrue(wallMillis < (3 * CacheSingleFlightTest.SLOW_LOAD_MILLIS), "Distinct keys took " + wallMillis + "ms.");
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
		Thread.sleep(300);
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
		Assertions.assertTrue(hitMillis < 500, "Synchronized hit took " + hitMillis + "ms.");
		Assertions.assertTrue(plainMillis < 500, "Plain cached call took " + plainMillis + "ms.");
		Assertions.assertTrue(newKeyMillis < 500, "Synchronized load of a new key took " + newKeyMillis + "ms.");
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
	 * Makes concurrent synchronized loads of one key on two central cache managers (instances) on the same Redis.
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
		for (final CallOutcome outcome : outcomes) {
			Assertions.assertNull(outcome.exception());
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
		Assertions.assertTrue(outcomes.get(1).elapsedMillis() < (2 * CacheSingleFlightTest.SLOW_LOAD_MILLIS),
				"Waited " + outcomes.get(1).elapsedMillis() + "ms.");
		Assertions.assertEquals(2, loads.get());
	}

	/**
	 * Measures the mean time of a cache read, best of some rounds.
	 *
	 * @param  read       Cache read.
	 * @param  iterations Iterations per round.
	 * @param  rounds     Rounds.
	 * @return            The best mean time, in nanoseconds.
	 * @throws Exception  If a read fails.
	 */
	private static double measureNanosPerRead(
			final Callable<Object> read,
			final int iterations,
			final int rounds) throws Exception {
		double bestNanosPerRead = Double.MAX_VALUE;
		long checksum = 0;
		for (int roundIndex = 0; roundIndex < rounds; roundIndex++) {
			final long start = System.nanoTime();
			for (int iterationIndex = 0; iterationIndex < iterations; iterationIndex++) {
				checksum += read.call().hashCode();
			}
			bestNanosPerRead = Math.min(bestNanosPerRead, (double) (System.nanoTime() - start) / iterations);
		}
		Assertions.assertNotEquals(0, checksum);
		return bestNanosPerRead;
	}

	/**
	 * Measures the hit path of a plain cache and its single-flight version, synchronized and not.
	 *
	 * @param  label            Label.
	 * @param  plainCache       Plain cache.
	 * @param  singleFlightCache Single-flight cache.
	 * @param  iterations       Iterations per round.
	 * @param  rounds           Rounds.
	 * @return                  Nanoseconds per read: plain synchronized, single-flight synchronized, plain, single-flight.
	 * @throws Exception        If a read fails.
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
		// Warm-up, then the variants measured in turns.
		double[] nanosPerRead = new double[4];
		for (int turnIndex = 0; turnIndex < 2; turnIndex++) {
			nanosPerRead = new double[] { CacheSingleFlightTest.measureNanosPerRead(() -> plainCache.get("key", loader), iterations, rounds),
					CacheSingleFlightTest.measureNanosPerRead(() -> singleFlightCache.get("key", loader), iterations, rounds),
					CacheSingleFlightTest.measureNanosPerRead(() -> plainCache.get("key").get(), iterations, rounds),
					CacheSingleFlightTest.measureNanosPerRead(() -> singleFlightCache.get("key").get(), iterations, rounds) };
		}
		CacheSingleFlightTest.LOGGER.info(
				"Hit path, {} (ns per read, best of {} rounds of {}): synchronized get(key, loader): plain {}, single-flight {}; get(key): plain {}, single-flight {}.",
				label, rounds, iterations, String.format(Locale.ROOT, "%.1f", nanosPerRead[0]), String.format(Locale.ROOT, "%.1f", nanosPerRead[1]), String.format(Locale.ROOT, "%.1f", nanosPerRead[2]),
				String.format(Locale.ROOT, "%.1f", nanosPerRead[3]));
		return nanosPerRead;
	}

	/**
	 * Tests that the single-flight hit path (synchronized and not) costs about the same as the plain caches'.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The single-flight hit path costs about the same as the plain caches' (synchronized and not)")
	public void testHitPathOverhead() throws Exception {
		final double[] localNanos = CacheSingleFlightTest.measureHitPath("local",
				new CaffeineCache("hit-path-plain", Caffeine.newBuilder().recordStats().build(), true),
				new SingleFlightCaffeineCache("hit-path-single-flight", Caffeine.newBuilder().recordStats().build(), true, Duration.ofSeconds(60)), 1_000_000, 5);
		final String cacheName = "hit-path-" + UUID.randomUUID();
		final double[] centralNanos = CacheSingleFlightTest.measureHitPath("central",
				RedisCacheManager.builder(this.redisConnectionFactory).cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)))
						.build().getCache(cacheName + "-plain"),
				this.createCentralCacheManager(Duration.ofSeconds(60), false, Duration.ofSeconds(60)).getCache(cacheName + "-single-flight"), 2_000, 3);
		// Generous bounds (shared machines are noisy): the single-flight hit is one delegate read, as the plain one.
		Assertions.assertTrue(localNanos[1] < ((2 * localNanos[0]) + 100), "Local synchronized hit: " + localNanos[1] + "ns vs " + localNanos[0] + "ns.");
		Assertions.assertTrue(localNanos[3] < ((2 * localNanos[2]) + 100), "Local hit: " + localNanos[3] + "ns vs " + localNanos[2] + "ns.");
		Assertions.assertTrue(centralNanos[1] < ((1.5 * centralNanos[0]) + 200_000),
				"Central synchronized hit: " + centralNanos[1] + "ns vs " + centralNanos[0] + "ns.");
		Assertions.assertTrue(centralNanos[3] < ((1.5 * centralNanos[2]) + 200_000), "Central hit: " + centralNanos[3] + "ns vs " + centralNanos[2] + "ns.");
	}

}
