package org.coldis.library.service.cache;

/**
 * Test-only access to the package-private {@link LocalCacheStatsConfiguration}, so isolated contexts can register it.
 */
public final class LocalCacheStatsConfigurationTestSeam {

	/**
	 * No instances.
	 */
	private LocalCacheStatsConfigurationTestSeam() {
		super();
	}

	/**
	 * Gets the local cache stats configuration class.
	 *
	 * @return The local cache stats configuration class.
	 */
	public static Class<?> getConfigurationClass() {
		return LocalCacheStatsConfiguration.class;
	}

}
