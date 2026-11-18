package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.coldis.library.service.jms.JmsRequestThreadsLimit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.ResourceAllocationException;

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
	 * The limit reports the permits in all and held, the permits held each time
	 * a request takes one, the waits for a permit and the refusals.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Reports how close requests come to the limit")
	public void testMeters() throws Exception {
		final SimpleMeterRegistry registry = new SimpleMeterRegistry();
		final JmsRequestThreadsLimit limit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofMillis(500));
		limit.setMeterRegistry(registry);
		limit.size(4);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
		try {
			// Two requests take the two permits without waiting.
			final Semaphore permits = limit.acquire();
			limit.acquire();
			Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.PERMITS_MAX).gauge().value());
			Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.PERMITS_HELD).gauge().value());
			Assertions.assertEquals(2, registry.get(JmsRequestThreadsLimit.HELD_AT_ACQUIRE).summary().count());
			Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.HELD_AT_ACQUIRE).summary().max());
			Assertions.assertEquals(0, registry.get(JmsRequestThreadsLimit.WAIT).timer().count());

			// A third waits the maximum wait and is refused.
			Assertions.assertThrows(ResourceAllocationException.class, limit::acquire);
			Assertions.assertEquals(1, registry.get(JmsRequestThreadsLimit.WAIT).timer().count());
			Assertions.assertTrue(registry.get(JmsRequestThreadsLimit.WAIT).timer().max(TimeUnit.MILLISECONDS) >= 500);
			Assertions.assertEquals(1.0, registry.get(JmsRequestThreadsLimit.REFUSED).counter().count());

			// A fourth waits until a holder gives its permit back, and takes it.
			final Thread holder = Thread.ofVirtual().start(() -> {
				while (!permits.hasQueuedThreads()) {
					Thread.onSpinWait();
				}
				permits.release();
			});
			Assertions.assertNotNull(limit.acquire());
			holder.join();
			Assertions.assertEquals(2, registry.get(JmsRequestThreadsLimit.WAIT).timer().count());
			Assertions.assertEquals(1.0, registry.get(JmsRequestThreadsLimit.REFUSED).counter().count());
			Assertions.assertEquals(3, registry.get(JmsRequestThreadsLimit.HELD_AT_ACQUIRE).summary().count());
			Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.PERMITS_HELD).gauge().value());

			// Given back, the permits read as free.
			permits.release(2);
			Assertions.assertEquals(0.0, registry.get(JmsRequestThreadsLimit.PERMITS_HELD).gauge().value());
		}
		finally {
			RequestContextHolder.resetRequestAttributes();
		}
	}

	/**
	 * The library ships the limit on, at half of the request threads, with a
	 * short wait.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("Ships the limit on, at half of the request threads")
	public void testShippedDefaults() throws IOException {
		final Properties libraryDefaults = PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"));
		Assertions.assertEquals("true", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".enabled"));
		Assertions.assertEquals("0.5", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".max-share"));
		Assertions.assertEquals("2s", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".maximum-wait"));
	}

}
