package org.coldis.library.service.warmup;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.ConfigurableTransactionManager;

/**
 * Makes every transaction manager refuse new read-write transactions on warm-up threads, through a listener added to
 * the manager itself: no bean is replaced or proxied. Other threads see the same transactions as before. Registered
 * only when the warm-up and its read-only transaction are on.
 */
public class ServiceWarmUpBeanPostProcessor implements BeanPostProcessor {

	/**
	 * Listener that refuses new read-write transactions on warm-up threads.
	 */
	private static final ServiceWarmUpTransactionExecutionListener TRANSACTION_GUARD = new ServiceWarmUpTransactionExecutionListener();

	/**
	 * Constructor.
	 */
	public ServiceWarmUpBeanPostProcessor() {
		super();
	}

	/**
	 * @see org.springframework.beans.factory.config.BeanPostProcessor#postProcessAfterInitialization(java.lang.Object,
	 *      java.lang.String)
	 */
	@Override
	public Object postProcessAfterInitialization(
			final Object bean,
			final String beanName) {
		if ((bean instanceof final ConfigurableTransactionManager transactionManager)
				&& !transactionManager.getTransactionExecutionListeners().contains(ServiceWarmUpBeanPostProcessor.TRANSACTION_GUARD)) {
			transactionManager.addListener(ServiceWarmUpBeanPostProcessor.TRANSACTION_GUARD);
		}
		return bean;
	}

}
