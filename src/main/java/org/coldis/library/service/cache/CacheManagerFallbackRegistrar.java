package org.coldis.library.service.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers a cache manager under each library cache manager name that has none once every configuration is read, so
 * a method cached with a manager the service does not configure is called through instead of failing. The cache aspect
 * looks the manager up by name on each call: without this, a missing central (Redis) manager fails the call with
 * {@link org.springframework.beans.factory.NoSuchBeanDefinitionException}, and with local caching off and Redis on the
 * service fails to start (no default cache manager among the six central ones). Names that have a bean (Redis or local
 * configured) are left as they are.
 */
public class CacheManagerFallbackRegistrar implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware, Ordered {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(CacheManagerFallbackRegistrar.class);

	/**
	 * Property that chooses what the central cache manager names fall back to.
	 */
	public static final String CENTRAL_FALLBACK_PROPERTY = "org.coldis.configuration.cache.central.fallback";

	/**
	 * Environment.
	 */
	private final Environment environment;

	/**
	 * Bean factory (looks the meter registry up when a local fallback cache is created).
	 */
	private BeanFactory beanFactory;

	/**
	 * Constructor.
	 *
	 * @param environment Environment.
	 */
	public CacheManagerFallbackRegistrar(final Environment environment) {
		this.environment = environment;
	}

	/**
	 * Gets what the central cache manager names fall back to.
	 *
	 * @return What the central cache manager names fall back to.
	 */
	private CentralCacheFallback getCentralFallback() {
		final String centralFallback = this.environment.getProperty(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY, CentralCacheFallback.NONE.name());
		try {
			return CentralCacheFallback.valueOf(centralFallback.trim().toUpperCase(Locale.ROOT));
		}
		catch (final IllegalArgumentException exception) {
			throw new IllegalArgumentException("'" + CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "' is '" + centralFallback + "', expected one of "
					+ Arrays.toString(CentralCacheFallback.values()).toLowerCase(Locale.ROOT) + ".", exception);
		}
	}

	/**
	 * @see org.springframework.beans.factory.BeanFactoryAware#setBeanFactory(org.springframework.beans.factory.BeanFactory)
	 */
	@Override
	public void setBeanFactory(
			final BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	/**
	 * Creates a local cache manager with the central cache manager's expiration. It records stats and publishes metrics
	 * as the local cache managers do (tagged with the central cache manager name); the local cache stats debug log
	 * covers only the local cache managers.
	 *
	 * @param  expiration Expiration.
	 * @return            The local cache manager.
	 */
	private CacheManager createLocalCacheManager(
			final CacheManagerExpiration expiration) {
		return LocalCacheAutoConfiguration.createCacheManager(expiration.getCentralCacheManagerName(), expiration.getExpiration(this.environment),
				this.environment.getProperty(LocalCacheAutoConfiguration.MAXIMUM_SIZE_PROPERTY, Long.class),
				LocalCacheAutoConfiguration.shouldRecordStats(this.environment), LocalCacheAutoConfiguration.shouldPublishMetrics(this.environment),
				this.beanFactory.getBeanProvider(MeterRegistry.class)::getIfUnique, this.environment);
	}

	/**
	 * Registers a cache manager under a name, if the name has no bean.
	 *
	 * @param  registry             Registry.
	 * @param  name                 Cache manager name.
	 * @param  isPrimary            If the cache manager is the default one.
	 * @param  cacheManagerSupplier Cache manager supplier.
	 * @return                      If the cache manager was registered.
	 */
	private boolean registerIfMissing(
			final BeanDefinitionRegistry registry,
			final String name,
			final boolean isPrimary,
			final Supplier<CacheManager> cacheManagerSupplier) {
		final boolean isMissing = !registry.containsBeanDefinition(name) && !registry.isAlias(name);
		if (isMissing) {
			final RootBeanDefinition beanDefinition = new RootBeanDefinition(CacheManager.class, cacheManagerSupplier);
			beanDefinition.setPrimary(isPrimary);
			registry.registerBeanDefinition(name, beanDefinition);
		}
		return isMissing;
	}

	/**
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor#postProcessBeanDefinitionRegistry(org.springframework.beans.factory.support.BeanDefinitionRegistry)
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(
			final BeanDefinitionRegistry registry) throws BeansException {
		final CentralCacheFallback centralFallback = this.getCentralFallback();
		final List<String> namesWithoutCaching = new ArrayList<>();
		final List<String> centralNamesCachedLocally = new ArrayList<>();
		for (final CacheManagerExpiration expiration : CacheManagerExpiration.values()) {
			// Local caching off: the local names do not cache, and the minutes one stays the default, as when it is on.
			if (this.registerIfMissing(registry, expiration.getLocalCacheManagerName(), expiration == CacheManagerExpiration.MINUTES, NoOpCacheManager::new)) {
				namesWithoutCaching.add(expiration.getLocalCacheManagerName());
			}
			// No central (Redis) configuration: the central names do not cache, or cache locally if the service chose it.
			if (CentralCacheFallback.LOCAL == centralFallback) {
				if (this.registerIfMissing(registry, expiration.getCentralCacheManagerName(), false, () -> this.createLocalCacheManager(expiration))) {
					centralNamesCachedLocally.add(expiration.getCentralCacheManagerName());
				}
			}
			else if (this.registerIfMissing(registry, expiration.getCentralCacheManagerName(), false, NoOpCacheManager::new)) {
				namesWithoutCaching.add(expiration.getCentralCacheManagerName());
			}
		}
		if (!namesWithoutCaching.isEmpty()) {
			CacheManagerFallbackRegistrar.LOGGER.info("Cache managers without a configuration do not cache: {}.", namesWithoutCaching);
		}
		if (!centralNamesCachedLocally.isEmpty()) {
			CacheManagerFallbackRegistrar.LOGGER.info("Central cache managers without a configuration cache locally: {}.", centralNamesCachedLocally);
		}
	}

	/**
	 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor#postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory)
	 */
	@Override
	public void postProcessBeanFactory(
			final ConfigurableListableBeanFactory beanFactory) throws BeansException {
		// Nothing to do: the cache managers are registered with the bean definitions.
	}

	/**
	 * Runs after the configuration classes registered their bean definitions.
	 *
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
