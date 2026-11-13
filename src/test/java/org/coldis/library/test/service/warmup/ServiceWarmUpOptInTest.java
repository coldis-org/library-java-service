package org.coldis.library.test.service.warmup;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import org.coldis.library.service.warmup.ServiceWarmUpAutoConfiguration;
import org.coldis.library.service.warmup.ServiceWarmUpBeanPostProcessor;
import org.coldis.library.service.warmup.ServiceWarmUpCacheManager;
import org.coldis.library.service.warmup.ServiceWarmUpClientHttpRequestFactory;
import org.coldis.library.service.warmup.ServiceWarmUpProperties;
import org.coldis.library.service.warmup.ServiceWarmUpResult;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponent;
import org.coldis.library.service.warmup.ServiceWarmUpSideEffectException;
import org.coldis.library.service.warmup.ServiceWarmUpTransactionExecutionListener;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.client.RestTemplate;

/**
 * Tests that the service warm-up registers nothing, and wraps no bean, until a service turns it on, and what it guards
 * when on. No containers: the contexts hold only the warm-up configuration and a few beans a service declares.
 */
public class ServiceWarmUpOptInTest {

	/**
	 * Transaction manager without a resource, to check the guard it gets.
	 */
	public static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

		/**
		 * Serial.
		 */
		private static final long serialVersionUID = 2079338561347105298L;

		/**
		 * @see org.springframework.transaction.support.AbstractPlatformTransactionManager#doGetTransaction()
		 */
		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		/**
		 * @see org.springframework.transaction.support.AbstractPlatformTransactionManager#doBegin(java.lang.Object,
		 *      org.springframework.transaction.TransactionDefinition)
		 */
		@Override
		protected void doBegin(
				final Object transaction,
				final TransactionDefinition definition) {
			// No resource to bind.
		}

		/**
		 * @see org.springframework.transaction.support.AbstractPlatformTransactionManager#doCommit(org.springframework.transaction.support.DefaultTransactionStatus)
		 */
		@Override
		protected void doCommit(
				final DefaultTransactionStatus status) {
			// No resource to commit.
		}

