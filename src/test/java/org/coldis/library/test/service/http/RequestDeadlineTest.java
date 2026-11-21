package org.coldis.library.test.service.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.coldis.library.service.client.ServiceClientRequestDeadline;
import org.coldis.library.service.http.RequestDeadlineAutoConfiguration;
import org.coldis.library.service.http.RequestDeadlineFilter;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Request deadline in the shared test context: the filter on the running server,
 * and a request whose controller calls a generated client.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Request deadline")
public class RequestDeadlineTest extends ContainerTestHelper {

	/**
	 * HTTP client of the tests (no deadline of its own).
	 */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/**
	 * Server port.
	 */
	@LocalServerPort
	private Integer port;

	/**
	 * Health check path.
	 */
	@Value("${org.coldis.configuration.health-check:/health-check}")
	private String healthCheckPath;

	/**
	 * Meter registry of the context.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Clears the test thread's deadline (the unit tests set none, but a failure
	 * must not leak one).
	 */
	@AfterEach
	public void clearDeadline() {
		ServiceClientRequestDeadline.setDeadlineNanos(null);
	}

	/**
	 * Sends a GET request to the server.
	 *
	 * @param  path        Path.
	 * @param  header      Deadline header ({@code null} for none).
	 * @return             The response.
	 * @throws Exception   If the request fails.
	 */
	private HttpResponse<String> get(
			final String path,
			final String header) throws Exception {
		final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).timeout(Duration.ofSeconds(10)).GET();
		if (header != null) {
			request.header(ServiceClientRequestDeadline.HEADER, header);
		}
		return RequestDeadlineTest.HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Gets the count of requests answered 504 on arrival.
	 *
	 * @return The count.
	 */
	private double getExpiredCount() {
		return this.meterRegistry.get(RequestDeadlineFilter.EXPIRED_COUNTER).counter().count();
	}

