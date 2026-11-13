package org.coldis.library.service.warmup;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.web.client.RestTemplate;

/**
 * Guards the beans whose side effects warm-up calls must not have, on warm-up threads only: cache managers drop
 * writes, every {@link RestTemplate} refuses requests, and every transaction manager refuses new read-write
 * transactions. Other threads see the same caches, requests and transactions as before.
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
	 * Listener that refuses new read-write transactions on warm-up threads.
	 */
	private static final ServiceWarmUpTransactionExecutionListener TRANSACTION_GUARD = new ServiceWarmUpTransactionExecutionListener();

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
	 * Makes a transaction manager refuse new read-write transactions on warm-up threads.
	 *
	 * @param transactionManager Transaction manager.
	 */
	private static void guard(
			final ConfigurableTransactionManager transactionManager) {
		if (!transactionManager.getTransactionExecutionListeners().contains(ServiceWarmUpBeanPostProcessor.TRANSACTION_GUARD)) {
			transactionManager.addListener(ServiceWarmUpBeanPostProcessor.TRANSACTION_GUARD);
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
		else if (bean instanceof final ConfigurableTransactionManager transactionManager) {
			ServiceWarmUpBeanPostProcessor.guard(transactionManager);
		}
		return processedBean;
	}

}
