package org.coldis.library.service.warmup;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.CacheableOperation;

/**
 * Cache operation source of a cache aspect (or interceptor) that, on warm-up threads, sends every cache operation
 * through {@link ServiceWarmUpCacheResolver}, which gives the call caches that drop writes, evictions and clears. The
 * operations are otherwise the same, so the same key, condition and cache names apply. Other threads get the wrapped
 * source's operations unchanged, so their caches, and the cache manager beans, are not touched.
 */
public class ServiceWarmUpCacheOperationSource implements CacheOperationSource {

	/**
	 * Cache operation source.
	 */
	private final CacheOperationSource cacheOperationSource;

	/**
	 * Default cache resolver of the cache aspect, for operations that name neither a cache manager nor a resolver.
	 */
	private final Supplier<CacheResolver> defaultCacheResolver;

	/**
	 * Warm-up operations, by the operation they stand for.
	 */
	private final Map<CacheOperation, CacheOperation> warmUpOperations = new ConcurrentHashMap<>();

	/**
	 * Cache operation that stands, on warm-up threads, for an operation of the service.
	 */
	interface WarmUpCacheOperation {

		/**
		 * Gets the operation of the service it stands for.
		 *
		 * @return The operation of the service.
		 */
		CacheOperation getOriginalOperation();

		/**
		 * Gets the default cache resolver of the cache aspect.
		 *
		 * @return The default cache resolver.
		 */
		Supplier<CacheResolver> getDefaultCacheResolver();

	}

	/**
	 * Warm-up version of a cacheable operation.
	 */
	private static final class WarmUpCacheableOperation extends CacheableOperation implements WarmUpCacheOperation {

		/**
		 * Operation of the service.
		 */
		private final CacheOperation originalOperation;

		/**
		 * Default cache resolver of the cache aspect.
		 */
		private final Supplier<CacheResolver> defaultCacheResolver;

		/**
		 * Constructor.
		 *
		 * @param builder              Builder.
		 * @param originalOperation    Operation of the service.
		 * @param defaultCacheResolver Default cache resolver of the cache aspect.
		 */
		WarmUpCacheableOperation(final CacheableOperation.Builder builder, final CacheOperation originalOperation,
				final Supplier<CacheResolver> defaultCacheResolver) {
			super(builder);
			this.originalOperation = originalOperation;
			this.defaultCacheResolver = defaultCacheResolver;
		}

		/**
		 * @see org.coldis.library.service.warmup.ServiceWarmUpCacheOperationSource.WarmUpCacheOperation#getOriginalOperation()
		 */
		@Override
		public CacheOperation getOriginalOperation() {
			return this.originalOperation;
		}

		/**
		 * @see org.coldis.library.service.warmup.ServiceWarmUpCacheOperationSource.WarmUpCacheOperation#getDefaultCacheResolver()
		 */
		@Override
		public Supplier<CacheResolver> getDefaultCacheResolver() {
			return this.defaultCacheResolver;
		}

	}

	/**
	 * Warm-up version of a cache put operation.
	 */
	private static final class WarmUpCachePutOperation extends CachePutOperation implements WarmUpCacheOperation {

		/**
		 * Operation of the service.
		 */
		private final CacheOperation originalOperation;

		/**
		 * Default cache resolver of the cache aspect.
		 */
		private final Supplier<CacheResolver> defaultCacheResolver;

		/**
		 * Constructor.
		 *
		 * @param builder              Builder.
		 * @param originalOperation    Operation of the service.
		 * @param defaultCacheResolver Default cache resolver of the cache aspect.
		 */
		WarmUpCachePutOperation(final CachePutOperation.Builder builder, final CacheOperation originalOperation,
				final Supplier<CacheResolver> defaultCacheResolver) {
			super(builder);
			this.originalOperation = originalOperation;
			this.defaultCacheResolver = defaultCacheResolver;
		}

		/**
		 * @see org.coldis.library.service.warmup.ServiceWarmUpCacheOperationSource.WarmUpCacheOperation#getOriginalOperation()
		 */
		@Override
		public CacheOperation getOriginalOperation() {
			return this.originalOperation;
		}

		/**
		 * @see org.coldis.library.service.warmup.ServiceWarmUpCacheOperationSource.WarmUpCacheOperation#getDefaultCacheResolver()
		 */
		@Override
		public Supplier<CacheResolver> getDefaultCacheResolver() {
			return this.defaultCacheResolver;
		}

	}

	/**
	 * Warm-up version of a cache evict operation.
	 */
	private static final class WarmUpCacheEvictOperation extends CacheEvictOperation implements WarmUpCacheOperation {

		/**
		 * Operation of the service.
		 */
		private final CacheOperation originalOperation;

		/**
		 * Default cache resolver of the cache aspect.
		 */
		private final Supplier<CacheResolver> defaultCacheResolver;

		/**
		 * Constructor.
		 *
		 * @param builder              Builder.
		 * @param originalOperation    Operation of the service.
		 * @param defaultCacheResolver Default cache resolver of the cache aspect.
		 */
		WarmUpCacheEvictOperation(final CacheEvictOperation.Builder builder, final CacheOperation originalOperation,
				final Supplier<CacheResolver> defaultCacheResolver) {
			super(builder);
			this.originalOperation = originalOperation;
			this.defaultCacheResolver = defaultCacheResolver;
		}

