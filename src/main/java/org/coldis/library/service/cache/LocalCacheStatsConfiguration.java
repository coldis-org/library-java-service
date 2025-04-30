package org.coldis.library.service.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Local cache stats.
 */
@Configuration
@ConditionalOnProperty(
		value = "org.coldis.configuration.cache.local.record-stats",
		havingValue = "true",
		matchIfMissing = false
)
class LocalCacheStatsConfiguration {

	/**
	 * Local cache configriration.
	 */
	private final LocalCacheAutoConfiguration localCacheAutoConfiguration;

	/**
	 * TODO Javadoc
	 *
	 * @param localCacheAutoConfiguration Javadoc
	 */
	LocalCacheStatsConfiguration(final LocalCacheAutoConfiguration localCacheAutoConfiguration) {
		this.localCacheAutoConfiguration = localCacheAutoConfiguration;
	}

	/**
	 * Logs stats.
	 */
	@Scheduled(cron = "0 */5 * * * *")
	public void logStats() {
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
