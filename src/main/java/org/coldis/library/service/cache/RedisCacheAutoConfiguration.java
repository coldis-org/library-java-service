package org.coldis.library.service.cache;


import org.coldis.library.service.serialization.JsonMapperAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

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

/**
 * Cache configuration.
 */
@Configuration
@EnableCaching(mode = AdviceMode.ASPECTJ)
@DependsOn(value = { "jsonMapperAutoConfiguration" })
@ConditionalOnClass(value = { CacheManager.class, RedisCacheManager.class })
public class RedisCacheAutoConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheAutoConfiguration.class);

	/** Key converter. */
	private final Converter<Object, String> keyConverter;

	/**
	 * Serialization pair.
	 */
	private final SerializationPair<Object> serializationPair;

	/**
	 * Cache manager.
	 */
	private RedisCacheManager millisExpirationCentralCacheManager;

	/**
	 * Cache manager.
	 */
	private RedisCacheManager secondsExpirationCentralCacheManager;

	/**
	 * Cache manager.
	 */
	private RedisCacheManager minutesExpirationCentralCacheManager;

	/**
	 * Cache manager.
	 */
	private RedisCacheManager hoursExpirationCentralCacheManager;

	/**
	 * Cache manager.
	 */
	private RedisCacheManager dayExpirationCentralCacheManager;

	/**
	 * Cache manager.
	 */
	private RedisCacheManager daysExpirationCentralCacheManager;

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

	/**
	 * Default constructor.
	 *
	 * @throws JsonProcessingException
	 */
	public RedisCacheAutoConfiguration(final JsonMapperAutoConfiguration jsonMapperAutoConfiguration, final Jackson2ObjectMapperBuilder builder)
			throws JsonProcessingException {
		final ObjectMapper objectMapper = jsonMapperAutoConfiguration.genericMapper(builder);
		objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
		objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
		objectMapper.setAnnotationIntrospector(new IgnoreTypeInfoIntrospector());
		this.keyConverter = new HashCacheKeyConverter(objectMapper, "SHA-256");
		final GenericJackson2JsonRedisSerializer serializer = GenericJackson2JsonRedisSerializer.builder().objectMapper(objectMapper).defaultTyping(true)
				.registerNullValueSerializer(false).build();
		this.serializationPair = SerializationPair.fromSerializer(serializer);
	}

	/**
	 * Creates a central cache manager: single-flight ({@link SingleFlightRedisCacheManager}) unless
	 * {@value SingleFlightCacheLoader#CENTRAL_ENABLED_PROPERTY} is false, with the cross-instance lock if
	 * {@value SingleFlightCacheLoader#CENTRAL_LOCK_ENABLED_PROPERTY} is true.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  cacheConfiguration     Cache configuration.
	 * @param  environment            Environment.
	 * @return                        The cache manager.
	 */
	private RedisCacheManager createCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final RedisCacheConfiguration cacheConfiguration,
			final Environment environment) {
		final RedisCacheManager cacheManager;
		if (SingleFlightCacheLoader.isCentralEnabled(environment)) {
			cacheManager = new SingleFlightRedisCacheManager(RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory), cacheConfiguration,
					SingleFlightCacheLoader.getWaitTimeout(environment),
					SingleFlightCacheLoader.isCentralLockEnabled(environment) ? redisConnectionFactory : null,
					SingleFlightCacheLoader.getCentralLockTimeout(environment), SingleFlightCacheLoader.getCentralLockPollInterval(environment));
		}
		else {
			cacheManager = RedisCacheManager.builder(redisConnectionFactory).cacheDefaults(cacheConfiguration).build();
		}
		return cacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  environment            Environment.
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager millisExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final Environment environment) {
		final RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(CacheManagerExpiration.MILLIS.getExpiration(environment))
				.serializeValuesWith(this.serializationPair).computePrefixWith(CacheKeyPrefix.simple());
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(SimpleKey.class, String.class, this.keyConverter));
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(this.keyConverter));
		this.millisExpirationCentralCacheManager = this.createCacheManager(redisConnectionFactory, cacheConfiguration, environment);
		return this.millisExpirationCentralCacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  environment            Environment.
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager secondsExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final Environment environment) {
		final RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(CacheManagerExpiration.SECONDS.getExpiration(environment))
				.serializeValuesWith(this.serializationPair).computePrefixWith(CacheKeyPrefix.simple());
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(SimpleKey.class, String.class, this.keyConverter));
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(this.keyConverter));
		this.secondsExpirationCentralCacheManager = this.createCacheManager(redisConnectionFactory, cacheConfiguration, environment);
		return this.secondsExpirationCentralCacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  environment            Environment.
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager minutesExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final Environment environment) {
		final RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(CacheManagerExpiration.MINUTES.getExpiration(environment))
				.serializeValuesWith(this.serializationPair).computePrefixWith(CacheKeyPrefix.simple());
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(SimpleKey.class, String.class, this.keyConverter));
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(this.keyConverter));
		this.minutesExpirationCentralCacheManager = this.createCacheManager(redisConnectionFactory, cacheConfiguration, environment);
		return this.minutesExpirationCentralCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  environment            Environment.
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager hoursExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final Environment environment) {
		final RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(CacheManagerExpiration.HOURS.getExpiration(environment))
				.serializeValuesWith(this.serializationPair).computePrefixWith(CacheKeyPrefix.simple());
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(SimpleKey.class, String.class, this.keyConverter));
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(this.keyConverter));
		this.hoursExpirationCentralCacheManager = this.createCacheManager(redisConnectionFactory, cacheConfiguration, environment);
		return this.hoursExpirationCentralCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  environment            Environment.
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager dayExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final Environment environment) {
		final RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(CacheManagerExpiration.DAY.getExpiration(environment))
				.serializeValuesWith(this.serializationPair).computePrefixWith(CacheKeyPrefix.simple());
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(SimpleKey.class, String.class, this.keyConverter));
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(this.keyConverter));
		this.dayExpirationCentralCacheManager = this.createCacheManager(redisConnectionFactory, cacheConfiguration, environment);
		return this.dayExpirationCentralCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @param  redisConnectionFactory Redis connection factory.
	 * @param  environment            Environment.
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager daysExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			final Environment environment) {
		final RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig().entryTtl(CacheManagerExpiration.DAYS.getExpiration(environment))
				.serializeValuesWith(this.serializationPair).computePrefixWith(CacheKeyPrefix.simple());
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(SimpleKey.class, String.class, this.keyConverter));
		cacheConfiguration.configureKeyConverters(conversionService -> conversionService.addConverter(this.keyConverter));
		this.daysExpirationCentralCacheManager = this.createCacheManager(redisConnectionFactory, cacheConfiguration, environment);
		return this.daysExpirationCentralCacheManager;
	}

	/**
	 * Evict all caches.
	 */
	public void evictAll() {
		this.millisExpirationCentralCacheManager.getCacheNames().stream().forEach(name -> this.millisExpirationCentralCacheManager.getCache(name).clear());
		this.secondsExpirationCentralCacheManager.getCacheNames().stream().forEach(name -> this.secondsExpirationCentralCacheManager.getCache(name).clear());
		this.minutesExpirationCentralCacheManager.getCacheNames().stream().forEach(name -> this.minutesExpirationCentralCacheManager.getCache(name).clear());
		this.hoursExpirationCentralCacheManager.getCacheNames().stream().forEach(name -> this.hoursExpirationCentralCacheManager.getCache(name).clear());
		this.dayExpirationCentralCacheManager.getCacheNames().stream().forEach(name -> this.dayExpirationCentralCacheManager.getCache(name).clear());
		this.daysExpirationCentralCacheManager.getCacheNames().stream().forEach(name -> this.daysExpirationCentralCacheManager.getCache(name).clear());
	}

}
