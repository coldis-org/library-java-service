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
	 * Test property.
	 */
	private Float property5;

	/**
	 * Test property.
	 */
	private Boolean property6;

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
	 *
	 * @return The property2.
	 */
	public Long getProperty2() {
		return this.property2;
	}

	/**
	 * Sets the property2.
	 *
	 * @param property2 New property2.
	 */
	public void setProperty2(
			final Long property2) {
		this.property2 = property2;
	}

	/**
	 * Gets the property3.
	 *
	 * @return The property3.
	 */
	public Integer getProperty3() {
		return this.property3;
	}

	/**
	 * Sets the property3.
	 *
	 * @param property3 New property3.
	 */
	public void setProperty3(
			final Integer property3) {
		this.property3 = property3;
	}

	/**
	 * Gets the property4.
	 *
	 * @return The property4.
	 */
	public Double getProperty4() {
		return this.property4;
	}

	/**
	 * Sets the property4.
	 *
	 * @param property4 New property4.
	 */
	public void setProperty4(
			final Double property4) {
		this.property4 = property4;
	}

	/**
	 * Gets the property5.
	 *
	 * @return The property5.
	 */
	public Float getProperty5() {
		return this.property5;
	}

	/**
	 * Sets the property5.
	 *
	 * @param property5 New property5.
	 */
	public void setProperty5(
			final Float property5) {
		this.property5 = property5;
	}

	/**
	 * Gets the property6.
	 *
	 * @return The property6.
	 */
	public Boolean getProperty6() {
		return this.property6;
	}

	/**
	 * Sets the property6.
	 *
	 * @param property6 New property6.
	 */
	public void setProperty6(
			final Boolean property6) {
		this.property6 = property6;
	}

}
