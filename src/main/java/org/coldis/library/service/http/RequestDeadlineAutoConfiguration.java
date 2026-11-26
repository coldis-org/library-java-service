package org.coldis.library.service.http;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Request deadline configuration (see {@link RequestDeadlineFilter}), for servlet
 * services. The filter is always registered; it is on unless
 * {@value #ENABLED_PROPERTY} is false (on by default), and can also be switched
 * at runtime ({@link RequestDeadlineFilter#setEnabled(boolean)}).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestDeadlineAutoConfiguration {

	/**
	 * Switch of the request deadline.
	 */
	public static final String ENABLED_PROPERTY = "org.coldis.configuration.request-deadline.enabled";

	/**
	 * Order of the filter: right after the server request observation
	 * ({@code HIGHEST_PRECEDENCE + 1}), so a request answered 504 is still
	 * measured, and before every other filter (security included) and the
	 * controllers.
	 */
	public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

	/**
	 * Registers the request deadline filter on every path (it skips the health
	 * check itself).
	 *
	 * @param  isEnabled             If the request deadline is on.
	 * @param  healthCheckPath       Health check path.
	 * @param  meterRegistryProvider Meter registry provider (none without
	 *                                   Micrometer).
	 * @return                       The filter registration.
	 */
	@Bean
	public FilterRegistrationBean<RequestDeadlineFilter> requestDeadlineFilter(
			@Value("${" + RequestDeadlineAutoConfiguration.ENABLED_PROPERTY + ":true}")
			final Boolean isEnabled,
			@Value("${org.coldis.configuration.health-check:/health-check}")
			final String healthCheckPath,
			final ObjectProvider<MeterRegistry> meterRegistryProvider) {
		final FilterRegistrationBean<RequestDeadlineFilter> registration = new FilterRegistrationBean<>(
				new RequestDeadlineFilter(Boolean.TRUE.equals(isEnabled), healthCheckPath, meterRegistryProvider.getIfAvailable()));
		registration.setOrder(RequestDeadlineAutoConfiguration.FILTER_ORDER);
		return registration;
	}

}
