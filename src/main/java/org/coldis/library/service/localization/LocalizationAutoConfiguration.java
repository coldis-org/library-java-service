package org.coldis.library.service.localization;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;

/**
 * Default configuration.
 */
@Configuration
public class LocalizationAutoConfiguration {

	/**
	 * Creates the locale resolver.
	 *
	 * @return The locale resolver.
	 */
	@Bean
	@Primary
	public LocaleResolver localeResolver() {
		// Creates the locale resolver.
		final SessionLocaleResolver sessionLocaleResolver = new SessionLocaleResolver();
		// Default locale is US.
		sessionLocaleResolver.setDefaultLocale(Locale.US);
		// Returns the configured locale resolver.
		return sessionLocaleResolver;
	}

	/**
	 * Creates the message source.
	 *
	 * @return The message source.
	 */
	@Bean
	@Primary
	public ReloadableResourceBundleMessageSource messageSource() {
		// Creates the message source.
		final ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
		// Sets the base path and expiration (30 minutes).
		messageSource.setBasename("classpath:locale/messages");
		messageSource.setCacheSeconds(1800);
		messageSource.setUseCodeAsDefaultMessage(true);
		messageSource.setFallbackToSystemLocale(true);
		// Returns the configured message source.
		return messageSource;
	}

}
