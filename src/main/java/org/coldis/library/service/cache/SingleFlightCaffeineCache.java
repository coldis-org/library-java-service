package org.coldis.library.service.cache;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

import org.springframework.cache.caffeine.CaffeineCache;

/**
 * Caffeine cache whose synchronized loads ({@link #get(Object, Callable)}, used by {@code @Cacheable(sync = true)})
 * are deduplicated per key by a {@link SingleFlightCacheLoader}, outside Caffeine's atomic compute: a slow load does
 * not hold a Caffeine hash-bin lock (so it does not block other keys of the bin), and a load may call another
 * synchronized cached method of the same cache without failing with "Recursive update" or deadlocking. Every other
 * method is {@link CaffeineCache}'s, unchanged. Null values are stored as {@link CaffeineCache} stores them. The miss
 * still counts in the Caffeine stats, but the load is a put, so the {@code cache.puts} meter (Caffeine loads) no
 * longer counts synchronized loads. An eviction or a clear that runs during a synchronized load wins: the load gives
 * its value to its callers but does not keep it in the cache.
 */
public class SingleFlightCaffeineCache extends CaffeineCache {

	/**
	 * Single-flight loader.
	 */
	private final SingleFlightCacheLoader singleFlightLoader;

	/**
	 * Constructor.
	 *
	 * @param name            Cache name.
	 * @param cache           Native cache.
	 * @param allowNullValues If null values are allowed.
	 * @param waitTimeout     How long a thread waits for another thread's load of the same key.
	 */
	public SingleFlightCaffeineCache(
			final String name,
			final com.github.benmanes.caffeine.cache.Cache<Object, Object> cache,
			final boolean allowNullValues,
			final Duration waitTimeout) {
		super(name, cache, allowNullValues);
		this.singleFlightLoader = new SingleFlightCacheLoader(waitTimeout);
	}

	/**
	 * Gets the single-flight loader.
	 *
	 * @return The single-flight loader.
	 */
	public SingleFlightCacheLoader getSingleFlightLoader() {
		return this.singleFlightLoader;
	}

	/**
	 * Loads a key and puts it in the cache, unless another load put it meanwhile (read without counting in the stats)
	 * or the load was invalidated (an eviction that runs during the put removes the value again).
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @param  isInvalidated Whether the load was invalidated.
	 * @return             The store value.
	 */
	private Object loadAndPut(
			final Object key,
			final Callable<?> valueLoader,
			final BooleanSupplier isInvalidated) {
		Object storeValue = this.getNativeCache().policy().getIfPresentQuietly(key);
		if (storeValue == null) {
			try {
				storeValue = this.toStoreValue(valueLoader.call());
			}
			catch (final Exception exception) {
				throw new ValueRetrievalException(key, valueLoader, exception);
			}
			if (!isInvalidated.getAsBoolean()) {
				this.getNativeCache().put(key, storeValue);
				if (isInvalidated.getAsBoolean()) {
					this.getNativeCache().asMap().remove(key, storeValue);
				}
			}
		}
		return storeValue;
	}

	/**
	 * Reads the key; on a miss, loads it single-flight.
	 *
	 * @see org.springframework.cache.caffeine.CaffeineCache#get(java.lang.Object, java.util.concurrent.Callable)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> T get(
			final Object key,
			final Callable<T> valueLoader) {
		Object storeValue = this.lookup(key);
		if (storeValue == null) {
			storeValue = this.singleFlightLoader.load(key, valueLoader, isInvalidated -> this.loadAndPut(key, valueLoader, isInvalidated));
		}
		return (T) this.fromStoreValue(storeValue);
	}

	/**
	 * Invalidates the key's load in flight, then evicts it.
	 *
	 * @see org.springframework.cache.caffeine.CaffeineCache#evict(java.lang.Object)
	 */
	@Override
	public void evict(
			final Object key) {
		this.singleFlightLoader.invalidate(key);
		super.evict(key);
	}

	/**
	 * Invalidates the key's load in flight, then evicts it.
	 *
	 * @see org.springframework.cache.caffeine.CaffeineCache#evictIfPresent(java.lang.Object)
	 */
	@Override
	public boolean evictIfPresent(
			final Object key) {
		this.singleFlightLoader.invalidate(key);
		return super.evictIfPresent(key);
	}

	/**
	 * Invalidates every load in flight, then clears the cache.
	 *
	 * @see org.springframework.cache.caffeine.CaffeineCache#clear()
	 */
	@Override
	public void clear() {
		this.singleFlightLoader.invalidateAll();
		super.clear();
	}

	/**
	 * Invalidates every load in flight, then clears the cache.
	 *
	 * @see org.springframework.cache.caffeine.CaffeineCache#invalidate()
	 */
	@Override
	public boolean invalidate() {
		this.singleFlightLoader.invalidateAll();
		return super.invalidate();
	}

}
