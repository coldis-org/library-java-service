package org.coldis.library.service.property;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringValueResolver;

/**
 * Property resolver auto-configuration.
 */
@Configuration
public class PropertyResolverAutoConfiguration {

	/**
	 * Embedded value resolver.
	 *
	 * @param  beanFactory Bean factory.
	 * @return             Embedded value resolver.
	 */
	@Bean
	public StringValueResolver embeddedValueResolver(
			final ConfigurableBeanFactory beanFactory) {
		return new EmbeddedValueResolver(beanFactory);
	}

}
