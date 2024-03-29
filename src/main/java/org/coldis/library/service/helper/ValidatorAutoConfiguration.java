package org.coldis.library.service.helper;

import org.coldis.library.helper.ExtendedValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import jakarta.validation.Validator;

/**
 * Validator auto configuration.
 */
@Configuration
public class ValidatorAutoConfiguration {

	/**
	 * Validator.
	 */
	public static ExtendedValidator VALIDATOR = new ExtendedValidator(new LocalValidatorFactoryBean());

	/**
	 * Creates the internal validator.
	 *
	 * @return The internal validator.
	 */
	@Bean(name = "internalValidator")
	public Validator createInternalValidator() {
		return new LocalValidatorFactoryBean();
	}

	/**
	 * Creates the extended validator.
	 *
	 * @return The extended validator.
	 */
	@Primary
	@Bean(name = "extendedValidator")
	public ExtendedValidator createExtendedValidator(
			final Validator validator) {
		ValidatorAutoConfiguration.VALIDATOR = new ExtendedValidator(validator);
		return ValidatorAutoConfiguration.VALIDATOR;
	}

}
