package org.coldis.library.test.service.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Properties class. */
@Component
@ConfigurationProperties(prefix = "test.properties")
class TestProperties1 {

	/**
	 * Test property.
	 */
	private String property1;

	/**
	 * Test property.
	 */
	private Long property2;

	/**
	 * Test property.
	 */
	private Integer property3;

	/**
	 * Test property.
	 */
	private Double property4;

	/**
	 * Gets the property1.
	 *
	 * @return The property1.
	 */
	public String getProperty1() {
		return this.property1;
	}

	/**
	 * Sets the property1.
	 *
	 * @param property1 New property1.
	 */
	public void setProperty1(
			final String property1) {
		this.property1 = property1;
	}

	/**
	 * Gets the property2.
	 * @return The property2.
	 */
	public Long getProperty2() {
		return property2;
	}

	/**
	 * Sets the property2.
	 * @param property2 New property2.
	 */
	public void setProperty2(
			Long property2) {
		this.property2 = property2;
	}

	/**
	 * Gets the property3.
	 * @return The property3.
	 */
	public Integer getProperty3() {
		return property3;
	}

	/**
	 * Sets the property3.
	 * @param property3 New property3.
	 */
	public void setProperty3(
			Integer property3) {
		this.property3 = property3;
	}

	/**
	 * Gets the property4.
	 * @return The property4.
	 */
	public Double getProperty4() {
		return property4;
	}

	/**
	 * Sets the property4.
	 * @param property4 New property4.
	 */
	public void setProperty4(
			Double property4) {
		this.property4 = property4;
	}
	
	
}
