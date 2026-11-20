package org.coldis.library.service.cache;

import java.time.Duration;
import java.util.function.Supplier;

import org.coldis.library.service.serialization.JsonMapperAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Local (Caffeine) cache configuration. On by default; {@value #ENABLED_PROPERTY}=false turns it off, and the local
 * cache manager names then do not cache (see {@link CacheManagerFallbackRegistrar}). The local caches record stats
 * ({@value #RECORD_STATS_PROPERTY}, on by default) and, when the context has a Micrometer meter registry, publish them
 * per cache ({@value #METRICS_ENABLED_PROPERTY}, on by default; see {@link MeteredCaffeineCacheManager}).
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

	/** Meter registry provider. */
	private final ObjectProvider<MeterRegistry> meterRegistryProvider;

	/**
	 * Default constructor.
	 *
	 * @param  jsonMapperAutoConfiguration JSON mapper configuration.
	 * @param  builder                     Object mapper builder.
	 * @param  meterRegistryProvider       Meter registry provider (the context may have no registry).
	 * @throws JsonProcessingException     If the key mapper cannot be created.
	 */
	public LocalCacheAutoConfiguration(
			final JsonMapperAutoConfiguration jsonMapperAutoConfiguration,
			final Jackson2ObjectMapperBuilder builder,
			final ObjectProvider<MeterRegistry> meterRegistryProvider) throws JsonProcessingException {
		this.meterRegistryProvider = meterRegistryProvider;
		final ObjectMapper objectMapper = jsonMapperAutoConfiguration.genericMapper(builder);
		objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
		objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
		objectMapper.setAnnotationIntrospector(new IgnoreTypeInfoIntrospector());
		this.keyConverter = new HashCacheKeyConverter(objectMapper, "SHA-256");
	}

	/**
	 * Maximum entries of each local cache property (each cache name of each local cache manager). Unset or
	 * not positive leaves local caches unbounded (bounded by their expiration only), as before local
	 * caching was on by default: a default cap thrashes caches whose working set is larger (statistics
	 * buckets, for instance) and sends every miss to the central cache or the database.
	 */
	public static final String MAXIMUM_SIZE_PROPERTY = "org.coldis.configuration.cache.local.maximum-size";

	/** Maximum entries of each local cache. */
	@Value(value = "${" + LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY + ":}")
	private Long maximumSize;

	/**
	 * Property that turns local cache stats recording on (the default) or off. Off, the local caches publish no metrics
	 * and the stats debug log is not scheduled.
	 */
	public static final String RECORD_STATS_PROPERTY = "org.coldis.configuration.cache.local.record-stats";

	/**
	 * Default of {@value #RECORD_STATS_PROPERTY}.
	 */
	static final boolean RECORD_STATS_DEFAULT = true;

	/**
	 * Property that turns the local cache metrics on (the default) or off. On, each local cache that records stats is
	 * bound to the context's Micrometer meter registry, if there is one, when the cache is created.
	 */
	public static final String METRICS_ENABLED_PROPERTY = "org.coldis.configuration.cache.local.metrics-enabled";

	/**
	 * Default of {@value #METRICS_ENABLED_PROPERTY}.
	 */
	static final boolean METRICS_ENABLED_DEFAULT = true;

	/**
	 * Gets whether the local caches (local cache managers and the local central fallback) record stats.
	 *
	 * @param  environment Environment.
	 * @return             Whether the local caches record stats.
	 */
	static Boolean shouldRecordStats(
			final Environment environment) {
		return environment.getProperty(LocalCacheAutoConfiguration.RECORD_STATS_PROPERTY, Boolean.class, LocalCacheAutoConfiguration.RECORD_STATS_DEFAULT);
	}

	/**
	 * Gets whether the local caches (local cache managers and the local central fallback) publish metrics.
	 *
	 * @param  environment Environment.
	 * @return             Whether the local caches publish metrics.
	 */
	static Boolean shouldPublishMetrics(
			final Environment environment) {
		return environment.getProperty(LocalCacheAutoConfiguration.METRICS_ENABLED_PROPERTY, Boolean.class,
				LocalCacheAutoConfiguration.METRICS_ENABLED_DEFAULT);
	}

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
	 * @param  cacheManagerName      Cache manager bean name (tags its caches' metrics).
	 * @param  expiration            Expiration after write.
	 * @param  maximumSize           Maximum entries per cache (unbounded if null or not positive).
	 * @param  shouldRecordStats     If stats should be recorded.
	 * @param  shouldPublishMetrics  If the caches publish metrics.
	 * @param  meterRegistrySupplier Meter registry supplier, read when each cache is created (null when there is no
	 *                                   single or primary registry).
	 * @return                       Cache manager.
	 */
	static CaffeineCacheManager createCacheManager(
			final String cacheManagerName,
			final Duration expiration,
			final Long maximumSize,
			final Boolean shouldRecordStats,
			final Boolean shouldPublishMetrics,
			final Supplier<MeterRegistry> meterRegistrySupplier) {
		final CaffeineCacheManager cacheManager = new MeteredCaffeineCacheManager(cacheManagerName, Boolean.TRUE.equals(shouldPublishMetrics),
				meterRegistrySupplier);
		Caffeine<Object, Object> caffeine = Caffeine.newBuilder().expireAfterWrite(expiration);
		if ((maximumSize != null) && (maximumSize > 0)) {
			caffeine = caffeine.maximumSize(maximumSize);
		}
		if (Boolean.TRUE.equals(shouldRecordStats)) {
			caffeine = caffeine.recordStats();
		}
		cacheManager.setCaffeine(caffeine);
		return cacheManager;
	}

	/**
	 * Creates a cache manager with the given expiration.
	 *
	 * @param  expiration  Expiration.
	 * @param  environment Environment.
	 * @return             Cache manager.
	 */
	private CaffeineCacheManager getCacheManager(
			final CacheManagerExpiration expiration,
			final Environment environment) {
		return LocalCacheAutoConfiguration.createCacheManager(expiration.getLocalCacheManagerName(), expiration.getExpiration(environment), this.maximumSize,
				LocalCacheAutoConfiguration.shouldRecordStats(environment), LocalCacheAutoConfiguration.shouldPublishMetrics(environment),
				this.meterRegistryProvider::getIfUnique);
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
		this.millisExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.MILLIS, environment);
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
		this.secondsExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.SECONDS, environment);
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
		this.minutesExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.MINUTES, environment);
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
		this.hoursExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.HOURS, environment);
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
		this.dayExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.DAY, environment);
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
		this.daysExpirationLocalCacheManager = this.getCacheManager(CacheManagerExpiration.DAYS, environment);
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
