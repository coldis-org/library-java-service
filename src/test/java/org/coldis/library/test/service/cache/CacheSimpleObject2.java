package org.coldis.library.test.service.cache;

import java.math.BigDecimal;
import java.util.List;

import org.coldis.library.model.view.ModelView;
import org.coldis.library.serialization.json.NumberDeserializer;
import org.coldis.library.serialization.json.NumberSerializer;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Object.
 */
public class CacheSimpleObject2 {

	/**
	 * Attribute.
	 */
	private BigDecimal attribute;

	/**
	 * List.
	 */
	private List<CacheSimpleObject2> list;

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
	public List<CacheSimpleObject2> getList() {
		return this.list;
	}

	/**
	 * Sets the list.
	 *
	 * @param list New list.
	 */
	public void setList(
			final List<CacheSimpleObject2> list) {
		this.list = list;
	}

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
	public CacheSimpleObject2(final BigDecimal attribute, final List<CacheSimpleObject2> list) {
		super();
		this.attribute = attribute;
		this.list = list;
	}

}
