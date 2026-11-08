package org.coldis.library.service.tiering.model;

import java.lang.reflect.Field;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.util.ReflectionUtils;

/**
 * Reflection over an entity's (or its id class's) key properties.
 */
public final class TieredEntityKeys {

	/**
	 * No instances.
	 */
	private TieredEntityKeys() {
		super();
	}

	/**
	 * A field of a class, by name.
	 *
	 * @param  type Class.
	 * @param  name Field name.
	 * @return      The field, accessible.
	 */
	public static Field fieldOf(
			final Class<?> type,
			final String name) {
		final Field field = ReflectionUtils.findField(type, name);
		if (field == null) {
			throw new IntegrationException(new SimpleMessage("coldTiering.key.property.missing", new Object[] { type.getName(), name }));
		}
		ReflectionUtils.makeAccessible(field);
		return field;
	}

	/**
	 * The value of a key property of an object (an entity or an id).
	 *
	 * @param  object   Object.
	 * @param  property Property.
	 * @return          The value.
	 */
	public static Object valueOf(
			final Object object,
			final String property) {
		return ReflectionUtils.getField(TieredEntityKeys.fieldOf(object.getClass(), property), object);
	}

}
