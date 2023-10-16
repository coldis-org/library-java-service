package org.coldis.library.test.service.batch;

import java.util.Objects;

import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * Batch object.
 */
@JsonTypeName(value = BatchObject.TYPE_NAME)
public class BatchObject implements Typable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 5145984741817814709L;

	/**
	 * Type name.
	 */
	protected static final String TYPE_NAME = "BatchObject";

	/**
	 * Attribute.
	 */
	private String attribute;

	/**
	 * No arguments constructor.
	 */
	public BatchObject() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param attribute Attribute.
	 */
	public BatchObject(final String attribute) {
		super();
		this.attribute = attribute;
	}

	/**
	 * Gets the attribute.
	 *
	 * @return The attribute.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getAttribute() {
		return this.attribute;
	}

	/**
	 * Sets the attribute.
	 *
	 * @param attribute New attribute.
	 */
	public void setAttribute(
			final String attribute) {
		this.attribute = attribute;
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getTypeName() {
		return BatchObject.TYPE_NAME;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.attribute);
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
		final BatchObject other = (BatchObject) obj;
		return Objects.equals(this.attribute, other.attribute);
	}

}
