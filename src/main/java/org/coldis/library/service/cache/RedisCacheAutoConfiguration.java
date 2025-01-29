package org.coldis.library.service.cache;

import java.time.Duration;

import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.serialization.JsonMapperAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;

/**
 * Cache configuration.
 */
@Configuration
@EnableCaching(mode = AdviceMode.ASPECTJ)
@ConditionalOnClass(value = { CacheManager.class, RedisCacheManager.class })
public class RedisCacheAutoConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheAutoConfiguration.class);

	/**
	 * JSON mapper auto configuration.
	 */
	@Autowired
	private JsonMapperAutoConfiguration jsonMapperAutoConfiguration;

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
	 * Default constructor.
	 */
	public RedisCacheAutoConfiguration(final Jackson2ObjectMapperBuilder builder) {
		final ObjectMapper objectMapper = this.jsonMapperAutoConfiguration.genericMapper(builder);
		objectMapper.registerModule(ObjectMapperHelper.getDateTimeModule());
		objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		GenericJackson2JsonRedisSerializer.registerNullValueSerializer(objectMapper, "typeName");
		objectMapper.activateDefaultTypingAsProperty(objectMapper.getPolymorphicTypeValidator(), DefaultTyping.NON_FINAL, "typeName");
		final GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);
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
