package org.coldis.library.service.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CacheHelper {

	/** Local cache configuration. */
	@Autowired(required = false)
	private LocalCacheAutoConfiguration localCacheAutoConfiguration;

	/** Redis cache configuration. */
	@Autowired(required = false)
	private RedisCacheAutoConfiguration redisCacheAutoConfiguration;

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
	}

}