		/**
		 * @see org.springframework.transaction.support.AbstractPlatformTransactionManager#doRollback(org.springframework.transaction.support.DefaultTransactionStatus)
		 */
		@Override
		protected void doRollback(
				final DefaultTransactionStatus status) {
			// No resource to roll back.
		}

	}

	/**
	 * A cache manager, a REST template and a transaction manager, as a service declares them.
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

		/**
		 * Transaction manager.
		 *
		 * @return The transaction manager.
		 */
		@Bean
		public NoResourceTransactionManager transactionManager() {
			return new NoResourceTransactionManager();
		}

	}

	/**
	 * A REST template that already intercepts requests.
	 */
	@Configuration
	public static class InterceptedTemplateBeans {

		/**
		 * Interceptor that passes requests on.
		 */
		public static final ClientHttpRequestInterceptor PASS_THROUGH = (request, body, execution) -> execution.execute(request, body);

		/**
		 * REST template with an interceptor.
		 *
		 * @return The REST template.
		 */
		@Bean
		public RestTemplate interceptedRestTemplate() {
			final RestTemplate restTemplate = new RestTemplate();
			restTemplate.setInterceptors(List.of(InterceptedTemplateBeans.PASS_THROUGH));
			return restTemplate;
		}

	}

	/**
	 * Context with the warm-up configuration and a service's beans.
	 */
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class)).withUserConfiguration(ServiceBeans.class,
					ServiceWarmUpProperties.class, ServiceWarmUpServiceComponent.class, ServiceWarmUpAutoConfiguration.class);

	/**
	 * Creates properties for a warm-up run in these contexts, which do not load the library defaults.
	 *
	 * @return The properties.
	 */
	private static ServiceWarmUpProperties createProperties() {
		final ServiceWarmUpProperties properties = new ServiceWarmUpProperties();
		properties.setEnabled(true);
		properties.setTimeLimit(Duration.ofSeconds(30));
		properties.setMinimumCalls(Integer.MAX_VALUE);
		properties.setParallelism(1);
		properties.setIdleCompilationMillisPerSecond(0L);
		properties.setIdleCompilationPeriod(Duration.ofSeconds(1));
		properties.setMaximumConsecutiveErrors(20);
		return properties;
	}

	/**
	 * Tests that the value the library ships keeps the warm-up off: no warm-up service, no guard and no wrapped bean.
	 *
	 * @throws Exception If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("The shipped default keeps the warm-up off: nothing registered and no bean wrapped")
	public void testOffByDefault() throws Exception {
		final Properties libraryDefaults = PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"));
		final String shippedValue = libraryDefaults.getProperty(ServiceWarmUpProperties.ENABLED_PROPERTY);
		Assertions.assertEquals("false", shippedValue);
		this.contextRunner.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=" + shippedValue).run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(0, context.getBeanNamesForType(ServiceWarmUpServiceComponent.class).length);
			Assertions.assertEquals(0, context.getBeanNamesForType(ServiceWarmUpBeanPostProcessor.class).length);
			Assertions.assertInstanceOf(ConcurrentMapCacheManager.class, context.getBean(CacheManager.class));
			Assertions.assertFalse(context.getBean(RestTemplate.class).getRequestFactory() instanceof ServiceWarmUpClientHttpRequestFactory);
			Assertions.assertTrue(context.getBean(ConfigurableTransactionManager.class).getTransactionExecutionListeners().isEmpty());
		});
	}

	/**
	 * Tests that, with the warm-up on, the warm-up service exists and caches, templates and transaction managers are
	 * guarded.
	 */
	@Test
	@DisplayName("With the warm-up on, the warm-up service exists and caches, templates and transaction managers are guarded")
	public void testOnRegistersServiceAndGuards() {
		this.contextRunner.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(1, context.getBeanNamesForType(ServiceWarmUpServiceComponent.class).length);
			Assertions.assertInstanceOf(ServiceWarmUpCacheManager.class, context.getBean(CacheManager.class));
			Assertions.assertInstanceOf(ServiceWarmUpClientHttpRequestFactory.class, context.getBean(RestTemplate.class).getRequestFactory());
			Assertions.assertTrue(context.getBean(ConfigurableTransactionManager.class).getTransactionExecutionListeners().stream()
					.anyMatch(listener -> listener instanceof ServiceWarmUpTransactionExecutionListener));
		});
	}

	/**
	 * Tests that a template that already intercepts requests gets the guard as its first interceptor, which refuses a
	 * request from a warm-up call before it is made.
	 */
	@Test
	@DisplayName("A template that already intercepts requests refuses warm-up requests through a first interceptor")
	public void testInterceptedTemplateGuarded() {
		this.contextRunner.withUserConfiguration(InterceptedTemplateBeans.class).withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true")
				.run(context -> {
					Assertions.assertNull(context.getStartupFailure());
					final RestTemplate restTemplate = context.getBean("interceptedRestTemplate", RestTemplate.class);
					Assertions.assertEquals(2, restTemplate.getInterceptors().size());
					Assertions.assertSame(InterceptedTemplateBeans.PASS_THROUGH, restTemplate.getInterceptors().get(1));
					final List<ServiceWarmUpResult> results = context.getBean(ServiceWarmUpServiceComponent.class).warmUp(List.of(
							new ServiceWarmUpTestRoutine<>(() -> List.of("http://localhost:9/"), input -> restTemplate.getForObject(input, String.class))),
							ServiceWarmUpOptInTest.createProperties());
					Assertions.assertEquals(1L, results.get(0).getFailedCallCount());
					Assertions.assertInstanceOf(ServiceWarmUpSideEffectException.class, results.get(0).getFirstError());
				});
	}

}
