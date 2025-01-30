package org.coldis.library.test.service.cache;

/**
 * Object.
 */
public class CacheSimpleObject1 {

	/**
	 * Attribute.
	 */
	private Integer attribute;

	/**
	 * Constructor.
	 */
	public CacheSimpleObject1() {
	}

	/**
	 * Constructor.
	 *
	 * @param attribute Attribute.
	 */
	public CacheSimpleObject1(final Integer attribute) {
		super();
		this.attribute = attribute;
	}

	/**
	 * Gets the attribute.
	 *
	 * @return The attribute.
	 */
	public Integer getAttribute() {
		return this.attribute;
	}

	/**
	 * Sets the attribute.
	 *
	 * @param attribute New attribute.
	 */
	public void setAttribute(
			final Integer attribute) {
		this.attribute = attribute;
	}

}
