package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;

import org.coldis.library.service.jms.JmsRequestThreadsLimit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Covers how the limit on request threads holding pooled JMS connections is
 * sized, and when it applies.
 */
@DisplayName("JmsRequestThreadsLimit — share of the request threads that may hold a JMS connection")
public class JmsRequestThreadsLimitUnitTest {

	/**
	 * The permits are the share of the request threads, at least 1, and none
	 * when the server has no thread count.
	 */
	@Test
	@DisplayName("Sizes the permits as a share of the request threads")
	public void testMaxPermits() {
		Assertions.assertEquals(10, JmsRequestThreadsLimit.getMaxPermits(20, 0.5));
		Assertions.assertEquals(50, JmsRequestThreadsLimit.getMaxPermits(100, 0.5));
		Assertions.assertEquals(12, JmsRequestThreadsLimit.getMaxPermits(25, 0.5));
		Assertions.assertEquals(1, JmsRequestThreadsLimit.getMaxPermits(1, 0.5));
		Assertions.assertNull(JmsRequestThreadsLimit.getMaxPermits(-1, 0.5));
		Assertions.assertNull(JmsRequestThreadsLimit.getMaxPermits(0, 0.5));
		Assertions.assertNull(JmsRequestThreadsLimit.getMaxPermits(null, 0.5));
	}

	/**
	 * A limit that is off is never sized, and a sized limit does not apply
	 * outside a request thread.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Applies only when on, and only to request threads")
	public void testAppliesWhenOnAndOnRequestThreads() throws Exception {
		final JmsRequestThreadsLimit disabledLimit = new JmsRequestThreadsLimit(false, 0.5, Duration.ofSeconds(2));
		disabledLimit.size(20);
		Assertions.assertNull(disabledLimit.getMaxPermits());
		Assertions.assertNull(disabledLimit.acquire());
		final JmsRequestThreadsLimit enabledLimit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofSeconds(2));
		enabledLimit.size(20);
		Assertions.assertEquals(10, enabledLimit.getMaxPermits());
		// This test thread serves no request.
		Assertions.assertNull(enabledLimit.acquire());
		Assertions.assertEquals(0, enabledLimit.getHeldPermits());
	}

	/**
	 * The library ships the limit off, at half of the request threads, with a
	 * short wait.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("Ships the limit off, at half of the request threads")
	public void testShippedDefaults() throws IOException {
		final Properties libraryDefaults = PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"));
		Assertions.assertEquals("false", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".enabled"));
		Assertions.assertEquals("0.5", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".max-share"));
		Assertions.assertEquals("2s", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".maximum-wait"));
	}

}
