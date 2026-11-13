package org.coldis.library.service.warmup;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

/**
 * Guards the beans whose side effects warm-up calls must not have: cache managers drop writes and every
 * {@link RestTemplate} refuses requests, on warm-up threads only. Other threads see the same caches and requests as
 * before.
 */
public class ServiceWarmUpBeanPostProcessor implements BeanPostProcessor {

	/**
	 * Name of the side effect refused for HTTP requests.
	 */
	static final String HTTP_REQUEST = "HTTP request";

	/**
	 * Interceptor that refuses requests from warm-up calls, for templates that already intercept requests.
	 */
	private static final ClientHttpRequestInterceptor HTTP_GUARD = (request, body, execution) -> {
		ServiceWarmUpContext.rejectDuringWarmUp(ServiceWarmUpBeanPostProcessor.HTTP_REQUEST);
		return execution.execute(request, body);
	};

	/**
	 * Makes a template refuse requests from warm-up calls. A template without interceptors gets a guarded request
	 * factory, since adding an interceptor would make it buffer every request body.
	 *
	 * @param restTemplate Template.
	 */
	private static void guard(
			final RestTemplate restTemplate) {
		if (restTemplate.getInterceptors().isEmpty()) {
			if (!(restTemplate.getRequestFactory() instanceof ServiceWarmUpClientHttpRequestFactory)) {
				restTemplate.setRequestFactory(new ServiceWarmUpClientHttpRequestFactory(restTemplate.getRequestFactory()));
			}
		}
		else if (!restTemplate.getInterceptors().contains(ServiceWarmUpBeanPostProcessor.HTTP_GUARD)) {
			final List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
			interceptors.add(0, ServiceWarmUpBeanPostProcessor.HTTP_GUARD);
			restTemplate.setInterceptors(interceptors);
		}
	}

	/**
	 * @see org.springframework.beans.factory.config.BeanPostProcessor#postProcessAfterInitialization(java.lang.Object,
	 *      java.lang.String)
	 */
	@Override
	public Object postProcessAfterInitialization(
			final Object bean,
			final String beanName) {
		Object processedBean = bean;
		if ((bean instanceof final CacheManager cacheManager) && !(bean instanceof ServiceWarmUpCacheManager)) {
			processedBean = new ServiceWarmUpCacheManager(cacheManager);
		}
		else if (bean instanceof final RestTemplate restTemplate) {
			ServiceWarmUpBeanPostProcessor.guard(restTemplate);
		}
		return processedBean;
	}

}
