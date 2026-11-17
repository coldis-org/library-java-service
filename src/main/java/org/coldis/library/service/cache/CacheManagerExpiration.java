package org.coldis.library.service.cache;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.core.env.Environment;

/**
 * The expirations the library's cache managers come in: each one has a local (Caffeine) and a central (Redis) cache
 * manager, named after it, and the same expiration property.
 */
public enum CacheManagerExpiration {

	/**
	 * Milliseconds.
	 */
	MILLIS("millis", "org.coldis.configuration.cache.millis-expiration", 3100L, ChronoUnit.MILLIS),

	/**
	 * Seconds.
	 */
	SECONDS("seconds", "org.coldis.configuration.cache.seconds-expiration", 23L, ChronoUnit.SECONDS),

	/**
	 * Minutes. Its local cache manager is the default (primary) one.
	 */
	MINUTES("minutes", "org.coldis.configuration.cache.minutes-expiration", 11L, ChronoUnit.MINUTES),

	/**
	 * Hours.
	 */
	HOURS("hours", "org.coldis.configuration.cache.hours-expiration", 3L, ChronoUnit.HOURS),

	/**
	 * Day.
	 */
	DAY("day", "org.coldis.configuration.cache.day-expiration", 1L, ChronoUnit.DAYS),

	/**
	 * Days.
	 */
	DAYS("days", "org.coldis.configuration.cache.days-expiration", 5L, ChronoUnit.DAYS);

	/**
	 * Prefix of the cache manager names.
	 */
	private final String namePrefix;

	/**
	 * Expiration property.
	 */
	private final String expirationProperty;

	/**
	 * Expiration when the property is not set.
	 */
	private final Long defaultExpiration;

	/**
	 * Unit of the expiration.
	 */
	private final ChronoUnit expirationUnit;

	/**
	 * Constructor.
	 *
	 * @param namePrefix         Prefix of the cache manager names.
	 * @param expirationProperty Expiration property.
	 * @param defaultExpiration  Expiration when the property is not set.
	 * @param expirationUnit     Unit of the expiration.
	 */
	CacheManagerExpiration(final String namePrefix, final String expirationProperty, final Long defaultExpiration, final ChronoUnit expirationUnit) {
		this.namePrefix = namePrefix;
		this.expirationProperty = expirationProperty;
		this.defaultExpiration = defaultExpiration;
		this.expirationUnit = expirationUnit;
	}

	/**
	 * Gets the name of the local cache manager.
	 *
	 * @return The name of the local cache manager.
	 */
	public String getLocalCacheManagerName() {
		return this.namePrefix + "ExpirationLocalCacheManager";
	}

	/**
	 * Gets the name of the central cache manager.
	 *
	 * @return The name of the central cache manager.
	 */
	public String getCentralCacheManagerName() {
		return this.namePrefix + "ExpirationCentralCacheManager";
	}

	/**
	 * Gets the expiration configured in the environment.
	 *
	 * @param  environment Environment.
	 * @return             The expiration.
	 */
	public Duration getExpiration(
			final Environment environment) {
		return Duration.of(environment.getProperty(this.expirationProperty, Long.class, this.defaultExpiration), this.expirationUnit);
	}

}
