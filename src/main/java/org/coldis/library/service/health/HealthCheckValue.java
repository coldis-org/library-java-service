package org.coldis.library.service.health;

import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;

/**
 * Health check value.
 */
@JsonTypeName(value = HealthCheckValue.TYPE_NAME)
public class HealthCheckValue implements Typable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -5168044828758231509L;

	/**
	 * Type name.
	 */
	public static final String TYPE_NAME = "org.coldis.library.spring.health.HealthCheckValue";

	/**
	 * Fixed value.
	 */
	public static final Long VALUE = 1L;

	/**
	 * Health.
	 */
	private static final String HEALTH = "healthy";

	/**
	 * Gets the health.
	 *
	 * @return The health.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getHealth() {
		return HealthCheckValue.HEALTH;
	}

	/**
	 * Gets the value.
	 *
	 * @return The value.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Long getValue() {
		return HealthCheckValue.VALUE;
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getTypeName() {
		return HealthCheckValue.TYPE_NAME;
	}

}
