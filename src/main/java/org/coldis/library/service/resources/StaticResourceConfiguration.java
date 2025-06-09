package org.coldis.library.service.resources;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StaticResourceConfiguration implements WebMvcConfigurer {

	/**
	 * Environment variable to be used in the static resources.
	 */
	@Value("${environment}")
	private String environment;

	/**
	 * Duration for which the static resources should be cached.
	 */
	@Value("${org.coldis.configuration.resources.static.cache:30d}")
	private Duration cacheDuration;

	/** Gets if the environment is production. */
	public boolean isProductionEnvironment() {
		return "production".equalsIgnoreCase(this.environment);
	}

	/**
	 * Adds resource handlers for static resources.
	 *
	 * @param registry Resource handler registry.
	 */
	@Override
	public void addResourceHandlers(
			final ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**").addResourceLocations("classpath:/static/", "classpath:/public/")
				.setCacheControl(CacheControl.maxAge(this.cacheDuration).cachePublic()).resourceChain(this.isProductionEnvironment());
	}
	
	
	

}
