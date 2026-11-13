package org.coldis.library.service.warmup;

/**
 * Thrown when a warm-up call tries a side effect that warm-up calls must not have.
 */
public class ServiceWarmUpSideEffectException extends IllegalStateException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -2384958061874217641L;

	/**
	 * Name of the side effect.
	 */
	private final String sideEffect;

	/**
	 * Constructor.
	 *
	 * @param sideEffect Name of the side effect.
	 */
	public ServiceWarmUpSideEffectException(final String sideEffect) {
		super("'" + sideEffect + "' is not allowed during the service warm-up: warm-up calls must only read.");
		this.sideEffect = sideEffect;
	}

	/**
	 * Gets the name of the side effect.
	 *
	 * @return The name of the side effect.
	 */
	public String getSideEffect() {
		return this.sideEffect;
	}

}
