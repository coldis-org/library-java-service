package org.coldis.library.service.helper;

import java.math.RoundingMode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Number properties.
 */
@Component
@ConfigurationProperties(prefix = "org.coldis.configuration.number")
public class NumberProperties {

	/**
	 * Calculation scale.
	 */
	public static Integer CALCULATION_SCALE;

	/**
	 * Rate scale.
	 */
	public static Integer RATE_SCALE;

	/**
	 * Currency scale.
	 */
	public static Integer CURRENCY_SCALE;

	/**
	 * Rounding mode.
	 */
	public static RoundingMode ROUNDING_MODE;

	/**
	 * Gets the calculation scale.
	 *
	 * @return The calculation scale.
	 */
	public Integer getCalculationScale() {
		return NumberProperties.CALCULATION_SCALE;
	}

	/**
	 * Sets the calculation scale.
	 *
	 * @param calculationScale New calculation scale.
	 */
	public void setCalculationScale(final Integer calculationScale) {
		NumberProperties.CALCULATION_SCALE = calculationScale;
	}

	/**
	 * Gets the rate scale.
	 *
	 * @return The rate scale.
	 */
	public Integer getRateScale() {
		return NumberProperties.RATE_SCALE;
	}

	/**
	 * Sets the rate scale.
	 *
	 * @param rateScale New rate scale.
	 */
	public void setRateScale(final Integer rateScale) {
		NumberProperties.RATE_SCALE = rateScale;
	}

	/**
	 * Gets the currency scale.
	 *
	 * @return The currency scale.
	 */
	public Integer getCurrencyScale() {
		return NumberProperties.CURRENCY_SCALE;
	}

	/**
	 * Sets the currency scale.
	 *
	 * @param currencyScale New currency scale.
	 */
	public void setCurrencyScale(final Integer currencyScale) {
		NumberProperties.CURRENCY_SCALE = currencyScale;
	}

	/**
	 * Gets the rounding mode.
	 *
	 * @return The rounding mode.
	 */
	public RoundingMode getRoundingMode() {
		return NumberProperties.ROUNDING_MODE;
	}

	/**
	 * Sets the rounding mode.
	 *
	 * @param roundingMode New rounding mode.
	 */
	public void setRoundingMode(final RoundingMode roundingMode) {
		NumberProperties.ROUNDING_MODE = roundingMode;
	}

}
