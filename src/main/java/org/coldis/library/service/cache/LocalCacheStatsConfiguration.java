package org.coldis.library.service.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Local cache stats debug log: every five minutes, each local cache's estimated size and stats, at debug level. On while
 * the local caches record stats (the default).
 */
@Configuration
@ConditionalOnProperty(
		value = LocalCacheAutoConfiguration.RECORD_STATS_PROPERTY,
		havingValue = "true",
		matchIfMissing = true
)
@ConditionalOnProperty(
		name = LocalCacheAutoConfiguration.ENABLED_PROPERTY,
		havingValue = "true",
		matchIfMissing = true
)
class LocalCacheStatsConfiguration {

	/**
	 * Local cache configriration.
	 */
	private final LocalCacheAutoConfiguration localCacheAutoConfiguration;

	/**
	 * Constructor.
	 *
	 * @param localCacheAutoConfiguration Local cache configuration.
	 */
	LocalCacheStatsConfiguration(final LocalCacheAutoConfiguration localCacheAutoConfiguration) {
		this.localCacheAutoConfiguration = localCacheAutoConfiguration;
	}

	/**
	 * Logs stats, if debug logging is on (stats recording is now on by default, so the scheduled run costs nothing
	 * otherwise).
	 */
	@Scheduled(cron = "0 */5 * * * *")
	public void logStats() {
		if (!LocalCacheAutoConfiguration.LOGGER.isDebugEnabled()) {
			return;
		}
		this.localCacheAutoConfiguration.millisExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
				+ name + "' estimated size in '"
				+ ((CaffeineCache) this.localCacheAutoConfiguration.millisExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
				+ "' and stats: "
				+ ((CaffeineCache) this.localCacheAutoConfiguration.millisExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.localCacheAutoConfiguration.secondsExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
				+ name + "' estimated size in '"
				+ ((CaffeineCache) this.localCacheAutoConfiguration.secondsExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
				+ "' and stats: "
				+ ((CaffeineCache) this.localCacheAutoConfiguration.secondsExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.localCacheAutoConfiguration.minutesExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
				+ name + "' estimated size in '"
				+ ((CaffeineCache) this.localCacheAutoConfiguration.minutesExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
				+ "' and stats: "
				+ ((CaffeineCache) this.localCacheAutoConfiguration.minutesExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.localCacheAutoConfiguration.hoursExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
				+ name + "' estimated size in '"
				+ ((CaffeineCache) this.localCacheAutoConfiguration.hoursExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
				+ "' and stats: "
				+ ((CaffeineCache) this.localCacheAutoConfiguration.hoursExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.localCacheAutoConfiguration.dayExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
				+ name + "' estimated size in '"
				+ ((CaffeineCache) this.localCacheAutoConfiguration.dayExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
				+ "' and stats: "
				+ ((CaffeineCache) this.localCacheAutoConfiguration.dayExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.localCacheAutoConfiguration.daysExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
				+ name + "' estimated size in '"
				+ ((CaffeineCache) this.localCacheAutoConfiguration.daysExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
				+ "' and stats: "
				+ ((CaffeineCache) this.localCacheAutoConfiguration.daysExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
	}

}
