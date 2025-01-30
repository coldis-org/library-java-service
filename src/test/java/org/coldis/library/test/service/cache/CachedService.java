package org.coldis.library.test.service.cache;

import java.math.BigDecimal;
import java.util.Set;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cache service.
 */
@Service
public class CachedService {

	/**
	 * Attribute.
	 */
	public static Integer ATTR_1 = 0;

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationCentralCacheManager",
			value = "cachedService-getFromCentralCache1"
	)
	public Integer getFromCentralCache1() {
		CachedService.ATTR_1++;
		return CachedService.ATTR_1;
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationCentralCacheManager",
			value = "cachedService-getFromCentralCache2"
	)
	public CacheSimpleObject1 getFromCentralCache2() {
		CachedService.ATTR_1++;
		return new CacheSimpleObject1(CachedService.ATTR_1);
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationCentralCacheManager",
			value = "cachedService-getFromCentralCache3"
	)
	public BigDecimal getFromCentralCache3() {
		CachedService.ATTR_1++;
		return new BigDecimal(CachedService.ATTR_1);
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationCentralCacheManager",
			value = "cachedService-getFromCentralCache4"
	)
	public CacheSimpleObject2 getFromCentralCache4() {
		CachedService.ATTR_1++;
		return new CacheSimpleObject2(new BigDecimal(CachedService.ATTR_1), Set.of(new CacheSimpleObject2(new BigDecimal(CachedService.ATTR_1))));
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationLocalCacheManager",
			value = "cachedService-getFromLocalCache1"
	)
	public Integer getFromLocalCache1() {
		CachedService.ATTR_1++;
		return CachedService.ATTR_1;
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationLocalCacheManager",
			value = "cachedService-getFromLocalCache2"
	)
	public CacheSimpleObject1 getFromLocalCache2() {
		CachedService.ATTR_1++;
		return new CacheSimpleObject1(CachedService.ATTR_1);
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationLocalCacheManager",
			value = "cachedService-getFromLocalCache3"
	)
	public BigDecimal getFromLocalCache3() {
		CachedService.ATTR_1++;
		return new BigDecimal(CachedService.ATTR_1);
	}

	/**
	 * Gets the aTTR_1.
	 *
	 * @return The aTTR_1.
	 */
	@Cacheable(
			cacheManager = "millisExpirationLocalCacheManager",
			value = "cachedService-getFromLocalCache4"
	)
	public CacheSimpleObject2 getFromLocalCache4() {
		CachedService.ATTR_1++;
		return new CacheSimpleObject2(new BigDecimal(CachedService.ATTR_1));
	}

}
