package org.coldis.library.service.warmup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
 * filter and, unless {@value ServiceWarmUpProperties#READ_ONLY_TRANSACTION_PROPERTY} is false, the database guard (the
 * read-only {@code @Transactional} methods and the transaction managers' listener). Nothing else is guarded, and no
 * bean is replaced or proxied.
 */
@Configuration
public class ServiceWarmUpAutoConfiguration {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceWarmUpAutoConfiguration.class);

	/**
	 * Condition of the database guard: the warm-up is on, and so is its read-only transaction (the default).
	 */
	static final String READ_ONLY_TRANSACTION_CONDITION = "${" + ServiceWarmUpProperties.ENABLED_PROPERTY + ":false} and ${"
			+ ServiceWarmUpProperties.READ_ONLY_TRANSACTION_PROPERTY + ":true}";

	/**
	 * Makes the service's {@code @Transactional} methods read-only on warm-up threads, by wrapping the attribute source
	 * of the transaction aspect; other new read-write transactions are refused by the transaction managers' listener.
	 * The aspect is a single instance for the whole JVM, so a source already wrapped is kept as is.
	 */
	@Configuration
	@ConditionalOnClass(value = AnnotationTransactionAspect.class)
	@ConditionalOnExpression(ServiceWarmUpAutoConfiguration.READ_ONLY_TRANSACTION_CONDITION)
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
	 * Health check filter, for servlet services whose health check is on. A filter, rather than a change to
	 * {@code HealthCheckService}, answers before the other filters and without touching the repository, and leaves the
	 * health check's API as it is; so its path and switch mirror the health check's own properties, and must change with
	 * them. The liveness scope passes through: a warming instance is alive. Publishing the readiness state instead was
	 * not chosen: Spring Boot publishes accepting traffic right after the ready event, which would race with the
	 * warm-up, readiness can be turned off in the health check, and opening it after the warm-up could reopen an
	 * instance that started shutting down.
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
		 * Warm-up beans, read when the application is ready.
		 */
		private final ObjectProvider<ServiceWarmUp<?>> warmUpProvider;

		/**
		 * Constructor.
		 *
		 * @param warmUpProvider Warm-up beans, read when the application is ready.
		 */
		public ServiceWarmUpDisabledWarning(final ObjectProvider<ServiceWarmUp<?>> warmUpProvider) {
			super();
			this.warmUpProvider = warmUpProvider;
		}

		/**
		 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
		 */
		@Override
		public void onApplicationEvent(
				final ApplicationReadyEvent event) {
			final long warmUpCount = this.warmUpProvider.stream().count();
			if (warmUpCount > 0) {
				ServiceWarmUpAutoConfiguration.LOGGER.warn("The service declares {} warm-up(s), which do not run because '{}' is not true.", warmUpCount,
						ServiceWarmUpProperties.ENABLED_PROPERTY);
			}
		}

	}

	/**
	 * Makes transaction managers refuse new read-write transactions on warm-up threads.
	 *
	 * @return The bean post processor.
	 */
	@Bean
	@ConditionalOnExpression(ServiceWarmUpAutoConfiguration.READ_ONLY_TRANSACTION_CONDITION)
	public static ServiceWarmUpBeanPostProcessor serviceWarmUpBeanPostProcessor() {
		return new ServiceWarmUpBeanPostProcessor();
	}

	/**
	 * Warns when the service declares warm-ups but the warm-up is off.
	 *
	 * @param  warmUpProvider Warm-up beans, read when the application is ready.
	 * @return                The warning listener.
	 */
	@Bean
	@ConditionalOnProperty(
			name = ServiceWarmUpProperties.ENABLED_PROPERTY,
			havingValue = "false",
			matchIfMissing = true
	)
	public ServiceWarmUpDisabledWarning serviceWarmUpDisabledWarning(
			final ObjectProvider<ServiceWarmUp<?>> warmUpProvider) {
		return new ServiceWarmUpDisabledWarning(warmUpProvider);
	}

}
