package org.coldis.library.test.service.cache;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import redis.embedded.RedisServer;

@Configuration
public class TestRedisConfiguration {

	private final RedisServer redisServer;

	public TestRedisConfiguration(final RedisProperties redisProperties) {
		this.redisServer = new RedisServer(redisProperties.getPort());
	}

	@PostConstruct
	public void postConstruct() {
		try {
			this.redisServer.stop();
		}
		catch (final Exception exception) {
		}
		try {
			this.redisServer.start();
		}
		catch (final Exception exception) {
		}
	}

	@PreDestroy
	public void preDestroy() {
		this.redisServer.stop();
	}
}
