package org.coldis.library.test.service.jms;

import java.io.Serializable;
import java.util.Objects;

import java.util.Arrays;

/**
 * DtoTestObjectDto.
 */
public class DtoTestObjectDto implements Serializable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -1877623249L;
	
	/**
	 * id.
	 */
	private java.lang.Long id;

	/**
	 * test5.
	 */
	private static final java.lang.String test5 = "ABC";

	/**
	 * test7.
	 */
	private int test7;

	/**
	 * test8.
	 */
	private int[] test88;

	/**
	 * test9.
	 */
	private java.lang.Integer test9;

	/**
	 * typeName.
	 */
	private final java.lang.String typeName = "org.coldis.library.test.spring.jms.DtoTestObject";


	/**
	 * No arguments constructor.
	 */
	public DtoTestObjectDto() {
		super();
	}

	/**
	 * Gets the id.
	 * @return The id.
	 */
	
	public java.lang.Long getId() {
		return id;
	}
	
	/**
	 * Sets the id.
	 *
	 * @param id
	 *            The id.
	 */
	public void setId(final java.lang.Long id) {
		this.id = id;
	}
	
	/**
	 * Sets the id and returns the updated object.
	 *
	 * @param id
	 *            The id.
	 * @return The updated object.
	 */
	public DtoTestObjectDto withId(final java.lang.Long id) {
		this.setId(id);
		return this;
	}
	/**
	 * Gets the test5.
	 * @return The test5.
	 */
	
	public static java.lang.String getTest5() {
		return test5;
	}
	
	/**
	 * Gets the test7.
	 * @return The test7.
	 */
	
	public int getTest7() {
		return test7;
	}
	
	/**
	 * Sets the test7.
	 *
	 * @param test7
	 *            The test7.
	 */
	public void setTest7(final int test7) {
		this.test7 = test7;
	}
	
	/**
	 * Sets the test7 and returns the updated object.
	 *
	 * @param test7
	 *            The test7.
	 * @return The updated object.
	 */
	public DtoTestObjectDto withTest7(final int test7) {
		this.setTest7(test7);
		return this;
	}
	/**
	 * Gets the test8.
	 * @return The test8.
	 */
	
	public int[] getTest88() {
		return test88;
	}
	
	/**
	 * Sets the test8.
	 *
	 * @param test88
	 *            The test8.
	 */
	public void setTest88(final int[] test88) {
		this.test88 = test88;
	}
	
	/**
	 * Sets the test8 and returns the updated object.
	 *
	 * @param test88
	 *            The test8.
	 * @return The updated object.
	 */
	public DtoTestObjectDto withTest88(final int[] test88) {
		this.setTest88(test88);
		return this;
	}
	/**
	 * Gets the test9.
	 * @return The test9.
	 */
	
	public java.lang.Integer getTest9() {
		return test9;
	}
	
	/**
	 * Sets the test9.
	 *
	 * @param test9
	 *            The test9.
	 */
	public void setTest9(final java.lang.Integer test9) {
		this.test9 = test9;
	}
	
	/**
	 * Sets the test9 and returns the updated object.
	 *
	 * @param test9
	 *            The test9.
	 * @return The updated object.
	 */
	public DtoTestObjectDto withTest9(final java.lang.Integer test9) {
		this.setTest9(test9);
		return this;
	}
	/**
	 * Gets the typeName.
	 * @return The typeName.
	 */
	
	public java.lang.String getTypeName() {
		return typeName;
	}
	

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Objects.hash(
id

,
test5

,
test7



,
typeName



			);
		result = prime * result + Arrays.hashCode(test88);
		return result;
	}
	
	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final DtoTestObjectDto other = (DtoTestObjectDto) obj;
		if (! Objects.equals(id, other.id)) {
			return false;
		}
		if (! Objects.equals(test5, other.test5)) {
			return false;
		}
		if (! Objects.equals(test7, other.test7)) {
			return false;
		}
		if (! Arrays.equals(test88, other.test88)) {
			return false;
		}
		if (! Objects.equals(typeName, other.typeName)) {
			return false;
		}
		return true;
	}
	
}