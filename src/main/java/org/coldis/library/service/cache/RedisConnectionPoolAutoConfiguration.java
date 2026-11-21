package org.coldis.library.service.cache;

import java.time.Duration;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder;

import io.lettuce.core.api.StatefulConnection;

/**
 * Pooled Redis connections for the central cache. On by default ({@value #ENABLED_PROPERTY}=false turns it off), when
 * commons-pool2 is on the class path (without it, the one shared native connection stays, as before).
 * <p>
 * The Redis connection factory is Spring Boot's (Lettuce). With commons-pool2 on the class path Boot already builds a
 * pooling client configuration, but the factory shares one native connection, and every command that is neither
 * blocking nor transactional (all the cache reads and writes) goes through it: the pool is never used. When on, this
 * configuration stops sharing the native connection, so each command borrows a connection from the pool, and sets
 * the pool sizes from the {@value #PROPERTY_PREFIX}* properties (they take precedence over
 * {@code spring.data.redis.lettuce.pool.*}). Each service instance then opens up to {@value #DEFAULT_MAX_TOTAL}
 * (max-total) connections to Redis instead of one.
 */
@Configuration
@ConditionalOnClass(
		value = { LettuceConnectionFactory.class },
		name = "org.apache.commons.pool2.impl.GenericObjectPoolConfig"
)
@ConditionalOnProperty(
		name = RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY,
		havingValue = "true",
		matchIfMissing = true
)
public class RedisConnectionPoolAutoConfiguration {

	/**
	 * Prefix of the pool properties.
	 */
	public static final String PROPERTY_PREFIX = "org.coldis.configuration.cache.central.pool.";

	/**
	 * Property that turns the pool off when false (true or unset: pooled connections; false: one shared connection).
	 */
	public static final String ENABLED_PROPERTY = RedisConnectionPoolAutoConfiguration.PROPERTY_PREFIX + "enabled";

	/**
	 * Maximum connections (borrowed and idle) per service instance.
	 */
	public static final String MAX_TOTAL_PROPERTY = RedisConnectionPoolAutoConfiguration.PROPERTY_PREFIX + "max-total";

	/**
	 * Maximum idle connections kept open.
	 */
	public static final String MAX_IDLE_PROPERTY = RedisConnectionPoolAutoConfiguration.PROPERTY_PREFIX + "max-idle";

	/**
	 * Minimum idle connections kept open (maintained by the pool evictor).
	 */
	public static final String MIN_IDLE_PROPERTY = RedisConnectionPoolAutoConfiguration.PROPERTY_PREFIX + "min-idle";

	/**
	 * How long a command waits for a connection when all are borrowed (a duration: 500ms, 1s, PT1S). Negative waits
	 * indefinitely.
	 */
	public static final String MAX_WAIT_PROPERTY = RedisConnectionPoolAutoConfiguration.PROPERTY_PREFIX + "max-wait";

	/**
	 * Default maximum connections.
	 */
	public static final int DEFAULT_MAX_TOTAL = 8;

	/**
	 * Default maximum idle connections.
	 */
	public static final int DEFAULT_MAX_IDLE = 8;

	/**
	 * Default minimum idle connections.
	 */
	public static final int DEFAULT_MIN_IDLE = 0;

	/**
	 * Default wait for a connection.
	 */
	public static final String DEFAULT_MAX_WAIT = "1s";

	/**
	 * Interval of the pool evictor, which keeps the minimum idle connections open. Only set when min-idle is positive.
	 */
	public static final Duration EVICTION_RUNS_INTERVAL = Duration.ofSeconds(30);

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RedisConnectionPoolAutoConfiguration.class);

	/**
	 * Builds the pool configuration from the properties.
	 *
	 * @param  environment Environment.
	 * @return             The pool configuration.
	 */
	private static GenericObjectPoolConfig<StatefulConnection<?, ?>> buildPoolConfig(
			final Environment environment) {
		final GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
		final Integer maxTotal = environment.getProperty(RedisConnectionPoolAutoConfiguration.MAX_TOTAL_PROPERTY, Integer.class,
				RedisConnectionPoolAutoConfiguration.DEFAULT_MAX_TOTAL);
		final Integer maxIdle = environment.getProperty(RedisConnectionPoolAutoConfiguration.MAX_IDLE_PROPERTY, Integer.class,
				RedisConnectionPoolAutoConfiguration.DEFAULT_MAX_IDLE);
		final Integer minIdle = environment.getProperty(RedisConnectionPoolAutoConfiguration.MIN_IDLE_PROPERTY, Integer.class,
				RedisConnectionPoolAutoConfiguration.DEFAULT_MIN_IDLE);
		final Duration maxWait = DurationStyle.detectAndParse(
				environment.getProperty(RedisConnectionPoolAutoConfiguration.MAX_WAIT_PROPERTY, RedisConnectionPoolAutoConfiguration.DEFAULT_MAX_WAIT));
		poolConfig.setMaxTotal(maxTotal);
		poolConfig.setMaxIdle(maxIdle);
		poolConfig.setMinIdle(minIdle);
		poolConfig.setMaxWait(maxWait);
		if (minIdle > 0) {
			poolConfig.setTimeBetweenEvictionRuns(RedisConnectionPoolAutoConfiguration.EVICTION_RUNS_INTERVAL);
		}
		RedisConnectionPoolAutoConfiguration.LOGGER.debug("Central cache connection pool on: max-total {}, max-idle {}, min-idle {}, max-wait {}.", maxTotal,
				maxIdle, minIdle, maxWait);
		return poolConfig;
	}

	/**
	 * Sets the pool sizes on Boot's pooling client configuration. Fails the start up when Boot did not build a pooling
	 * one (spring.data.redis.lettuce.pool.enabled=false): asking for a pool and silently getting one shared connection
	 * would hide the misconfiguration. Without commons-pool2 on the class path the start up fails earlier, on the
	 * missing class.
	 *
	 * @param  environment Environment.
	 * @return             The customizer.
	 */
	@Bean
	public LettuceClientConfigurationBuilderCustomizer redisConnectionPoolCustomizer(
			final Environment environment) {
		return builder -> {
			if (!(builder instanceof LettucePoolingClientConfigurationBuilder)) {
				throw new IllegalStateException(RedisConnectionPoolAutoConfiguration.ENABLED_PROPERTY
						+ "=true needs a pooling Lettuce client configuration: put org.apache.commons:commons-pool2 on the class path and do not set spring.data.redis.lettuce.pool.enabled=false.");
			}
			((LettucePoolingClientConfigurationBuilder) builder).poolConfig(RedisConnectionPoolAutoConfiguration.buildPoolConfig(environment));
		};
	}

	/**
	 * Stops the pooling Lettuce connection factories from sharing one native connection, so that commands borrow
	 * pooled connections. Runs before the factory initializes. A factory without a pool is left alone: unshared, it
	 * would open a new connection for every command.
	 *
	 * @return The post processor.
	 */
	@Bean
	public static BeanPostProcessor redisConnectionPoolSharingPostProcessor() {
		return new BeanPostProcessor() {

			@Override
			public Object postProcessBeforeInitialization(
					final Object bean,
					final String beanName) {
				if ((bean instanceof final LettuceConnectionFactory connectionFactory)
						&& (connectionFactory.getClientConfiguration() instanceof LettucePoolingClientConfiguration)) {
					connectionFactory.setShareNativeConnection(false);
				}
				return bean;
			}

		};
	}

}
