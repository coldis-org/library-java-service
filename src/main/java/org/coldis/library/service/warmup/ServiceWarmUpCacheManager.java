package org.coldis.library.service.warmup;

import java.util.Collection;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Cache manager that gives warm-up calls caches that drop writes. Other threads get the caches of the wrapped manager
 * themselves, so their types and behaviour do not change.
 */
public class ServiceWarmUpCacheManager implements CacheManager {

	/**
	 * Cache manager.
	 */
	private final CacheManager cacheManager;

	/**
	 * Constructor.
	 *
	 * @param cacheManager Cache manager.
	 */
	public ServiceWarmUpCacheManager(final CacheManager cacheManager) {
		super();
		this.cacheManager = cacheManager;
	}

	/**
	 * Gets the wrapped cache manager.
	 *
	 * @return The wrapped cache manager.
	 */
	public CacheManager getCacheManager() {
		return this.cacheManager;
	}

	/**
	 * @see org.springframework.cache.CacheManager#getCache(java.lang.String)
	 */
	@Override
	public Cache getCache(
			final String name) {
		final Cache cache = this.cacheManager.getCache(name);
		return ((cache != null) && ServiceWarmUpContext.isWarmingUp() ? new ServiceWarmUpCache(cache) : cache);
	}

	/**
	 * @see org.springframework.cache.CacheManager#getCacheNames()
	 */
	@Override
	public Collection<String> getCacheNames() {
		return this.cacheManager.getCacheNames();
	}

}
