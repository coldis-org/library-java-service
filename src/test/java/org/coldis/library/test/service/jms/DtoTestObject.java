package org.coldis.library.test.service.jms;

import java.util.Arrays;
import java.util.Objects;

import org.coldis.library.dto.DtoAttribute;
import org.coldis.library.dto.DtoType;
import org.coldis.library.model.Identifiable;
import org.coldis.library.model.Typable;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonTypeName;

import jakarta.persistence.Transient;

/**
 * DTO test object.
 */
@JsonTypeName(value = DtoTestObject.TYPE_NAME)
@DtoType(
		targetPath = "src/test/java",
		namespace = "org.coldis.library.test.service.jms"
)
public class DtoTestObject implements Typable, Identifiable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -6904605762253009838L;

	/**
	 * Type name.
	 */
	public static final String TYPE_NAME = "org.coldis.library.test.spring.jms.DtoTestObject";

	/**
	 * Test attribute value.
	 */
	public static final String TEST_FINAL_ATTR_VALUE = "ABC";

	/**
	 * Object identifier.
	 */
	private Long id;

	/**
	 * Test attribute.
	 */
	private String test3;

	/**
	 * Test attribute.
	 */
	private String test5;

	/**
	 * Test attribute.
	 */
	private int test7;

	/**
	 * Test attribute.
	 */
	private int[] test8;

	/**
	 * Test attribute.
	 */
	private Integer test9;

	/**
	 * Empty constructor.
	 */
	public DtoTestObject() {
		super();
	}

	/**
	 * Default constructor.
	 *
	 * @param id    Test property.
	 * @param test3 Test property.
	 * @param test5 Test property.
	 * @param test7 Test property.
	 * @param test8 Test property.
	 * @param test9 Test property.
	 */
	public DtoTestObject(final Long id, final String test3, final String test5, final int test7, final int[] test8, final Integer test9) {
		super();
		this.id = id;
		this.test3 = test3;
		this.test5 = test5;
		this.test7 = test7;
		this.test8 = test8;
		this.test9 = test9;
	}

	/**
	 * @see org.coldis.library.model.Identifiable#getId()
	 */
	@Override
	public Long getId() {
		return this.id;
	}

	/**
	 * Sets the identifier.
	 *
	 * @param id New identifier.
	 */
	public void setId(
			final Long id) {
		this.id = id;
	}

	/**
	 * Gets the test3.
	 *
	 * @return The test3.
	 */
	@DtoAttribute(ignore = true)
	public String getTest3() {
		return this.test3;
	}

	/**
	 * Sets the test3.
	 *
	 * @param test3 New test3.
	 */
	public void setTest3(
			final String test3) {
		this.test3 = test3;
	}

	/**
	 * Gets the test5.
	 *
	 * @return The test5.
	 */
	@DtoAttribute(
			defaultValue = DtoTestObject.TEST_FINAL_ATTR_VALUE,
			modifiers = { "static", "final" }
	)
	public String getTest5() {
		return this.test5;
	}

	/**
	 * Sets the test5.
	 *
	 * @param test5 New test5.
	 */
	public void setTest5(
			final String test5) {
		this.test5 = test5;
	}

	/**
	 * Gets the test7.
	 *
	 * @return The test7.
	 */
	public int getTest7() {
		return this.test7;
	}

	/**
	 * Sets the test7.
	 *
	 * @param test7 New test7.
	 */
	public void setTest7(
			final int test7) {
		this.test7 = test7;
	}

	/**
	 * Gets the test8.
	 *
	 * @return The test8.
	 */
	@JsonGetter(value = "test88")
	@DtoAttribute(name = "test88")
	public int[] getTest8() {
		return this.test8;
	}

	/**
	 * Sets the test8.
	 *
	 * @param test8 New test8.
	 */
	public void setTest8(
			final int[] test8) {
		this.test8 = test8;
	}

	/**
	 * Gets the test9.
	 *
	 * @return The test9.
	 */
	@DtoAttribute(usedInComparison = false)
	public Integer getTest9() {
		return this.test9;
	}

	/**
	 * Sets the test9.
	 *
	 * @param test9 New test9.
	 */
	public void setTest9(
			final Integer test9) {
		this.test9 = test9;
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	@Transient
	@DtoAttribute(
			modifiers = { "final" },
			readOnly = true,
			defaultValue = DtoTestObject.TYPE_NAME
	)
	public String getTypeName() {
		return DtoTestObject.TYPE_NAME;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = (prime * result) + Arrays.hashCode(this.test8);
		result = (prime * result) + Objects.hash(this.id, this.test7, this.test9);
		return result;
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (this.getClass() != obj.getClass())) {
			return false;
		}
		final DtoTestObject other = (DtoTestObject) obj;
		return Objects.equals(this.id, other.id) && (this.test7 == other.test7) && Arrays.equals(this.test8, other.test8)
				&& Objects.equals(this.test9, other.test9);
	}

}
