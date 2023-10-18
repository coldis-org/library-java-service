package org.coldis.library.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * Service configuration.
 */
@Configuration
@PropertySource(
		value = { "classpath:service.properties" },
		ignoreResourceNotFound = true
)
public class ServiceConfiguration {

}
