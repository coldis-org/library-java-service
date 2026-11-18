package org.coldis.library.service.cache;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.stereotype.Component;

/**
 * Cache helper.
 */
@Component
public class CacheHelper {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(CacheHelper.class);

	/** Local cache configuration. */
	@Autowired(required = false)
	private LocalCacheAutoConfiguration localCacheAutoConfiguration;

	/** Redis cache configuration. */
	@Autowired(required = false)
	private RedisCacheAutoConfiguration redisCacheAutoConfiguration;

	/** Cache managers (including the local ones the fallback creates for central names, which no configuration owns). */
	@Autowired(required = false)
	private List<CacheManager> cacheManagers;

	/**
	 * Clears caches.
	 */
	public void clearCaches() {
		// Clears caches.
		try {
			this.localCacheAutoConfiguration.evictAll();
		}
		catch (final Exception exception) {
		}
		try {
			this.redisCacheAutoConfiguration.evictAll();
		}
		catch (final Exception exception) {
		}
		// Clears the local caches no configuration owns (the central fallback ones).
		if (this.cacheManagers != null) {
			this.cacheManagers.stream().filter(CaffeineCacheManager.class::isInstance).forEach(cacheManager -> {
				try {
					cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
				}
				catch (final Exception exception) {
					CacheHelper.LOGGER.warn("Local cache manager could not be cleared.", exception);
				}
			});
		}
	}

}
