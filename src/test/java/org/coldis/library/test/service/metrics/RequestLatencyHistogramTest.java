package org.coldis.library.test.service.metrics;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.coldis.library.test.service.health.HealthCheckServiceClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Server request latency histogram in the shared test context: a real request through the server, measured by Spring
 * Boot's server request observation, with the library's default service level objectives.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Server request latency histogram")
public class RequestLatencyHistogramTest extends ContainerTestHelper {

	/**
	 * Health check service client (makes a real request to the server).
	 */
	@Autowired
	private HealthCheckServiceClient healthCheckServiceClient;

	/**
	 * Meter registry of the context.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Tests that, after a request, the server requests timer of its URI has the default service level objective buckets
	 * and counts the request (client requests, which get no buckets, are checked in
	 * {@link RequestLatencyHistogramPropertiesTest}).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("After a request, the server requests timer has the default buckets")
	public void testServerRequestHistogram() throws Exception {
		this.healthCheckServiceClient.check();
		final List<Timer> serverTimers = this.meterRegistry.find("http.server.requests").timers().stream()
				.filter(timer -> (timer.getId().getTag("uri") != null) && timer.getId().getTag("uri").contains("health-check")).toList();
		Assertions.assertFalse(serverTimers.isEmpty());
		for (final Timer serverTimer : serverTimers) {
			Assertions.assertArrayEquals(new double[] { 50, 100, 250, 500, 1000, 2000, 5000, 10000 },
					Arrays.stream(serverTimer.takeSnapshot().histogramCounts()).mapToDouble(bucket -> bucket.bucket(TimeUnit.MILLISECONDS)).toArray());
			Assertions.assertTrue(serverTimer.count() > 0);
		}
	}

}
