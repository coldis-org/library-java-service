package org.coldis.library.test.service.installer;

import java.util.Objects;

import org.coldis.library.persistence.model.AbstractTimestampableEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

/**
 * Test entity.
 */
@Entity
@IdClass(DataInstallerTestEntityKey.class)
public class DataInstallerTestEntity extends AbstractTimestampableEntity {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 7888029963299563614L;

	/**
	 * Test property.
	 */
	private Integer property1;

	/**
	 * Test property.
	 */
	private Integer property2;

	/**
	 * Test property.
	 */
	private String property3;

	/**
	 * No arguments constructor.
	 */
	public DataInstallerTestEntity() {
	}

	/**
	 * Default constructor.
	 *
	 * @param property1 Test property.
	 * @param property2 Test property.
	 * @param property3 Test property.
	 */
	public DataInstallerTestEntity(final Integer property1, final Integer property2, final String property3) {
		super();
		this.property1 = property1;
		this.property2 = property2;
		this.property3 = property3;
	}

	/**
	 * Gets the property1.
	 *
	 * @return The property1.
	 */
	@Id
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
	@Id
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

	/**
	 * Gets the property3.
	 *
	 * @return The property3.
	 */
	public String getProperty3() {
		return this.property3;
	}

	/**
	 * Sets the property3.
	 *
	 * @param property3 New property3.
	 */
	public void setProperty3(
			final String property3) {
		this.property3 = property3;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.property1, this.property2, this.property3);
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
		if ((obj == null) || !(obj instanceof DataInstallerTestEntity)) {
			return false;
		}
		final DataInstallerTestEntity other = (DataInstallerTestEntity) obj;
		return Objects.equals(this.property1, other.property1) && Objects.equals(this.property2, other.property2)
				&& Objects.equals(this.property3, other.property3);
	}

}
