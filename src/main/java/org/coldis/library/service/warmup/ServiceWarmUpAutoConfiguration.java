package org.coldis.library.service.warmup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Service warm-up configuration. Everything here, apart from the warning about warm-ups that do not run, is registered
 * only when the service turns the warm-up on ({@value ServiceWarmUpProperties#ENABLED_PROPERTY}): the health check
 * filter, the guards on caches and {@code RestTemplate} requests, and the read-only transactions. The JMS connection
 * and statistics guards live where those side effects happen, and do nothing while no warm-up runs.
 */
@Configuration
public class ServiceWarmUpAutoConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceWarmUpAutoConfiguration.class);

	/**
	 * Makes every transaction read-only on warm-up threads, by wrapping the attribute source of the transaction aspect.
	 * The aspect is a single instance for the whole JVM, so a source already wrapped is kept as is.
	 */
	@Configuration
	@ConditionalOnClass(value = AnnotationTransactionAspect.class)
	@ConditionalOnProperty(
			name = ServiceWarmUpProperties.ENABLED_PROPERTY,
			havingValue = "true"
	)
	public static class ServiceWarmUpTransactionConfiguration {

		/**
		 * Constructor.
		 *
		 * @param transactionAspectProvider Transaction aspect provider.
		 */
		public ServiceWarmUpTransactionConfiguration(final ObjectProvider<AnnotationTransactionAspect> transactionAspectProvider) {
			super();
			final AnnotationTransactionAspect transactionAspect = transactionAspectProvider.getIfAvailable();
			if (transactionAspect != null) {
				final TransactionAttributeSource currentSource = transactionAspect.getTransactionAttributeSource();
				if ((currentSource != null) && !(currentSource instanceof ServiceWarmUpTransactionAttributeSource)) {
					transactionAspect.setTransactionAttributeSource(new ServiceWarmUpTransactionAttributeSource(currentSource));
				}
			}
		}

	}

	/**
	 * Health check filter, for servlet services whose health check is on.
	 */
	@Configuration
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	@ConditionalOnProperty(
			name = "org.coldis.configuration.health-check-enabled",
			matchIfMissing = true
	)
	public static class ServiceWarmUpHealthCheckConfiguration {

		/**
		 * Answers the health check with 503 while the service warms up.
		 *
		 * @param  serviceWarmUpServiceComponent Warm-up service.
		 * @param  healthCheckPath               Health check path.
		 * @return                               The filter registration.
		 */
		@Bean
		@ConditionalOnProperty(
				name = ServiceWarmUpProperties.ENABLED_PROPERTY,
				havingValue = "true"
		)
		public FilterRegistrationBean<ServiceWarmUpHealthCheckFilter> serviceWarmUpHealthCheckFilter(
				final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent,
				@Value("${org.coldis.configuration.health-check:/health-check}") final String healthCheckPath) {
			final FilterRegistrationBean<ServiceWarmUpHealthCheckFilter> registration = new FilterRegistrationBean<>(
					new ServiceWarmUpHealthCheckFilter(serviceWarmUpServiceComponent));
			registration.addUrlPatterns(healthCheckPath);
			registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
			return registration;
		}

	}

	/**
	 * Warns when the service declares warm-ups but the warm-up is off, so they do not run.
	 */
	public static class ServiceWarmUpDisabledWarning implements ApplicationListener<ApplicationReadyEvent> {

		/**
		 * Bean factory.
		 */
		private final ListableBeanFactory beanFactory;

		/**
		 * Constructor.
		 *
		 * @param beanFactory Bean factory.
		 */
		public ServiceWarmUpDisabledWarning(final ListableBeanFactory beanFactory) {
			super();
			this.beanFactory = beanFactory;
		}

		/**
		 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
		 */
		@Override
		public void onApplicationEvent(
				final ApplicationReadyEvent event) {
			final String[] warmUpNames = this.beanFactory.getBeanNamesForType(ServiceWarmUp.class, true, false);
			if (warmUpNames.length > 0) {
				ServiceWarmUpAutoConfiguration.LOGGER.warn("The service declares {} warm-up(s), which do not run because '{}' is not true.", warmUpNames.length,
						ServiceWarmUpProperties.ENABLED_PROPERTY);
			}
		}

	}

	/**
	 * Guards caches and {@code RestTemplate} requests during the warm-up.
	 *
	 * @return The bean post processor.
	 */
	@Bean
	@ConditionalOnProperty(
			name = ServiceWarmUpProperties.ENABLED_PROPERTY,
			havingValue = "true"
	)
	public static ServiceWarmUpBeanPostProcessor serviceWarmUpBeanPostProcessor() {
		return new ServiceWarmUpBeanPostProcessor();
	}

	/**
	 * Warns when the service declares warm-ups but the warm-up is off.
	 *
	 * @param  beanFactory Bean factory.
	 * @return             The warning listener.
	 */
	@Bean
	@ConditionalOnProperty(
			name = ServiceWarmUpProperties.ENABLED_PROPERTY,
			havingValue = "false",
			matchIfMissing = true
	)
	public ServiceWarmUpDisabledWarning serviceWarmUpDisabledWarning(
			final ListableBeanFactory beanFactory) {
		return new ServiceWarmUpDisabledWarning(beanFactory);
	}

}
