package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.coldis.library.service.jms.JmsRequestThreadsLimit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

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
		Assertions.assertEquals(10, JmsRequestThreadsLimit.calculateMaxPermits(20, 0.5));
		Assertions.assertEquals(50, JmsRequestThreadsLimit.calculateMaxPermits(100, 0.5));
		Assertions.assertEquals(12, JmsRequestThreadsLimit.calculateMaxPermits(25, 0.5));
		Assertions.assertEquals(1, JmsRequestThreadsLimit.calculateMaxPermits(1, 0.5));
		Assertions.assertEquals(29, JmsRequestThreadsLimit.calculateMaxPermits(100, 0.29));
		Assertions.assertEquals(100, JmsRequestThreadsLimit.calculateMaxPermits(100, 1.0));
		Assertions.assertNull(JmsRequestThreadsLimit.calculateMaxPermits(-1, 0.5));
		Assertions.assertNull(JmsRequestThreadsLimit.calculateMaxPermits(0, 0.5));
		Assertions.assertNull(JmsRequestThreadsLimit.calculateMaxPermits(null, 0.5));
	}

	/**
	 * A share that is not above 0 and at most 1 stops the service from starting,
	 * instead of turning into a single permit or into no limit, and so does a
	 * missing or negative wait.
	 */
	@Test
	@DisplayName("Refuses a share that is not above 0 and at most 1, and a missing or negative wait")
	public void testInvalidSettingsRefused() {
		for (final Double maxShare : new Double[] { null, 0.0, -0.5, 1.5, Double.NaN }) {
			Assertions.assertThrows(IllegalArgumentException.class, () -> new JmsRequestThreadsLimit(true, maxShare, Duration.ofSeconds(2)), "share " + maxShare);
		}
		Assertions.assertDoesNotThrow(() -> new JmsRequestThreadsLimit(true, 1.0, Duration.ofSeconds(2)));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new JmsRequestThreadsLimit(true, 0.5, null));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new JmsRequestThreadsLimit(true, 0.5, Duration.ofSeconds(-1)));
		Assertions.assertDoesNotThrow(() -> new JmsRequestThreadsLimit(true, 0.5, Duration.ZERO));
	}

	/**
	 * Starts a web server on a free port.
	 *
	 * @param  isOnVirtualThreads Whether requests run on virtual threads.
	 * @return                    The server, started.
	 */
	private static TomcatWebServer startServer(
			final Boolean isOnVirtualThreads) {
		final TomcatServletWebServerFactory serverFactory = new TomcatServletWebServerFactory(0);
		if (isOnVirtualThreads) {
			// As Spring Boot sets it with spring.threads.virtual.enabled.
			serverFactory.addProtocolHandlerCustomizers(protocol -> protocol.setExecutor(new VirtualThreadExecutor("jms-limit-test-")));
		}
		final TomcatWebServer server = (TomcatWebServer) serverFactory.getWebServer();
		server.start();
		return server;
	}

	/**
	 * Tells the limit a web server started.
	 *
	 * @param limit           Limit.
	 * @param server          Server.
	 * @param serverNamespace Namespace of its context: null for the main server,
	 *                            "management" for a separate management server.
	 */
	private static void serverStarted(
			final JmsRequestThreadsLimit limit,
			final TomcatWebServer server,
			final String serverNamespace) {
		final ServletWebServerApplicationContext context = new ServletWebServerApplicationContext();
		context.setServerNamespace(serverNamespace);
		limit.onApplicationEvent(new ServletWebServerInitializedEvent(server, context));
	}

	/**
	 * The limit is sized from the main server's request threads, and left off by
	 * a separate management server and by a main server whose requests run on
	 * virtual threads, which has no thread count.
	 */
	@Test
	@DisplayName("Is sized by the main server, and not by a management server or one on virtual threads")
	public void testSizedByTheMainServerOnly() {
		final TomcatWebServer platformThreadsServer = JmsRequestThreadsLimitUnitTest.startServer(false);
		final TomcatWebServer virtualThreadsServer = JmsRequestThreadsLimitUnitTest.startServer(true);
		try {
			final JmsRequestThreadsLimit managementLimit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofSeconds(2));
			JmsRequestThreadsLimitUnitTest.serverStarted(managementLimit, platformThreadsServer, "management");
			Assertions.assertNull(managementLimit.getMaxPermits());

			final JmsRequestThreadsLimit virtualThreadsLimit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofSeconds(2));
			JmsRequestThreadsLimitUnitTest.serverStarted(virtualThreadsLimit, virtualThreadsServer, null);
			Assertions.assertNull(virtualThreadsLimit.getMaxPermits());

			// Tomcat's default of 200 request threads.
			final JmsRequestThreadsLimit mainLimit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofSeconds(2));
			JmsRequestThreadsLimitUnitTest.serverStarted(mainLimit, platformThreadsServer, null);
			Assertions.assertEquals(100, mainLimit.getMaxPermits());
		}
		finally {
			platformThreadsServer.destroy();
			virtualThreadsServer.destroy();
		}
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
		disabledLimit.sizeFromRequestThreads(20);
		Assertions.assertNull(disabledLimit.getMaxPermits());
		Assertions.assertNull(disabledLimit.acquire());
		final JmsRequestThreadsLimit enabledLimit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofSeconds(2));
		enabledLimit.sizeFromRequestThreads(20);
		Assertions.assertEquals(10, enabledLimit.getMaxPermits());
		// This test thread serves no request.
		Assertions.assertNull(enabledLimit.acquire());
		Assertions.assertEquals(0, enabledLimit.getHeldPermits());
	}

	/**
	 * The limit reports the permits in all and held, the permits held each time
	 * a request takes one, the waits for a permit and the refusals. A refused
	 * request gets a message that says nothing of the limit.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Reports how close requests come to the limit")
	public void testMeters() throws Exception {
		final SimpleMeterRegistry registry = new SimpleMeterRegistry();
		final JmsRequestThreadsLimit limit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofMillis(500));
		limit.setMeterRegistry(registry);
		limit.sizeFromRequestThreads(4);
		// Two requests take the two permits without waiting.
		final JmsRequestThreadsLimit.Permit firstPermit = RequestThreadTestHelper.startOnRequestThread(limit::acquire).get(5, TimeUnit.SECONDS);
		final JmsRequestThreadsLimit.Permit secondPermit = RequestThreadTestHelper.startOnRequestThread(limit::acquire).get(5, TimeUnit.SECONDS);
		Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.PERMITS_MAX).gauge().value());
		Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.PERMITS_HELD).gauge().value());
		Assertions.assertEquals(2, registry.get(JmsRequestThreadsLimit.HELD_AT_ACQUIRE).summary().count());
		Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.HELD_AT_ACQUIRE).summary().max());
		Assertions.assertEquals(0, registry.get(JmsRequestThreadsLimit.WAIT).timer().count());

		// A third waits the maximum wait and is refused.
		final ExecutionException refusal = Assertions.assertThrows(ExecutionException.class,
				() -> RequestThreadTestHelper.startOnRequestThread(limit::acquire).get(5, TimeUnit.SECONDS));
		Assertions.assertInstanceOf(ResourceAllocationException.class, refusal.getCause());
		Assertions.assertEquals(JmsRequestThreadsLimit.REFUSED_MESSAGE, refusal.getCause().getMessage());
		Assertions.assertEquals(1, registry.get(JmsRequestThreadsLimit.WAIT).timer().count());
		Assertions.assertTrue(registry.get(JmsRequestThreadsLimit.WAIT).timer().max(TimeUnit.MILLISECONDS) >= 500);
		Assertions.assertEquals(1.0, registry.get(JmsRequestThreadsLimit.REFUSED).counter().count());

		// A fourth waits until a holder gives its permit back, and takes it.
		final FutureTask<JmsRequestThreadsLimit.Permit> fourthRequest = RequestThreadTestHelper.startOnRequestThread(limit::acquire);
		Assertions.assertTrue(RequestThreadTestHelper.awaitWaitingRequest(limit));
		firstPermit.release();
		final JmsRequestThreadsLimit.Permit fourthPermit = fourthRequest.get(5, TimeUnit.SECONDS);
		Assertions.assertNotNull(fourthPermit);
		Assertions.assertEquals(2, registry.get(JmsRequestThreadsLimit.WAIT).timer().count());
		Assertions.assertEquals(1.0, registry.get(JmsRequestThreadsLimit.REFUSED).counter().count());
		Assertions.assertEquals(3, registry.get(JmsRequestThreadsLimit.HELD_AT_ACQUIRE).summary().count());
		Assertions.assertEquals(2.0, registry.get(JmsRequestThreadsLimit.PERMITS_HELD).gauge().value());

		// Given back, the permits read as free.
		secondPermit.release();
		fourthPermit.release();
		Assertions.assertEquals(0.0, registry.get(JmsRequestThreadsLimit.PERMITS_HELD).gauge().value());
	}

	/**
	 * A request thread holds one permit however many connections it opens, and
	 * gives it back when the last one closes; the next connection takes a new
	 * one.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A request thread holds one permit for all its connections")
	public void testOnePermitPerThread() throws Exception {
		final JmsRequestThreadsLimit limit = new JmsRequestThreadsLimit(true, 0.5, Duration.ofMillis(500));
		limit.sizeFromRequestThreads(2);
		Assertions.assertEquals(1, limit.getMaxPermits());
		RequestThreadTestHelper.startOnRequestThread(() -> {
			// With a single permit, a second connection of the same request would wait for the
			// first one and be refused if it took a permit of its own.
			final JmsRequestThreadsLimit.Permit firstHold = limit.acquire();
			final JmsRequestThreadsLimit.Permit secondHold = limit.acquire();
			Assertions.assertSame(firstHold, secondHold);
			Assertions.assertEquals(1, limit.getHeldPermits());
			secondHold.release();
			Assertions.assertEquals(1, limit.getHeldPermits());
			firstHold.release();
			Assertions.assertEquals(0, limit.getHeldPermits());
			// The next connection takes a new permit.
			final JmsRequestThreadsLimit.Permit nextHold = limit.acquire();
			Assertions.assertNotSame(firstHold, nextHold);
			Assertions.assertEquals(1, limit.getHeldPermits());
			nextHold.release();
			Assertions.assertEquals(0, limit.getHeldPermits());
			return null;
		}).get(5, TimeUnit.SECONDS);
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
		Assertions.assertEquals("2s", libraryDefaults.getProperty(JmsRequestThreadsLimit.PROPERTY_PREFIX + ".max-wait"));
	}

}
