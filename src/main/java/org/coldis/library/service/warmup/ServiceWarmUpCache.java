package org.coldis.library.service.warmup;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.springframework.cache.Cache;

/**
 * Cache for warm-up calls: it reads from the wrapped cache and drops every write, so a warm-up call neither fills nor
 * evicts a cache that other calls use. A value loader still runs on a miss; its value is returned without being
 * stored.
 */
public class ServiceWarmUpCache implements Cache {

	/**
	 * Cache.
	 */
	private final Cache cache;

	/**
	 * Constructor.
	 *
	 * @param cache Cache.
	 */
	public ServiceWarmUpCache(final Cache cache) {
		super();
		this.cache = cache;
	}

	/**
	 * @see org.springframework.cache.Cache#getName()
	 */
	@Override
	public String getName() {
		return this.cache.getName();
	}

	/**
	 * @see org.springframework.cache.Cache#getNativeCache()
	 */
	@Override
	public Object getNativeCache() {
		return this.cache.getNativeCache();
	}

	/**
	 * @see org.springframework.cache.Cache#get(java.lang.Object)
	 */
	@Override
	public ValueWrapper get(
			final Object key) {
		return this.cache.get(key);
	}

	/**
	 * @see org.springframework.cache.Cache#get(java.lang.Object, java.lang.Class)
	 */
	@Override
	public <ValueType> ValueType get(
			final Object key,
			final Class<ValueType> type) {
		return this.cache.get(key, type);
	}

	/**
	 * @see org.springframework.cache.Cache#get(java.lang.Object, java.util.concurrent.Callable)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <ValueType> ValueType get(
			final Object key,
			final Callable<ValueType> valueLoader) {
		final ValueWrapper cachedValue = this.cache.get(key);
		ValueType value = null;
		if (cachedValue == null) {
			try {
				value = valueLoader.call();
			}
			catch (final Exception exception) {
				throw new ValueRetrievalException(key, valueLoader, exception);
			}
		}
		else {
			value = (ValueType) cachedValue.get();
		}
		return value;
	}

	/**
	 * @see org.springframework.cache.Cache#retrieve(java.lang.Object)
	 */
	@Override
	public CompletableFuture<?> retrieve(
			final Object key) {
		return this.cache.retrieve(key);
	}

	/**
	 * @see org.springframework.cache.Cache#retrieve(java.lang.Object, java.util.function.Supplier)
	 */
	@Override
	public <ValueType> CompletableFuture<ValueType> retrieve(
			final Object key,
			final Supplier<CompletableFuture<ValueType>> valueLoader) {
		return valueLoader.get();
	}

	/**
	 * Drops the write.
	 *
	 * @see org.springframework.cache.Cache#put(java.lang.Object, java.lang.Object)
	 */
	@Override
	public void put(
			final Object key,
			final Object value) {
		// Warm-up calls do not write to caches.
	}

	/**
	 * Returns the cached value, if any, without writing.
	 *
	 * @see org.springframework.cache.Cache#putIfAbsent(java.lang.Object, java.lang.Object)
	 */
	@Override
	public ValueWrapper putIfAbsent(
			final Object key,
			final Object value) {
		return this.cache.get(key);
	}

	/**
	 * Drops the eviction.
	 *
	 * @see org.springframework.cache.Cache#evict(java.lang.Object)
	 */
	@Override
	public void evict(
			final Object key) {
		// Warm-up calls do not write to caches.
	}

	/**
	 * Drops the eviction.
	 *
	 * @see org.springframework.cache.Cache#evictIfPresent(java.lang.Object)
	 */
	@Override
	public boolean evictIfPresent(
			final Object key) {
		return false;
	}

	/**
	 * Drops the clear.
	 *
	 * @see org.springframework.cache.Cache#clear()
	 */
	@Override
	public void clear() {
		// Warm-up calls do not write to caches.
	}

	/**
	 * Drops the invalidation.
	 *
	 * @see org.springframework.cache.Cache#invalidate()
	 */
	@Override
	public boolean invalidate() {
		return false;
	}

}
