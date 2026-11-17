package org.coldis.library.service.cache;

/**
 * What a central cache manager name resolves to when the service has no central (Redis) cache configuration.
 */
public enum CentralCacheFallback {

	/**
	 * No caching: the cached methods are called every time.
	 */
	NONE,

	/**
	 * A local (Caffeine) cache with the central cache manager's expiration, separate from the local cache managers.
	 */
	LOCAL;

}
