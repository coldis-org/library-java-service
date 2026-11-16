package org.coldis.library.service.warmup;

import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor;
import org.springframework.cache.interceptor.CacheAspectSupport;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.transaction.ConfigurableTransactionManager;

/**
 * Attaches every warm-up guard, on warm-up threads only; other threads see the same beans, caches and transactions as
 * before:
 * <ul>
 * <li>the beans with methods in {@link ServiceWarmUpSideEffectAdvisor#GUARDS} ({@code RestTemplate}s, pooled JMS
 * connection factories, statistics, cache clears) get a class-based proxy, a subclass of their own class, that refuses
 * those methods;</li>
 * <li>cache aspects and interceptors send their operations through {@link ServiceWarmUpCacheResolver}, which drops
 * writes, evictions and clears, through a {@link ServiceWarmUpCacheOperationSource}; cache manager beans are not
 * touched;</li>
 * <li>transaction managers get a listener that refuses new read-write transactions.</li>
 * </ul>
 * Registered only when the warm-up is on.
 */
public class ServiceWarmUpBeanPostProcessor extends AbstractAdvisingBeanPostProcessor {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 2803622176390186251L;

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceWarmUpBeanPostProcessor.class);

	/**
	 * Listener that refuses new read-write transactions on warm-up threads.
	 */
	private static final ServiceWarmUpTransactionExecutionListener TRANSACTION_GUARD = new ServiceWarmUpTransactionExecutionListener();

	/**
	 * Final classes with guarded methods, already reported.
	 */
	private final transient Set<Class<?>> reportedFinalClasses = ConcurrentHashMap.newKeySet();

	/**
	 * Constructor.
	 */
	public ServiceWarmUpBeanPostProcessor() {
		super();
		this.advisor = new ServiceWarmUpSideEffectAdvisor();
		// Refuses before any other advice runs, and keeps the proxied beans of their own class.
		this.setBeforeExistingAdvisors(true);
		this.setProxyTargetClass(true);
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
	 * Makes a cache aspect or interceptor send its operations, on warm-up threads, through the warm-up cache resolver.
	 * The cache aspect is a single instance for the whole JVM, so a source already wrapped is kept as is.
	 *
	 * @param cacheAspect Cache aspect or interceptor.
	 */
	private static void guard(
			final CacheAspectSupport cacheAspect) {
		final CacheOperationSource currentSource = cacheAspect.getCacheOperationSource();
		if ((currentSource != null) && !(currentSource instanceof ServiceWarmUpCacheOperationSource)) {
			cacheAspect.setCacheOperationSource(new ServiceWarmUpCacheOperationSource(currentSource, cacheAspect::getCacheResolver));
		}
	}

	/**
	 * Proxies only classes that can be subclassed: a final class with guarded methods is left unguarded, with a warning,
	 * rather than failing the start.
	 *
	 * @see org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor#isEligible(java.lang.Class)
	 */
	@Override
	protected boolean isEligible(
			final Class<?> targetClass) {
		boolean isEligible = super.isEligible(targetClass);
		if (isEligible && Modifier.isFinal(targetClass.getModifiers())) {
			isEligible = false;
			if (this.reportedFinalClasses.add(targetClass)) {
				ServiceWarmUpBeanPostProcessor.LOGGER.warn("Beans of the final class '{}' are not guarded during the service warm-up: warm-up calls must not use them.",
						targetClass.getName());
			}
		}
		return isEligible;
	}

	/**
	 * @see org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor#postProcessAfterInitialization(java.lang.Object,
	 *      java.lang.String)
	 */
	@Override
	public Object postProcessAfterInitialization(
			final Object bean,
			final String beanName) {
		if (bean instanceof final ConfigurableTransactionManager transactionManager) {
			ServiceWarmUpBeanPostProcessor.guard(transactionManager);
		}
		else if (bean instanceof final CacheAspectSupport cacheAspect) {
			ServiceWarmUpBeanPostProcessor.guard(cacheAspect);
		}
		return super.postProcessAfterInitialization(bean, beanName);
	}

}
