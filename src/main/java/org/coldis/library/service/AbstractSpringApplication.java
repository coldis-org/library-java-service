package org.coldis.library.service;

import java.nio.charset.Charset;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

/**
 * Abstract spring application.
 */
public abstract class AbstractSpringApplication {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSpringApplication.class);

	/**
	 * Runs the Spring application.
	 *
	 * @param applicationClass Application configuration class.
	 * @param arguments        Initialization arguments.
	 */
	public static void run(final Class<?> applicationClass, final String[] arguments) {
		// Logs the locale and charset.
		AbstractSpringApplication.LOGGER.info("Spring application locale is: '" + Locale.getDefault()
		+ "'. Charset is '" + Charset.defaultCharset() + "'.");
		// Creates a new instance of the Spring Boot application.
		final SpringApplication springApplication = new SpringApplication(applicationClass);
		// Starts the application.
		springApplication.run(arguments);
	}

	/**
	 * Runs the generic application.
	 *
	 * @param args Initialization arguments.
	 */
	public static void main(final String[] args) {
		AbstractSpringApplication.run(AbstractSpringApplication.class, args);
	}

}
