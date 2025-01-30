package org.coldis.library.test.service.cache;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;
import org.coldis.library.serialization.json.NumberDeserializer;
import org.coldis.library.serialization.json.NumberSerializer;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Object.
 */
@JsonTypeName(CacheSimpleObject2.TYPE_NAME)
public class CacheSimpleObject2 implements Typable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -8061329461936357656L;

	/**
	 * Type name.
	 */
	public static final String TYPE_NAME = "CacheSimpleObject2";

	/**
	 * Attribute.
	 */
	private BigDecimal attribute;

	/**
	 * Set.
	 */
	private Set<CacheSimpleObject2>list;

	/**
	 * Constructor.
	 */
	public CacheSimpleObject2() {
		super();
	}

	/**
	 * Constructor.
	 *
	 * @param attribute Attribute.
	 */
	public CacheSimpleObject2(final BigDecimal attribute) {
		super();
		this.attribute = attribute;
	}

	/**
	 * Constructor.
	 *
	 * @param attribute Attribute.
	 */
	public CacheSimpleObject2(final BigDecimal attribute, final Set<CacheSimpleObject2> list) {
		super();
		this.attribute = attribute;
		this.list = list;
	}

	/**
	 * Gets the attribute.
	 *
	 * @return The attribute.
	 */
	@JsonSerialize(using = NumberSerializer.class)
	@JsonDeserialize(using = NumberDeserializer.class)
	@JsonView({ ModelView.Public.class })
	public BigDecimal getAttribute() {
		return this.attribute;
	}

	/**
	 * Sets the attribute.
	 *
	 * @param attribute New attribute.
	 */
	public void setAttribute(
			final BigDecimal attribute) {
		this.attribute = attribute;
	}

	/**
	 * Gets the list.
	 *
	 * @return The list.
	 */
	@JsonView({ ModelView.Public.class })
	public Set<CacheSimpleObject2> getSet() {
		this.list = (this.list == null ? new HashSet<>() : new HashSet<>(this.list));
		return this.list;
	}

	/**
	 * Sets the list.
	 *
	 * @param list New list.
	 */
	public void setSet(
			final Set<CacheSimpleObject2> list) {
		this.list = list;
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	public String getTypeName() {
		return CacheSimpleObject2.TYPE_NAME;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.attribute, this.list);
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
		final CacheSimpleObject2 other = (CacheSimpleObject2) obj;
		return Objects.equals(this.attribute, other.attribute) && Objects.equals(this.list, other.list);
	}

}
