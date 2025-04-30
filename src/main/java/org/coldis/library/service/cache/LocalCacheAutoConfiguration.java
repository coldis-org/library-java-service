package org.coldis.library.service.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
	static final Logger LOGGER = LoggerFactory.getLogger(LocalCacheAutoConfiguration.class);

	/** Maximum cache size. */
	@Value(value = "${org.coldis.configuration.cache.local.maximum-size:}")
	private Long maximumSize;

	/** If stats should be recoreded. */
	@Value(value = "${org.coldis.configuration.cache.local.record-stats:false}")
	private Boolean stats;

	/**
	 * Cache manager.
	 */
	CaffeineCacheManager millisExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	CaffeineCacheManager secondsExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	CaffeineCacheManager minutesExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	CaffeineCacheManager hoursExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	CaffeineCacheManager dayExpirationLocalCacheManager;

	/**
	 * Cache manager.
	 */
	CaffeineCacheManager daysExpirationLocalCacheManager;

	/**
	 * Creates a cache manager with the given expiration.
	 *
	 * @param  expiration Expiration in milliseconds.
	 * @return            Cache manager.
	 */
	private CaffeineCacheManager getCacheManager(
			final Duration expiration) {
		final CaffeineCacheManager cacheManager = new CaffeineCacheManager();
		Caffeine<Object, Object> caffeine = Caffeine.newBuilder().expireAfterWrite(expiration);
		if (this.maximumSize != null) {
			caffeine.maximumSize(this.maximumSize);
		}
		if (this.stats) {
			caffeine = caffeine.recordStats();
		}
		cacheManager.setCaffeine(caffeine);
		return cacheManager;
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
		this.millisExpirationLocalCacheManager = this.getCacheManager(Duration.ofMillis(expiration));
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
		this.secondsExpirationLocalCacheManager = this.getCacheManager(Duration.ofSeconds(expiration));
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
		this.minutesExpirationLocalCacheManager = this.getCacheManager(Duration.ofMinutes(expiration));
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
		this.hoursExpirationLocalCacheManager = this.getCacheManager(Duration.ofHours(expiration));
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
		this.dayExpirationLocalCacheManager = this.getCacheManager(Duration.ofDays(expiration));
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
		this.daysExpirationLocalCacheManager = this.getCacheManager(Duration.ofDays(expiration));
		return this.daysExpirationLocalCacheManager;
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
