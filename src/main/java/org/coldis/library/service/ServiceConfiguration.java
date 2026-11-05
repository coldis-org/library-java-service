package org.coldis.library.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.annotation.Order;

/**
 * Service configuration.
 */
@Configuration
@Order(value = -1000)
@PropertySource(
		name = ServiceConfiguration.LIBRARY_DEFAULTS_PROPERTY_SOURCE,
		value = { "classpath:service.properties" },
		ignoreResourceNotFound = true
)
@PropertySource(
		value = { "classpath:integration.properties" },
		ignoreResourceNotFound = true
)
public class ServiceConfiguration {

	/**
	 * Base package.
	 */
	public static final String BASE_PACKAGE = "org.coldis";

	/**
	 * Name of the property source that holds the library defaults
	 * ({@code service.properties}).
	 */
	public static final String LIBRARY_DEFAULTS_PROPERTY_SOURCE = "org.coldis.library.service.defaults";

}
