package org.coldis.library.service.controller;

import org.coldis.library.helper.DateTimeHelper;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Date/time formatter configuration.
 */
@Configuration
public class DateTimeFormatterAutoConfiguration implements WebMvcConfigurer {

	/**
	 * @see org.springframework.web.servlet.config.annotation.WebMvcConfigurer#addFormatters(org.springframework.format.FormatterRegistry)
	 */
	@Override
	public void addFormatters(
			final FormatterRegistry registry) {
		// Registers the date/time formatters to the service.
		final DateTimeFormatterRegistrar registrar = new DateTimeFormatterRegistrar();
		registrar.setDateFormatter(DateTimeHelper.DATE_TIME_FORMATTER);
		registrar.setDateTimeFormatter(DateTimeHelper.DATE_TIME_FORMATTER);
		registrar.setTimeFormatter(DateTimeHelper.DATE_TIME_FORMATTER);
		registrar.registerFormatters(registry);
	}

}
