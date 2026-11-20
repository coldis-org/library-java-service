package org.coldis.library.service.cache;

import java.util.function.Supplier;

import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

/**
 * Caffeine cache manager that publishes each cache's metrics (gets by hit and miss, evictions, size) through
 * Micrometer's vendor-neutral {@link CaffeineCacheMetrics} binder when the cache is created. The library's caches are
 * created by name on first use, after Spring Boot's startup-time cache metrics registrar ran, so they are bound here
 * instead. Meters are tagged with the cache name ({@code cache}) and the full cache manager bean name
 * ({@value #CACHE_MANAGER_TAG}: Spring Boot's tag key; Spring Boot's value drops a trailing "cacheManager", this one
 * keeps the whole bean name). A cache is bound only when metrics are on, it records stats and the meter registry
 * supplier gives a registry; otherwise nothing is published. The binder's {@code cache.puts} counts Caffeine loads,
 * which only synchronized cached methods ({@code @Cacheable(sync = true)}) make: plain cached methods leave it at zero.
 */
public class MeteredCaffeineCacheManager extends CaffeineCacheManager {

	/**
	 * Tag with the cache manager bean name (Spring Boot's cache metrics tag key).
	 */
	public static final String CACHE_MANAGER_TAG = "cache.manager";

	/**
	 * Cache manager bean name.
	 */
	private final String cacheManagerName;

	/**
	 * If the caches publish metrics.
	 */
	private final boolean shouldPublishMetrics;

	/**
	 * Meter registry supplier: gives null when there is no single (or primary) registry.
	 */
	private final Supplier<MeterRegistry> meterRegistrySupplier;

	/**
	 * Constructor.
	 *
	 * @param cacheManagerName      Cache manager bean name.
	 * @param shouldPublishMetrics  If the caches publish metrics.
	 * @param meterRegistrySupplier Meter registry supplier (gives null when there is no single or primary registry).
	 */
	public MeteredCaffeineCacheManager(
			final String cacheManagerName,
			final boolean shouldPublishMetrics,
			final Supplier<MeterRegistry> meterRegistrySupplier) {
		super();
		this.cacheManagerName = cacheManagerName;
		this.shouldPublishMetrics = shouldPublishMetrics;
		this.meterRegistrySupplier = meterRegistrySupplier;
	}

	/**
	 * Gets the cache manager bean name.
	 *
	 * @return The cache manager bean name.
	 */
	public String getCacheManagerName() {
		return this.cacheManagerName;
	}

	/**
	 * Binds the cache metrics to the meter registry, if metrics are on, the cache records stats and there is a registry.
	 *
	 * @param name  Cache name.
	 * @param cache Native cache.
	 */
	private void bindMetrics(
			final String name,
			final com.github.benmanes.caffeine.cache.Cache<Object, Object> cache) {
		if (this.shouldPublishMetrics && cache.policy().isRecordingStats()) {
			final MeterRegistry meterRegistry = this.meterRegistrySupplier.get();
			if (meterRegistry != null) {
				CaffeineCacheMetrics.monitor(meterRegistry, cache, name, Tags.of(MeteredCaffeineCacheManager.CACHE_MANAGER_TAG, this.cacheManagerName));
			}
		}
	}

	/**
	 * Binds the metrics of each cache as it is created.
	 *
	 * @see org.springframework.cache.caffeine.CaffeineCacheManager#adaptCaffeineCache(java.lang.String,
	 *      com.github.benmanes.caffeine.cache.Cache)
	 */
	@Override
	protected Cache adaptCaffeineCache(
			final String name,
			final com.github.benmanes.caffeine.cache.Cache<Object, Object> cache) {
		this.bindMetrics(name, cache);
		return super.adaptCaffeineCache(name, cache);
	}

}
