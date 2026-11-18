package org.coldis.library.service.cache;

import java.time.Duration;

import org.coldis.library.service.serialization.JsonMapperAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Local (Caffeine) cache configuration. On by default; {@value #ENABLED_PROPERTY}=false turns it off, and the local
 * cache manager names then do not cache (see {@link CacheManagerFallbackRegistrar}).
 */
@Configuration
@EnableCaching(mode = AdviceMode.ASPECTJ)
@DependsOn(value = { "jsonMapperAutoConfiguration" })
@ConditionalOnClass(value = { CacheManager.class, CaffeineCacheManager.class })
@ConditionalOnProperty(
		name = LocalCacheAutoConfiguration.ENABLED_PROPERTY,
		havingValue = "true",
		matchIfMissing = true
)
public class LocalCacheAutoConfiguration {

	/**
	 * Property that turns local caching on or off.
	 */
	public static final String ENABLED_PROPERTY = "org.coldis.configuration.cache.local.enabled";

	/**
	 * Logger.
	 */
	static final Logger LOGGER = LoggerFactory.getLogger(LocalCacheAutoConfiguration.class);

	/**
	 * Ignore type info introspector.
	 */
	private class IgnoreTypeInfoIntrospector extends JacksonAnnotationIntrospector {

		/**
		 * Serial.
		 */
		private static final long serialVersionUID = 6492455468446095497L;

		/**
		 * @see com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector#findPolymorphicTypeInfo(com.fasterxml.jackson.databind.cfg.MapperConfig,
		 *      com.fasterxml.jackson.databind.introspect.Annotated)
		 */
		@Override
		public JsonTypeInfo.Value findPolymorphicTypeInfo(
				final MapperConfig<?> config,
				final Annotated ann) {
			return null;
		}

		/**
		 * @see com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector#findTypeName(com.fasterxml.jackson.databind.introspect.AnnotatedClass)
		 */
		@Override
		public String findTypeName(
				final AnnotatedClass ac) {
			return null;
		}
	}

	/** Key converter. */
	private final Converter<Object, String> keyConverter;

	/**
	 * Default constructor.
	 *
	 * @throws JsonProcessingException
	 */
	public LocalCacheAutoConfiguration(final JsonMapperAutoConfiguration jsonMapperAutoConfiguration, final Jackson2ObjectMapperBuilder builder)
			throws JsonProcessingException {
		final ObjectMapper objectMapper = jsonMapperAutoConfiguration.genericMapper(builder);
		objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
		objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
		objectMapper.setAnnotationIntrospector(new IgnoreTypeInfoIntrospector());
		this.keyConverter = new HashCacheKeyConverter(objectMapper, "SHA-256");
	}

	/**
	 * Default maximum entries of each local cache (each cache name of each local cache manager).
	 */
	public static final long DEFAULT_MAXIMUM_SIZE = 10000L;

	/**
	 * Maximum entries of each local cache property.
	 */
	public static final String MAXIMUM_SIZE_PROPERTY = "org.coldis.configuration.cache.local.maximum-size";

	/** Maximum entries of each local cache. */
	@Value(value = "${" + LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY + ":}")
	private Long maximumSize;

	/**
	 * Property that turns local cache stats recording on.
	 */
	public static final String RECORD_STATS_PROPERTY = "org.coldis.configuration.cache.local.record-stats";

	/** If stats should be recoreded. */
	@Value(value = "${" + LocalCacheAutoConfiguration.RECORD_STATS_PROPERTY + ":false}")
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
	 * Creates a local cache manager.
	 *
	 * @param  expiration        Expiration after write.
	 * @param  maximumSize       Maximum entries per cache ({@link #DEFAULT_MAXIMUM_SIZE} if null or not positive).
	 * @param  shouldRecordStats If stats should be recorded.
	 * @return                   Cache manager.
	 */
	static CaffeineCacheManager createCacheManager(
			final Duration expiration,
			final Long maximumSize,
			final Boolean shouldRecordStats) {
		final CaffeineCacheManager cacheManager = new CaffeineCacheManager();
		Caffeine<Object, Object> caffeine = Caffeine.newBuilder().expireAfterWrite(expiration);
		caffeine.maximumSize((maximumSize == null) || (maximumSize <= 0) ? LocalCacheAutoConfiguration.DEFAULT_MAXIMUM_SIZE : maximumSize);
		if (Boolean.TRUE.equals(shouldRecordStats)) {
			caffeine = caffeine.recordStats();
		}
		cacheManager.setCaffeine(caffeine);
		return cacheManager;
	}

	/**
	 * Creates a cache manager with the given expiration.
	 *
	 * @param  expiration Expiration.
	 * @return            Cache manager.
	 */
	private CaffeineCacheManager getCacheManager(
			final Duration expiration) {
		return LocalCacheAutoConfiguration.createCacheManager(expiration, this.maximumSize, this.stats);
	}

	/**
	 * Short lived cache.
	 *
	 * @param  environment Environment.
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager millisExpirationLocalCacheManager(
			final Environment environment) {
		this.millisExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.MILLIS.getExpiration(environment));
		return this.millisExpirationLocalCacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @param  environment Environment.
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager secondsExpirationLocalCacheManager(
			final Environment environment) {
		this.secondsExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.SECONDS.getExpiration(environment));
		return this.secondsExpirationLocalCacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @param  environment Environment.
	 * @return Short lived cache.
	 */
	@Bean
	@Primary
	public CacheManager minutesExpirationLocalCacheManager(
			final Environment environment) {
		this.minutesExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.MINUTES.getExpiration(environment));
		return this.minutesExpirationLocalCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @param  environment Environment.
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager hoursExpirationLocalCacheManager(
			final Environment environment) {
		this.hoursExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.HOURS.getExpiration(environment));
		return this.hoursExpirationLocalCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @param  environment Environment.
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager dayExpirationLocalCacheManager(
			final Environment environment) {
		this.dayExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.DAY.getExpiration(environment));
		return this.dayExpirationLocalCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @param  environment Environment.
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager daysExpirationLocalCacheManager(
			final Environment environment) {
		this.daysExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.DAYS.getExpiration(environment));
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
