package org.coldis.library.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.annotation.Order;

/**
 * Service configuration.
 */
@Order
@Configuration
@PropertySource(
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

}
