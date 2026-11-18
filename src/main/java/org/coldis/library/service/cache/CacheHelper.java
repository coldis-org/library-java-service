package org.coldis.library.service.cache;

import java.util.Map;

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

	/** Cache managers by bean name (the local ones the fallback creates for central names are owned by no configuration). */
	@Autowired(required = false)
	private Map<String, CacheManager> cacheManagers;

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
		// Clears the local caches the fallback created under the central names (with Redis, these names are Redis).
		if (this.cacheManagers != null) {
			for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
				if (this.cacheManagers.get(expiration.getCentralCacheManagerName()) instanceof final CaffeineCacheManager cacheManager) {
					try {
						cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
					}
					catch (final Exception exception) {
						CacheHelper.LOGGER.warn("Cache manager '" + expiration.getCentralCacheManagerName() + "' could not be cleared.", exception);
					}
				}
			}
		}
	}

}
