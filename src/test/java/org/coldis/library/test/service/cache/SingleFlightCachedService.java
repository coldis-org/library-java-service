package org.coldis.library.test.service.cache;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Synchronized cached methods (slow, failing and nested loads) on a central and a local cache manager, and plain cached
 * methods to check that unrelated cache calls are not slowed by slow loads.
 */
@Service
public class SingleFlightCachedService {

	/**
	 * Central cache name.
	 */
	public static final String CENTRAL_CACHE = "singleFlightCachedService-central";

	/**
	 * Local cache name.
	 */
	public static final String LOCAL_CACHE = "singleFlightCachedService-local";

	/**
	 * Loads by key.
	 */
	private static final Map<String, AtomicInteger> LOADS = new ConcurrentHashMap<>();

	/**
	 * Keys whose loads fail.
	 */
	public static final Set<String> FAILING_KEYS = ConcurrentHashMap.newKeySet();

	/**
	 * Gets the loads of a key.
	 *
	 * @param  key Key.
	 * @return     The loads of the key.
	 */
	public static int getLoads(
			final String key) {
		return SingleFlightCachedService.LOADS.computeIfAbsent(key, ignoredKey -> new AtomicInteger()).get();
	}

	/**
	 * Loads a key: counts the load, waits and fails if the key is failing.
	 *
	 * @param  key          Key.
	 * @param  sleepMillis  How long the load takes.
	 * @return              The number of the load (1 for the first load of the key).
	 */
	public static Integer load(
			final String key,
			final Long sleepMillis) {
		final Integer load = SingleFlightCachedService.LOADS.computeIfAbsent(key, ignoredKey -> new AtomicInteger()).incrementAndGet();
		try {
			Thread.sleep(sleepMillis);
		}
		catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
		if (SingleFlightCachedService.FAILING_KEYS.contains(key)) {
			throw new IllegalStateException("Load of '" + key + "' failed.");
		}
		return load;
	}

	/**
	 * Synchronized load on the central cache.
	 *
	 * @param  key         Key.
	 * @param  sleepMillis How long the load takes.
	 * @return             The number of the load.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationCentralCacheManager",
			value = SingleFlightCachedService.CENTRAL_CACHE,
			sync = true
	)
	public Integer getFromCentralCache(
			final String key,
			final Long sleepMillis) {
		return SingleFlightCachedService.load(key, sleepMillis);
	}

	/**
	 * Synchronized load on the local cache.
	 *
	 * @param  key         Key.
	 * @param  sleepMillis How long the load takes.
	 * @return             The number of the load.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = SingleFlightCachedService.LOCAL_CACHE,
			sync = true
	)
	public Integer getFromLocalCache(
			final String key,
			final Long sleepMillis) {
		return SingleFlightCachedService.load(key, sleepMillis);
	}

	/**
	 * Synchronized load on the local cache that loads another key of the same cache (synchronized too).
	 *
	 * @param  key Key.
	 * @return     The number of the inner load.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = SingleFlightCachedService.LOCAL_CACHE,
			sync = true
	)
	public Integer getNestedFromLocalCache(
			final String key) {
		SingleFlightCachedService.load(key, 0L);
		return this.getFromLocalCache(key + "-inner", 10L);
	}

	/**
	 * Plain (not synchronized) cached call on another central cache.
	 *
	 * @param  key Key.
	 * @return     The key length.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationCentralCacheManager",
			value = "singleFlightCachedService-central-plain"
	)
	public Integer getPlainFromCentralCache(
			final String key) {
		return key.length();
	}

	/**
	 * Plain (not synchronized) cached call on another local cache.
	 *
	 * @param  key Key.
	 * @return     The key length.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = "singleFlightCachedService-local-plain"
	)
	public Integer getPlainFromLocalCache(
			final String key) {
		return key.length();
	}

}
