package org.coldis.library.service.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Cache configuration.
 */
@Configuration
@EnableCaching(mode = AdviceMode.ASPECTJ)
@ConditionalOnClass(value = { CacheManager.class, CaffeineCacheManager.class })
public class LocalCacheConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(LocalCacheConfiguration.class);

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
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager millisExpirationLocalCacheManager(
			@Value(value = "${org.coldis.configuration.cache.millis-expiration:3100}")
			final Long expiration) {
		this.millisExpirationLocalCacheManager = new CaffeineCacheManager();
		this.millisExpirationLocalCacheManager
				.setCaffeine(Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofMillis(expiration)).maximumSize(13791));
		return this.millisExpirationLocalCacheManager;
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
		this.secondsExpirationLocalCacheManager = new CaffeineCacheManager();
		this.secondsExpirationLocalCacheManager
				.setCaffeine(Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofSeconds(expiration)).maximumSize(13791));
		return this.secondsExpirationLocalCacheManager;
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
		this.minutesExpirationLocalCacheManager = new CaffeineCacheManager();
		this.minutesExpirationLocalCacheManager
				.setCaffeine(Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofMinutes(expiration)).maximumSize(13791));
		return this.minutesExpirationLocalCacheManager;
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
		this.hoursExpirationLocalCacheManager = new CaffeineCacheManager();
		this.hoursExpirationLocalCacheManager
				.setCaffeine(Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofHours(expiration)).maximumSize(13791));
		return this.hoursExpirationLocalCacheManager;
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
		this.dayExpirationLocalCacheManager = new CaffeineCacheManager();
		this.dayExpirationLocalCacheManager.setCaffeine(Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofDays(expiration)).maximumSize(13791));
		return this.dayExpirationLocalCacheManager;
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
		this.daysExpirationLocalCacheManager = new CaffeineCacheManager();
		this.daysExpirationLocalCacheManager.setCaffeine(Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofDays(expiration)).maximumSize(13791));
		return this.daysExpirationLocalCacheManager;
	}

	/**
	 * Logs stats.
	 */
	@Scheduled(cron = "0 */5 * * * *")
	public void logStats() {
		this.millisExpirationLocalCacheManager.getCacheNames()
				.forEach(name -> LocalCacheConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
						+ ((CaffeineCache) this.millisExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize() + "' and stats: "
						+ ((CaffeineCache) this.millisExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.secondsExpirationLocalCacheManager.getCacheNames()
				.forEach(name -> LocalCacheConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
						+ ((CaffeineCache) this.secondsExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize() + "' and stats: "
						+ ((CaffeineCache) this.secondsExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.minutesExpirationLocalCacheManager.getCacheNames()
				.forEach(name -> LocalCacheConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
						+ ((CaffeineCache) this.minutesExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize() + "' and stats: "
						+ ((CaffeineCache) this.minutesExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.hoursExpirationLocalCacheManager.getCacheNames()
				.forEach(name -> LocalCacheConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
						+ ((CaffeineCache) this.hoursExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize() + "' and stats: "
						+ ((CaffeineCache) this.hoursExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.dayExpirationLocalCacheManager.getCacheNames()
				.forEach(name -> LocalCacheConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
						+ ((CaffeineCache) this.dayExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize() + "' and stats: "
						+ ((CaffeineCache) this.dayExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
		this.daysExpirationLocalCacheManager.getCacheNames()
				.forEach(name -> LocalCacheConfiguration.LOGGER.debug("Cache '" + name + "' estimated size in '"
						+ ((CaffeineCache) this.daysExpirationLocalCacheManager.getCache(name)).getNativeCache().estimatedSize() + "' and stats: "
						+ ((CaffeineCache) this.daysExpirationLocalCacheManager.getCache(name)).getNativeCache().stats().toString()));
	}

}
