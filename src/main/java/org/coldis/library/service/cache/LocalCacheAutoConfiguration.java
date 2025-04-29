package org.coldis.library.service.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Cache configuration.
 */
@Configuration
@EnableCaching(mode = AdviceMode.ASPECTJ)
@ConditionalOnClass(value = { CacheManager.class, CaffeineCacheManager.class })
public class LocalCacheAutoConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(LocalCacheAutoConfiguration.class);

	/** Maximum cache size. */
	@Value(value = "${org.coldis.configuration.cache.local.maximum-size:20000}")
	private Long maximumSize;
	
	/** If stats should be recoreded. */
	@Value(value = "${org.coldis.configuration.cache.local.record-stats:false}")
	private Boolean stats;

	/**
	 * Cache manager.
	 */
	private CaffeineCacheManager millisExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	private CaffeineCacheManager secondsExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	private CaffeineCacheManager minutesExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	private CaffeineCacheManager hoursExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	private CaffeineCacheManager dayExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	private CaffeineCacheManager daysExpirationLocalCacheManager;

	
	/**
	 * Creates a cache manager with the given expiration.
	 * @param expiration Expiration in milliseconds.
	 * @return Cache manager.
	 */
	private CacheManager getCacheManager(
			final Long expiration) {
		this.millisExpirationLocalCacheManager = new CaffeineCacheManager();
		Caffeine<Object, Object> caffeine = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(expiration)).maximumSize(this.maximumSize);
		if (this.stats) {
			caffeine = caffeine.recordStats();
		}
		this.millisExpirationLocalCacheManager
				.setCaffeine(caffeine);
		return this.millisExpirationLocalCacheManager;
	}


	/**
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager millisExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.millis-expiration:3100}")
			final Long expiration) {
		return getCacheManager(expiration);
	}

	/**
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager secondsExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.seconds-expiration:23}")
			final Long expiration) {
		return getCacheManager(expiration);
	}

	/**
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	@Primary
	public CacheManager minutesExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.minutes-expiration:11}")
			final Long expiration) {
		return getCacheManager(expiration);
	}

	/**
	 * Long lived cache.
	 *
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager hoursExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.hours-expiration:3}")
			final Long expiration) {
		return getCacheManager(expiration);
	}

	/**
	 * Long lived cache.
	 *
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager dayExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.day-expiration:1}")
			final Long expiration) {
		return getCacheManager(expiration);
	}

	/**
	 * Long lived cache.
	 *
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager daysExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.days-expiration:5}")
			final Long expiration) {
		return getCacheManager(expiration);
	}

	/**
	 * Local cache stats.
	 */
	@Component
	@ConditionalOnProperty(
			value = "org.coldis.configuration.cache.local.record-stats",
			havingValue = "true",
			matchIfMissing = false
	)
	class LocalCacheStats  {


		/**
		 * Logs stats.
		 */
		@Scheduled(cron = "0 */5 * * * *")
		public void logStats() {
			LocalCacheAutoConfiguration.this.millisExpirationLocalCacheManager.getCacheNames()
					.forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
							+ ((CaffeineCache) LocalCacheAutoConfiguration.this.millisExpirationLocalCacheManager.getCache(name)).getNativeCache()
									.estimatedSize()
							+ "' and stats: " + ((CaffeineCache) LocalCacheAutoConfiguration.this.millisExpirationLocalCacheManager.getCache(name))
									.getNativeCache().stats().toString()));
			LocalCacheAutoConfiguration.this.secondsExpirationLocalCacheManager.getCacheNames()
					.forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
							+ ((CaffeineCache) LocalCacheAutoConfiguration.this.secondsExpirationLocalCacheManager.getCache(name)).getNativeCache()
									.estimatedSize()
							+ "' and stats: " + ((CaffeineCache) LocalCacheAutoConfiguration.this.secondsExpirationLocalCacheManager.getCache(name))
									.getNativeCache().stats().toString()));
			LocalCacheAutoConfiguration.this.minutesExpirationLocalCacheManager.getCacheNames()
					.forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
							+ ((CaffeineCache) LocalCacheAutoConfiguration.this.minutesExpirationLocalCacheManager.getCache(name)).getNativeCache()
									.estimatedSize()
							+ "' and stats: " + ((CaffeineCache) LocalCacheAutoConfiguration.this.minutesExpirationLocalCacheManager.getCache(name))
									.getNativeCache().stats().toString()));
			LocalCacheAutoConfiguration.this.hoursExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
					+ name + "' estimated size in '"
					+ ((CaffeineCache) LocalCacheAutoConfiguration.this.hoursExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
					+ "' and stats: "
					+ ((CaffeineCache) LocalCacheAutoConfiguration.this.hoursExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
			LocalCacheAutoConfiguration.this.dayExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
					+ name + "' estimated size in '"
					+ ((CaffeineCache) LocalCacheAutoConfiguration.this.dayExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
					+ "' and stats: "
					+ ((CaffeineCache) LocalCacheAutoConfiguration.this.dayExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
			LocalCacheAutoConfiguration.this.daysExpirationLocalCacheManager.getCacheNames().forEach(name -> LocalCacheAutoConfiguration.LOGGER.debug("Cache '"
					+ name + "' estimated size in '"
					+ ((CaffeineCache) LocalCacheAutoConfiguration.this.daysExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize()
					+ "' and stats: "
					+ ((CaffeineCache) LocalCacheAutoConfiguration.this.daysExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		}

	}

	/**
	 * Evict all caches.
	 */
	public void evictAll() {
		this.millisExpirationLocalCacheManager.getCacheNames().stream().forEach(name -> this.millisExpirationLocalCacheManager.getCache(name).clear());
		this.secondsExpirationLocalCacheManager.getCacheNames().stream().forEach(name -> this.secondsExpirationLocalCacheManager.getCache(name).clear());
		this.minutesExpirationLocalCacheManager.getCacheNames().stream().forEach(name -> this.minutesExpirationLocalCacheManager.getCache(name).clear());
		this.hoursExpirationLocalCacheManager.getCacheNames().stream().forEach(name -> this.hoursExpirationLocalCacheManager.getCache(name).clear());
		this.dayExpirationLocalCacheManager.getCacheNames().stream().forEach(name -> this.dayExpirationLocalCacheManager.getCache(name).clear());
		this.daysExpirationLocalCacheManager.getCacheNames().stream().forEach(name -> this.daysExpirationLocalCacheManager.getCache(name).clear());
	}

}
