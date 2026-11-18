package org.coldis.library.service.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Cache manager fallback configuration: see {@link CacheManagerFallbackRegistrar}. Enables caching itself, so the local
 * central fallback caches even with local caching off and no Redis (no other configuration enabling it).
 */
@Configuration
@EnableCaching(mode = AdviceMode.ASPECTJ)
public class CacheManagerFallbackAutoConfiguration {

	/**
	 * Registers the fallback cache managers. Static, so the registrar runs before this configuration is created.
	 *
	 * @param  environment Environment.
	 * @return             The fallback cache manager registrar.
	 */
	@Bean
	public static CacheManagerFallbackRegistrar cacheManagerFallbackRegistrar(
			final Environment environment) {
		return new CacheManagerFallbackRegistrar(environment);
	}

}
