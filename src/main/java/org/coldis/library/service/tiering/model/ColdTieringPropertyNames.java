package org.coldis.library.service.tiering.model;

/**
 * Names of the cold tiering properties the model and the repositories read directly.
 */
public final class ColdTieringPropertyNames {

	/** Master switch. */
	public static final String ENABLED = "org.coldis.configuration.service.cold-tiering-enabled";

	/** Property prefix. */
	public static final String PREFIX = "org.coldis.library.service.tiering";

	/** Storage selection. */
	public static final String STORAGE = ColdTieringPropertyNames.PREFIX + ".storage";

	/** Object key prefix, with its default. */
	public static final String OBJECT_PREFIX_PLACEHOLDER = "${" + ColdTieringPropertyNames.PREFIX + ".prefix:cold-tiering/}";

	/** Disables a schedule. */
	public static final String NO_SCHEDULE = "-";

	/**
	 * No instances.
	 */
	private ColdTieringPropertyNames() {
		super();
	}

}
