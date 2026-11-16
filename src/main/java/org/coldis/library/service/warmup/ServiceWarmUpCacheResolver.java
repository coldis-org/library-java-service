package org.coldis.library.service.warmup;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.util.StringUtils;

/**
 * Cache resolver of the warm-up versions of cache operations (see {@link ServiceWarmUpCacheOperationSource}): it
 * resolves the caches as the service's operation would, through the cache resolver or cache manager the operation
 * names or the cache aspect's default, and gives them to the warm-up call as {@link ServiceWarmUpCache}s, which read
 * and drop writes, evictions and clears.
 */
public class ServiceWarmUpCacheResolver implements CacheResolver, BeanFactoryAware {

	/**
	 * Bean name, which the warm-up versions of cache operations name.
	 */
	public static final String BEAN_NAME = "serviceWarmUpCacheResolver";

	/**
	 * Cache resolvers of the service's operations.
	 */
	private final Map<CacheOperation, CacheResolver> cacheResolvers = new ConcurrentHashMap<>();

	/**
	 * Bean factory.
	 */
	private BeanFactory beanFactory;

	/**
	 * Constructor.
	 */
	public ServiceWarmUpCacheResolver() {
		super();
	}

	/**
	 * @see org.springframework.beans.factory.BeanFactoryAware#setBeanFactory(org.springframework.beans.factory.BeanFactory)
	 */
	@Override
	public void setBeanFactory(
			final BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Gets the cache resolver the cache aspect uses for an operation of the service, chosen as the aspect chooses it.
	 *
	 * @param  warmUpOperation Warm-up version of the operation.
	 * @return                 The cache resolver of the service's operation.
	 */
	private CacheResolver getCacheResolver(
			final ServiceWarmUpCacheOperationSource.WarmUpCacheOperation warmUpOperation) {
		final CacheOperation operation = warmUpOperation.getOriginalOperation();
		return this.cacheResolvers.computeIfAbsent(operation, key -> {
			CacheResolver cacheResolver = null;
			if (StringUtils.hasText(operation.getCacheResolver())) {
				cacheResolver = BeanFactoryAnnotationUtils.qualifiedBeanOfType(this.beanFactory, CacheResolver.class, operation.getCacheResolver());
			}
			else if (StringUtils.hasText(operation.getCacheManager())) {
				cacheResolver = new SimpleCacheResolver(
						BeanFactoryAnnotationUtils.qualifiedBeanOfType(this.beanFactory, CacheManager.class, operation.getCacheManager()));
			}
			else {
				cacheResolver = warmUpOperation.getDefaultCacheResolver().get();
			}
			return cacheResolver;
		});
	}

	/**
	 * @see org.springframework.cache.interceptor.CacheResolver#resolveCaches(org.springframework.cache.interceptor.CacheOperationInvocationContext)
	 */
	@Override
	public Collection<? extends Cache> resolveCaches(
			final CacheOperationInvocationContext<?> context) {
		if (!(context.getOperation() instanceof final ServiceWarmUpCacheOperationSource.WarmUpCacheOperation warmUpOperation)) {
			throw new IllegalStateException("The service warm-up cache resolver only resolves the warm-up versions of cache operations.");
		}
		return this.getCacheResolver(warmUpOperation).resolveCaches(context).stream().map(ServiceWarmUpCache::new).toList();
	}

}
