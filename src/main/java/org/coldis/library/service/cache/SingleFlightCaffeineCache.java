package org.coldis.library.service.cache;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.springframework.cache.caffeine.CaffeineCache;

/**
 * Caffeine cache whose synchronized loads ({@link #get(Object, Callable)}, used by {@code @Cacheable(sync = true)})
 * are deduplicated per key by a {@link SingleFlightCacheLoader}, outside Caffeine's atomic compute: a slow load does
 * not hold a Caffeine hash-bin lock (so it does not block other keys of the bin), and a load may call another
 * synchronized cached method of the same cache without failing with "Recursive update" or deadlocking. Every other
 * method is {@link CaffeineCache}'s, unchanged. Null values are stored as {@link CaffeineCache} stores them. The miss
 * still counts in the Caffeine stats, but the load is a put, so the {@code cache.puts} meter (Caffeine loads) no
 * longer counts synchronized loads.
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
	 * Loads a key and puts it in the cache, unless another load put it meanwhile (read without counting in the stats).
	 *
	 * @param  key         Key.
	 * @param  valueLoader Value loader.
	 * @return             The store value.
	 */
	private Object loadAndPut(
			final Object key,
			final Callable<?> valueLoader) {
		Object storeValue = this.getNativeCache().policy().getIfPresentQuietly(key);
		if (storeValue == null) {
			try {
				storeValue = this.toStoreValue(valueLoader.call());
			}
			catch (final Exception exception) {
				throw new ValueRetrievalException(key, valueLoader, exception);
			}
			this.getNativeCache().put(key, storeValue);
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
			storeValue = this.singleFlightLoader.load(key, valueLoader, () -> this.loadAndPut(key, valueLoader));
		}
		return (T) this.fromStoreValue(storeValue);
	}

}
