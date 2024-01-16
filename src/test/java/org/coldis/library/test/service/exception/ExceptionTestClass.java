package org.coldis.library.test.service.exception;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Test class.
 */
public class ExceptionTestClass {

	/**
	 * Test attribute.
	 */
	private String attribute1;

	/**
	 * Test attribute.
	 */
	private String attribute2 = "";

	/**
	 * Gets the attribute1.
	 *
	 * @return The attribute1.
	 */
	@NotNull
	public String getAttribute1() {
		return this.attribute1;
	}

	/**
	 * Sets the attribute1.
	 *
	 * @param attribute1 New attribute1.
	 */
	public void setAttribute1(
			final String attribute1) {
		this.attribute1 = attribute1;
	}

	/**
	 * Gets the attribute2.
	 *
	 * @return The attribute2.
	 */
	@NotEmpty
	public String getAttribute2() {
		return this.attribute2;
	}

	/**
	 * Sets the attribute2.
	 *
	 * @param attribute2 New attribute2.
	 */
	public void setAttribute2(
			final String attribute2) {
		this.attribute2 = attribute2;
	}

}