		/**
		 * @see org.coldis.library.service.warmup.ServiceWarmUpCacheOperationSource.WarmUpCacheOperation#getOriginalOperation()
		 */
		@Override
		public CacheOperation getOriginalOperation() {
			return this.originalOperation;
		}

		/**
		 * @see org.coldis.library.service.warmup.ServiceWarmUpCacheOperationSource.WarmUpCacheOperation#getDefaultCacheResolver()
		 */
		@Override
		public Supplier<CacheResolver> getDefaultCacheResolver() {
			return this.defaultCacheResolver;
		}

	}

	/**
	 * Constructor.
	 *
	 * @param cacheOperationSource Cache operation source.
	 * @param defaultCacheResolver Default cache resolver of the cache aspect.
	 */
	public ServiceWarmUpCacheOperationSource(final CacheOperationSource cacheOperationSource, final Supplier<CacheResolver> defaultCacheResolver) {
		super();
		this.cacheOperationSource = cacheOperationSource;
		this.defaultCacheResolver = defaultCacheResolver;
	}

	/**
	 * Gets the wrapped cache operation source.
	 *
	 * @return The wrapped cache operation source.
	 */
	public CacheOperationSource getCacheOperationSource() {
		return this.cacheOperationSource;
	}

	/**
	 * Copies what an operation and its warm-up version share into a builder: everything but the name, which identifies
	 * the warm-up version apart from any other operation of the method, and the cache resolver, which is the warm-up's.
	 *
	 * @param operation Operation of the service.
	 * @param builder   Builder of the warm-up version.
	 */
	private static void copyInto(
			final CacheOperation operation,
			final CacheOperation.Builder builder) {
		// The aspect caches what it resolves for an operation by the operation's description, so the name carries the
		// whole description of the operation it stands for.
		builder.setName("service warm-up of " + operation);
		builder.setCacheNames(operation.getCacheNames().toArray(String[]::new));
		builder.setKey(operation.getKey());
		builder.setKeyGenerator(operation.getKeyGenerator());
		builder.setCacheManager(operation.getCacheManager());
		builder.setCacheResolver(ServiceWarmUpCacheResolver.BEAN_NAME);
		builder.setCondition(operation.getCondition());
	}

	/**
	 * Creates the warm-up version of an operation.
	 *
	 * @param  operation Operation of the service.
	 * @return           The warm-up version, or the operation itself when its type is not known.
	 */
	private CacheOperation createWarmUpOperation(
			final CacheOperation operation) {
		CacheOperation warmUpOperation = operation;
		if (operation instanceof final CacheableOperation cacheableOperation) {
			final CacheableOperation.Builder builder = new CacheableOperation.Builder();
			ServiceWarmUpCacheOperationSource.copyInto(operation, builder);
			builder.setUnless(cacheableOperation.getUnless());
			builder.setSync(cacheableOperation.isSync());
			warmUpOperation = new WarmUpCacheableOperation(builder, operation, this.defaultCacheResolver);
		}
		else if (operation instanceof final CachePutOperation cachePutOperation) {
			final CachePutOperation.Builder builder = new CachePutOperation.Builder();
			ServiceWarmUpCacheOperationSource.copyInto(operation, builder);
			builder.setUnless(cachePutOperation.getUnless());
			warmUpOperation = new WarmUpCachePutOperation(builder, operation, this.defaultCacheResolver);
		}
		else if (operation instanceof final CacheEvictOperation cacheEvictOperation) {
			final CacheEvictOperation.Builder builder = new CacheEvictOperation.Builder();
			ServiceWarmUpCacheOperationSource.copyInto(operation, builder);
			builder.setCacheWide(cacheEvictOperation.isCacheWide());
			builder.setBeforeInvocation(cacheEvictOperation.isBeforeInvocation());
			warmUpOperation = new WarmUpCacheEvictOperation(builder, operation, this.defaultCacheResolver);
		}
		return warmUpOperation;
	}

	/**
	 * @see org.springframework.cache.interceptor.CacheOperationSource#isCandidateClass(java.lang.Class)
	 */
	@Override
	public boolean isCandidateClass(
			final Class<?> targetClass) {
		return this.cacheOperationSource.isCandidateClass(targetClass);
	}

	/**
	 * @see org.springframework.cache.interceptor.CacheOperationSource#hasCacheOperations(java.lang.reflect.Method,
	 *      java.lang.Class)
	 */
	@Override
	public boolean hasCacheOperations(
			final Method method,
			final Class<?> targetClass) {
		return this.cacheOperationSource.hasCacheOperations(method, targetClass);
	}

	/**
	 * @see org.springframework.cache.interceptor.CacheOperationSource#getCacheOperations(java.lang.reflect.Method,
	 *      java.lang.Class)
	 */
	@Override
	public Collection<CacheOperation> getCacheOperations(
			final Method method,
			final Class<?> targetClass) {
		final Collection<CacheOperation> operations = this.cacheOperationSource.getCacheOperations(method, targetClass);
		return ((operations != null) && ServiceWarmUpContext.isWarmingUp()
				? operations.stream().map(operation -> this.warmUpOperations.computeIfAbsent(operation, this::createWarmUpOperation)).toList()
				: operations);
	}

}