	/**
	 * A request with the header gets the deadline while its controller runs, and
	 * the request thread is left without it afterwards (the filter on its own,
	 * with a chain that records it).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("a header becomes the request's deadline, cleared after the request")
	public void testDeadlineStoredAndCleared() throws Exception {
		final RequestDeadlineFilter filter = new RequestDeadlineFilter("/health-check", null);
		final AtomicReference<Long> deadlineInChain = new AtomicReference<>();
		final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/any");
		request.addHeader(ServiceClientRequestDeadline.HEADER, "3000");
		final long beforeNanos = System.nanoTime();
		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain(new HttpServlet() {

			private static final long serialVersionUID = 1L;

			@Override
			protected void service(
					final HttpServletRequest servletRequest,
					final HttpServletResponse servletResponse) {
				deadlineInChain.set(ServiceClientRequestDeadline.getDeadlineNanos());
			}

		}));
		final long afterNanos = System.nanoTime();
		Assertions.assertNotNull(deadlineInChain.get());
		Assertions.assertTrue((deadlineInChain.get() - beforeNanos) <= Duration.ofMillis(3000).plusNanos(afterNanos - beforeNanos).toNanos());
		Assertions.assertTrue((deadlineInChain.get() - afterNanos) >= Duration.ofMillis(3000).minusNanos(afterNanos - beforeNanos).toNanos());
		Assertions.assertNull(ServiceClientRequestDeadline.getDeadlineNanos());
		// A deadline the thread already had is put back, not cleared.
		final long earlierDeadlineNanos = ServiceClientRequestDeadline.getDeadlineNanosIn(60_000);
		ServiceClientRequestDeadline.setDeadlineNanos(earlierDeadlineNanos);
		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
		Assertions.assertEquals(earlierDeadlineNanos, ServiceClientRequestDeadline.getDeadlineNanos());
		ServiceClientRequestDeadline.setDeadlineNanos(null);
		// On the running server.
		final HttpResponse<String> response = this.get("/request-deadline/remaining", "5000");
		Assertions.assertEquals(200, response.statusCode());
		final long remainingMillis = Long.parseLong(response.body());
		Assertions.assertTrue((remainingMillis > 3000) && (remainingMillis <= 5000), response::body);
	}

	/**
	 * A deadline already passed on arrival (zero or less) is answered 504 at once,
	 * without running the controller, and counted.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("a passed deadline is answered 504 without the controller")
	public void testExpiredAnswered504() throws Exception {
		final int controllerCalls = RequestDeadlineTestService.CONTROLLER_CALLS.get();
		final double expiredCount = this.getExpiredCount();
		for (final String header : List.of("0", "-25", " -1 ")) {
			final HttpResponse<String> response = this.get("/request-deadline/remaining", header);
			Assertions.assertEquals(504, response.statusCode(), header);
		}
		Assertions.assertEquals(controllerCalls, RequestDeadlineTestService.CONTROLLER_CALLS.get());
		Assertions.assertEquals(expiredCount + 3, this.getExpiredCount());
		// Without a meter registry, the filter still answers 504.
		final MockHttpServletResponse response = new MockHttpServletResponse();
		final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/any");
		request.addHeader(ServiceClientRequestDeadline.HEADER, "0");
		final MockFilterChain chain = new MockFilterChain();
		new RequestDeadlineFilter("/health-check", null).doFilter(request, response, chain);
		Assertions.assertEquals(504, response.getStatus());
		Assertions.assertNull(chain.getRequest());
		// A registry of its own counts it.
		final SimpleMeterRegistry ownRegistry = new SimpleMeterRegistry();
		new RequestDeadlineFilter("/health-check", ownRegistry).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
		Assertions.assertEquals(1, ownRegistry.get(RequestDeadlineFilter.EXPIRED_COUNTER).counter().count());
	}

	/**
	 * A request without the header, or with one that is not an integer, gets no
	 * deadline and is served as before.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("no header, or an invalid one, changes nothing")
	public void testAbsentOrInvalidHeader() throws Exception {
		for (final String header : new String[] { null, "", "soon", "1.5" }) {
			final HttpResponse<String> response = this.get("/request-deadline/remaining", header);
			Assertions.assertEquals(200, response.statusCode(), header);
			Assertions.assertEquals(RequestDeadlineTestService.NONE, response.body(), header);
		}
		Assertions.assertNull(RequestDeadlineFilter.parseTimeoutMillis(null));
		Assertions.assertNull(RequestDeadlineFilter.parseTimeoutMillis("  "));
		Assertions.assertNull(RequestDeadlineFilter.parseTimeoutMillis("99999999999999999999"));
		Assertions.assertEquals(250L, RequestDeadlineFilter.parseTimeoutMillis(" 250 "));
	}

	/**
	 * The health check is never filtered: a passed deadline does not answer it
	 * 504, and it gets no deadline.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("the health check is untouched")
	public void testHealthCheckUntouched() throws Exception {
		final double expiredCount = this.getExpiredCount();
		final HttpResponse<String> response = this.get(this.healthCheckPath, "0");
		Assertions.assertNotEquals(504, response.statusCode());
		Assertions.assertEquals(expiredCount, this.getExpiredCount());
		// The filter on its own: the health check path goes through, other paths do not.
		final MockHttpServletRequest healthRequest = new MockHttpServletRequest("GET", "/health-check");
		healthRequest.addHeader(ServiceClientRequestDeadline.HEADER, "0");
		final MockFilterChain healthChain = new MockFilterChain();
		new RequestDeadlineFilter("/health-check", null).doFilter(healthRequest, new MockHttpServletResponse(), healthChain);
		Assertions.assertNotNull(healthChain.getRequest());
		final MockHttpServletRequest otherRequest = new MockHttpServletRequest("GET", "/health-check-other");
		otherRequest.addHeader(ServiceClientRequestDeadline.HEADER, "0");
		final MockFilterChain otherChain = new MockFilterChain();
		new RequestDeadlineFilter("/health-check", null).doFilter(otherRequest, new MockHttpServletResponse(), otherChain);
		Assertions.assertNull(otherChain.getRequest());
	}

	/**
	 * End to end: a request with the header whose controller calls a generated
	 * client sends on what is left of the deadline (less the client's margin) as
	 * the call's read timeout, and a slow downstream answer is cut by it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("a controller's generated client call gets the smaller timeout")
	public void testDeadlinePropagatedToGeneratedClient() throws Exception {
		final HttpResponse<String> forwardedResponse = this.get("/request-deadline/forward-header", "3000");
		Assertions.assertEquals(200, forwardedResponse.statusCode(), forwardedResponse::body);
		final long forwardedMillis = Long.parseLong(forwardedResponse.body());
		Assertions.assertTrue((forwardedMillis > 2000) && (forwardedMillis <= 2900), forwardedResponse::body);
		// Without the header, the call carries none.
		Assertions.assertEquals(RequestDeadlineTestService.NONE, this.get("/request-deadline/forward-header", null).body());
		// The slow answer (2 s) is cut by the deadline (700 ms), not the 9 s read timeout.
		// The downstream request is sent (both controllers run), then cut.
		final int controllerCalls = RequestDeadlineTestService.CONTROLLER_CALLS.get();
		final long startNanos = System.nanoTime();
		final HttpResponse<String> slowResponse = this.get("/request-deadline/forward-slow", "700");
		final Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
		Assertions.assertTrue(slowResponse.statusCode() >= 500, slowResponse::body);
		Assertions.assertTrue(elapsed.compareTo(Duration.ofMillis(400)) > 0, elapsed::toString);
		Assertions.assertTrue(elapsed.compareTo(Duration.ofMillis(1900)) < 0, elapsed::toString);
		TestHelper.waitUntilValid(RequestDeadlineTestService.CONTROLLER_CALLS::get, calls -> calls >= (controllerCalls + 2), TestHelper.SHORT_WAIT,
				TestHelper.SHORT_WAIT / 10);
		Assertions.assertEquals(controllerCalls + 2, RequestDeadlineTestService.CONTROLLER_CALLS.get());
	}

	/**
	 * The switch: the filter is registered by default and when on, and not when
	 * off (a context runner of its own, so the shared context is not split).
	 */
	@Test
	@DisplayName("the switch registers the filter or not")
	public void testSwitch() {
		final WebApplicationContextRunner runner = new WebApplicationContextRunner().withUserConfiguration(RequestDeadlineAutoConfiguration.class);
		runner.run(context -> Assertions.assertEquals(1, context.getBeansOfType(FilterRegistrationBean.class).size()));
		runner.withPropertyValues(RequestDeadlineAutoConfiguration.ENABLED_PROPERTY + "=true")
				.run(context -> Assertions.assertEquals(1, context.getBeansOfType(FilterRegistrationBean.class).size()));
		runner.withPropertyValues(RequestDeadlineAutoConfiguration.ENABLED_PROPERTY + "=false")
				.run(context -> Assertions.assertTrue(context.getBeansOfType(FilterRegistrationBean.class).isEmpty()));
	}

}
