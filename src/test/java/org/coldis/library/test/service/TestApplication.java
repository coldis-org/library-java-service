package org.coldis.library.test.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

/**
 * Test application.
 */
@SpringBootApplication(scanBasePackages = "org.coldis")
public class TestApplication {

	/**
	 * Runs the test application.
	 *
	 * @param args Application arguments.
	 */
	public static void main(
			final String[] args) {
		SpringApplication.run(TestApplication.class, args);
	}

}
