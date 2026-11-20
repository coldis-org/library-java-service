package org.coldis.library.test.service.metrics;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.PropertiesMeterFilter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The server request latency histogram default of the library properties ({@code service.properties}), as Spring Boot
 * binds it, and how a service overrides it. No Spring context: the library defaults (and a service's override, in a
 * property source that takes precedence, as its application properties do) are bound to Spring Boot's metrics
 * properties and applied through Spring Boot's properties meter filter, on a simple meter registry.
 */
@DisplayName("Server request latency histogram default")
public class RequestLatencyHistogramPropertiesTest {

	/**
	 * Default service level objectives of the server requests, in milliseconds.
	 */
	private static final double[] DEFAULT_OBJECTIVES_MILLIS = { 50, 100, 250, 500, 1000, 2000, 5000, 10000 };

	/**
	 * Creates a meter registry with Spring Boot's properties meter filter, from the library defaults and the service
	 * properties.
	 *
	 * @param  serviceProperties Service properties (take precedence over the library defaults).
	 * @return                   The meter registry.
	 * @throws IOException       If the library defaults cannot be read.
	 */
	private static MeterRegistry createMeterRegistry(
			final Map<String, Object> serviceProperties) throws IOException {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addLast(new MapPropertySource("service", serviceProperties));
		environment.getPropertySources()
				.addLast(new PropertiesPropertySource("library", PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"))));
		final MetricsProperties metricsProperties = Binder.get(environment).bindOrCreate("management.metrics", MetricsProperties.class);
		final MeterRegistry meterRegistry = new SimpleMeterRegistry();
		meterRegistry.config().meterFilter(new PropertiesMeterFilter(metricsProperties));
		return meterRegistry;
	}

	/**
	 * Gets the histogram bucket boundaries of a timer, in milliseconds.
	 *
	 * @param  buckets Histogram buckets.
	 * @return         The bucket boundaries, in milliseconds.
	 */
	private static double[] getBoundariesMillis(
			final CountAtBucket[] buckets) {
		return Arrays.stream(buckets).mapToDouble(bucket -> bucket.bucket(TimeUnit.MILLISECONDS)).toArray();
	}

	/**
	 * Records a server request and gets the histogram bucket boundaries of the server requests timer.
	 *
	 * @param  meterRegistry Meter registry.
	 * @return               The bucket boundaries, in milliseconds.
	 */
	private static double[] recordServerRequest(
			final MeterRegistry meterRegistry) {
		final Timer timer = Timer.builder("http.server.requests").tag("uri", "/test").register(meterRegistry);
		timer.record(Duration.ofMillis(120));
		return RequestLatencyHistogramPropertiesTest.getBoundariesMillis(timer.takeSnapshot().histogramCounts());
	}

	/**
	 * Tests the default: server requests get the service level objective buckets; active server requests and client
	 * requests get none.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("By default, server requests have the service level objective buckets; active server requests and client requests have none")
	public void testDefault() throws IOException {
		final MeterRegistry meterRegistry = RequestLatencyHistogramPropertiesTest.createMeterRegistry(Map.of());
		Assertions.assertArrayEquals(RequestLatencyHistogramPropertiesTest.DEFAULT_OBJECTIVES_MILLIS,
				RequestLatencyHistogramPropertiesTest.recordServerRequest(meterRegistry));
		final LongTaskTimer activeTimer = LongTaskTimer.builder("http.server.requests.active").register(meterRegistry);
		Assertions.assertEquals(0, activeTimer.takeSnapshot().histogramCounts().length);
		final Timer clientTimer = Timer.builder("http.client.requests").register(meterRegistry);
		clientTimer.record(Duration.ofMillis(120));
		Assertions.assertEquals(0, clientTimer.takeSnapshot().histogramCounts().length);
	}

	/**
	 * Tests that a service changes the buckets.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("A service's own list replaces the default buckets")
	public void testOverride() throws IOException {
		final MeterRegistry meterRegistry = RequestLatencyHistogramPropertiesTest
				.createMeterRegistry(Map.of("management.metrics.distribution.slo.http.server.requests", "200ms,1s"));
		Assertions.assertArrayEquals(new double[] { 200, 1000 }, RequestLatencyHistogramPropertiesTest.recordServerRequest(meterRegistry));
	}

	/**
	 * Tests that a service turns the buckets off with an empty value.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("An empty value turns the buckets off")
	public void testDisabled() throws IOException {
		final MeterRegistry meterRegistry = RequestLatencyHistogramPropertiesTest
				.createMeterRegistry(Map.of("management.metrics.distribution.slo.http.server.requests", ""));
		Assertions.assertEquals(0, RequestLatencyHistogramPropertiesTest.recordServerRequest(meterRegistry).length);
	}

}
