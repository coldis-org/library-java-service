package org.coldis.library.service.cache;

import java.time.Duration;
import java.util.Set;

import org.coldis.library.model.verification.Verification;
import org.coldis.library.service.serialization.JsonMapperAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
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
		objectMapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
		objectMapper.setAnnotationIntrospector(new IgnoreTypeInfoIntrospector());
		final GenericJackson2JsonRedisSerializer serializer = GenericJackson2JsonRedisSerializer.builder().objectMapper(objectMapper).defaultTyping(true)
				.registerNullValueSerializer(false).build();
		this.serializationPair = SerializationPair.fromSerializer(serializer);
	}

	/**
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager millisExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			@Value(value = "${org.coldis.configuration.cache.millis-expiration:3100}")
			final Long expiration) {
		this.millisExpirationCentralCacheManager = RedisCacheManager.builder(redisConnectionFactory)
				.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMillis(expiration)).serializeValuesWith(this.serializationPair))
				.build();
		return this.millisExpirationCentralCacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager secondsExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			@Value(value = "${org.coldis.configuration.cache.seconds-expiration:23}")
			final Long expiration) {
		this.secondsExpirationCentralCacheManager = RedisCacheManager.builder(redisConnectionFactory)
				.cacheDefaults(
						RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofSeconds(expiration)).serializeValuesWith(this.serializationPair))
				.build();
		return this.secondsExpirationCentralCacheManager;
	}

	/**
	 * Short lived cache.
	 *
	 * @return Short lived cache.
	 */
	@Bean
	public CacheManager minutesExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			@Value(value = "${org.coldis.configuration.cache.minutes-expiration:11}")
			final Long expiration) {
		this.minutesExpirationCentralCacheManager = RedisCacheManager.builder(redisConnectionFactory)
				.cacheDefaults(
						RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(expiration)).serializeValuesWith(this.serializationPair))
				.build();
		return this.minutesExpirationCentralCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager hoursExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			@Value(value = "${org.coldis.configuration.cache.hours-expiration:3}")
			final Long expiration) {
		this.hoursExpirationCentralCacheManager = RedisCacheManager.builder(redisConnectionFactory)
				.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(expiration)).serializeValuesWith(this.serializationPair))
				.build();
		return this.hoursExpirationCentralCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager dayExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			@Value(value = "${org.coldis.configuration.cache.day-expiration:1}")
			final Long expiration) {
		this.dayExpirationCentralCacheManager = RedisCacheManager.builder(redisConnectionFactory)
				.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(expiration)).serializeValuesWith(this.serializationPair))
				.build();
		return this.dayExpirationCentralCacheManager;
	}

	/**
	 * Long lived cache.
	 *
	 * @return Long lived cache.
	 */
	@Bean
	public CacheManager daysExpirationCentralCacheManager(
			final RedisConnectionFactory redisConnectionFactory,
			@Value(value = "${org.coldis.configuration.cache.days-expiration:5}")
			final Long expiration) {
		this.daysExpirationCentralCacheManager = RedisCacheManager.builder(redisConnectionFactory)
				.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(expiration)).serializeValuesWith(this.serializationPair))
				.build();
		return this.daysExpirationCentralCacheManager;
	}

	/**
	 * Evict all caches.
	 */
	public void evictAll() {
		this.millisExpirationCentralCacheManager.getCacheNames().parallelStream()
				.forEach(name -> this.millisExpirationCentralCacheManager.getCache(name).clear());
		this.secondsExpirationCentralCacheManager.getCacheNames().parallelStream()
				.forEach(name -> this.secondsExpirationCentralCacheManager.getCache(name).clear());
		this.minutesExpirationCentralCacheManager.getCacheNames().parallelStream()
				.forEach(name -> this.minutesExpirationCentralCacheManager.getCache(name).clear());
		this.hoursExpirationCentralCacheManager.getCacheNames().parallelStream()
				.forEach(name -> this.hoursExpirationCentralCacheManager.getCache(name).clear());
		this.dayExpirationCentralCacheManager.getCacheNames().parallelStream().forEach(name -> this.dayExpirationCentralCacheManager.getCache(name).clear());
		this.daysExpirationCentralCacheManager.getCacheNames().parallelStream().forEach(name -> this.daysExpirationCentralCacheManager.getCache(name).clear());
	}

}
