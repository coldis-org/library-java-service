package org.coldis.library.test.service.installer;

import java.io.Serializable;

/**
 * Test entity key.
 */
public class DataInstallerTestEntityKey implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 5652372851493018151L;

	/**
	 * Test field.
	 */
	private Integer property1;

	/**
	 * Test field.
	 */
	private Integer property2;

	/**
	 * no arguments constructor.
	 */
	public DataInstallerTestEntityKey() {
	}

	/**
	 * Default constructor.
	 *
	 * @param property1 Test field.
	 * @param property2 Test field.
	 */
	public DataInstallerTestEntityKey(final Integer property1, final Integer property2) {
		super();
		this.property1 = property1;
		this.property2 = property2;
	}

	/**
	 * Gets the property1.
	 *
	 * @return The property1.
	 */
	public Integer getProperty1() {
		return this.property1;
	}

	/**
	 * Sets the property1.
	 *
	 * @param property1 New property1.
	 */
	public void setProperty1(
			final Integer property1) {
		this.property1 = property1;
	}

	/**
	 * Gets the property2.
	 *
	 * @return The property2.
	 */
	public Integer getProperty2() {
		return this.property2;
	}

	/**
	 * Sets the property2.
	 *
	 * @param property2 New property2.
	 */
	public void setProperty2(
			final Integer property2) {
		this.property2 = property2;
	}

}
