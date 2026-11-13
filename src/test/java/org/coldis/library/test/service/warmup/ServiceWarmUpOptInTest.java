package org.coldis.library.test.service.warmup;

import org.coldis.library.service.warmup.ServiceWarmUpAutoConfiguration;
import org.coldis.library.service.warmup.ServiceWarmUpBeanPostProcessor;
import org.coldis.library.service.warmup.ServiceWarmUpCacheManager;
import org.coldis.library.service.warmup.ServiceWarmUpClientHttpRequestFactory;
import org.coldis.library.service.warmup.ServiceWarmUpProperties;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Tests that the service warm-up registers nothing, and wraps no bean, until a service turns it on.
 */
public class ServiceWarmUpOptInTest {

	/**
	 * A cache manager and a REST template, as a service declares them.
	 */
	@Configuration
	public static class ServiceBeans {

		/**
		 * Cache manager.
		 *
		 * @return The cache manager.
		 */
		@Bean
		public CacheManager cacheManager() {
			return new ConcurrentMapCacheManager();
		}

		/**
		 * REST template.
		 *
		 * @return The REST template.
		 */
		@Bean
		public RestTemplate restTemplate() {
			return new RestTemplate();
		}

	}

	/**
	 * Context with the warm-up configuration and a service's beans, without containers.
	 */
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class)).withUserConfiguration(ServiceBeans.class,
					ServiceWarmUpProperties.class, ServiceWarmUpServiceComponent.class, ServiceWarmUpAutoConfiguration.class);

	/**
	 * Tests that, with the warm-up off (the default), there is no warm-up service, no guard and no wrapped bean.
	 */
	@Test
	public void testOffByDefault() {
		this.contextRunner.run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(0, context.getBeanNamesForType(ServiceWarmUpServiceComponent.class).length);
			Assertions.assertEquals(0, context.getBeanNamesForType(ServiceWarmUpBeanPostProcessor.class).length);
			Assertions.assertInstanceOf(ConcurrentMapCacheManager.class, context.getBean(CacheManager.class));
			Assertions.assertFalse(context.getBean(RestTemplate.class).getRequestFactory() instanceof ServiceWarmUpClientHttpRequestFactory);
		});
	}

	/**
	 * Tests that, with the warm-up on, the warm-up service exists and caches and templates are guarded.
	 */
	@Test
	public void testOn() {
		this.contextRunner.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(1, context.getBeanNamesForType(ServiceWarmUpServiceComponent.class).length);
			Assertions.assertInstanceOf(ServiceWarmUpCacheManager.class, context.getBean(CacheManager.class));
			Assertions.assertInstanceOf(ServiceWarmUpClientHttpRequestFactory.class, context.getBean(RestTemplate.class).getRequestFactory());
		});
	}

}
