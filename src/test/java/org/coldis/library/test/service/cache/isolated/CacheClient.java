package org.coldis.library.test.service.cache.isolated;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.cache.annotation.Cacheable;

/**
 * Counts its calls behind a local and a central cache. Registered by hand in each isolated context (it has no stereotype,
 * so the shared test context does not scan it).
 */
public class CacheClient {

	/**
	 * Calls that reached the local cached method.
	 */
	private final AtomicInteger localCalls = new AtomicInteger();

	/**
	 * Calls that reached the central cached method.
	 */
	private final AtomicInteger centralCalls = new AtomicInteger();

	/**
	 * Counts a call behind the local minutes cache.
	 *
	 * @param  key Key.
	 * @return     How many calls reached the method so far.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = "cacheClient-local"
	)
	public Integer getFromLocalCache(
			final String key) {
		return this.localCalls.incrementAndGet();
	}

	/**
	 * Counts a call behind the central minutes cache.
	 *
	 * @param  key Key.
	 * @return     How many calls reached the method so far.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationCentralCacheManager",
			value = "cacheClient-central"
	)
	public Integer getFromCentralCache(
			final String key) {
		return this.centralCalls.incrementAndGet();
	}

	/**
	 * Gets the calls that reached the local cached method.
	 *
	 * @return The calls that reached the local cached method.
	 */
	public Integer getLocalCalls() {
		return this.localCalls.get();
	}

	/**
	 * Gets the calls that reached the central cached method.
	 *
	 * @return The calls that reached the central cached method.
	 */
	public Integer getCentralCalls() {
		return this.centralCalls.get();
	}

}
