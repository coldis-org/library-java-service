package org.coldis.library.test.service.jms;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.health.HealthCheckService;
import org.coldis.library.service.jms.JmsRequestThreadsLimit;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;

/**
 * Limit on the request threads that may hold a pooled JMS connection at once,
 * through real requests: with more requests holding their connection than
 * request threads, as when every request is stuck on a broker, the requests
 * past the limit are refused after the maximum wait and free their threads, so
 * the health check still answers; threads that serve no request go on.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class JmsRequestThreadsLimitTest extends ContainerTestHelper {

	/**
	 * Service port.
	 */
	@Value("${local.server.port}")
	private Integer port;

	/**
	 * Limit.
	 */
	@Autowired
	private JmsRequestThreadsLimit jmsRequestThreadsLimit;

	/**
	 * Endpoints that take pooled connections on request threads.
	 */
	@Autowired
	private JmsRequestThreadsLimitTestService jmsRequestThreadsLimitTestService;

	/**
	 * Pooled connection factory.
	 */
	@Autowired
	@Qualifier("pooledJmsConnectionFactory")
	private ConnectionFactory connectionFactory;

	/**
	 * Web server application context.
	 */
	@Autowired
	private ServletWebServerApplicationContext webServerApplicationContext;

	/**
	 * Meter registry.
	 */
	@Autowired
	private MeterRegistry meterRegistry;

	/**
	 * Gets the URL of a test endpoint.
	 *
	 * @param  path Endpoint path.
	 * @return      The URL.
	 */
	private URI getUrl(
			final String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	/**
	 * The limit is half of the server's request threads. With more requests
	 * holding a connection than request threads, the ones past the limit are
	 * refused after the maximum wait, so the health check gets a thread and
	 * answers; without the limit every thread would wait with them. Once the
	 * held connections close, the permits come back.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("With every request thread after a JMS connection, the ones past the limit are refused and the health check answers")
	public void testRequestsPastTheLimitRefusedAndHealthCheckAnswers() throws Exception {
		final Integer maxThreads = ((AbstractProtocol<?>) ((TomcatWebServer) this.webServerApplicationContext.getWebServer()).getTomcat().getConnector()
				.getProtocolHandler()).getMaxThreads();
		final Integer maxPermits = this.jmsRequestThreadsLimit.getMaxPermits();
		Assertions.assertEquals(JmsRequestThreadsLimit.getMaxPermits(maxThreads, 0.5), maxPermits);
		final Integer heldRequestsCount = maxThreads + 5;
		final Double refusedBefore = this.meterRegistry.get(JmsRequestThreadsLimit.REFUSED).counter().count();
		final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		final List<CompletableFuture<HttpResponse<Void>>> heldRequests = new ArrayList<>();
		this.jmsRequestThreadsLimitTestService.hold();
		try {
			// More requests that hold their connection than request threads.
			for (int requestIndex = 0; requestIndex < heldRequestsCount; requestIndex++) {
				heldRequests.add(httpClient.sendAsync(HttpRequest.newBuilder(this.getUrl("/jms-request-threads-test/hold")).GET().build(),
						BodyHandlers.discarding()));
			}
			Assertions.assertTrue(TestHelper.waitUntilValid(this.jmsRequestThreadsLimit::getHeldPermits, heldPermits -> heldPermits >= maxPermits,
					TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			// The health check gets a thread a refused request freed.
			final HttpResponse<Void> healthCheck = httpClient
					.sendAsync(HttpRequest.newBuilder(this.getUrl("/health-check?" + HealthCheckService.SCOPE_PARAMETER + "=" + HealthCheckService.LIVENESS_SCOPE))
							.GET().build(), BodyHandlers.discarding())
					.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(200, healthCheck.statusCode());
			// Every request past the permits is refused by the limit, and only those.
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.meterRegistry.get(JmsRequestThreadsLimit.REFUSED).counter().count() - refusedBefore,
					refused -> refused >= (heldRequestsCount - maxPermits), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			Assertions.assertEquals(maxPermits, this.jmsRequestThreadsLimit.getHeldPermits());
			Assertions.assertTrue(TestHelper.waitUntilValid(() -> heldRequests.stream().filter(CompletableFuture::isDone).map(CompletableFuture::join)
					.filter(response -> response.statusCode() >= 400).count(), refusedResponses -> refusedResponses == (heldRequestsCount - maxPermits),
					TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			// A thread that serves no request is not limited.
			try (Connection connection = this.connectionFactory.createConnection()) {
				Assertions.assertNotNull(connection.getMetaData());
			}
		}
		finally {
			this.jmsRequestThreadsLimitTestService.release();
			CompletableFuture.allOf(heldRequests.toArray(CompletableFuture[]::new)).get(1, TimeUnit.MINUTES);
		}
		Assertions.assertEquals(heldRequestsCount - maxPermits,
				this.meterRegistry.get(JmsRequestThreadsLimit.REFUSED).counter().count() - refusedBefore, 0.0);
		// The permits come back once the held connections close.
		Assertions.assertTrue(TestHelper.waitUntilValid(this.jmsRequestThreadsLimit::getHeldPermits, heldPermits -> heldPermits == 0, TestHelper.LONG_WAIT,
				TestHelper.VERY_SHORT_WAIT));
		final HttpResponse<Void> connectedRequest = httpClient.send(HttpRequest.newBuilder(this.getUrl("/jms-request-threads-test/connect")).GET().build(),
				BodyHandlers.discarding());
		Assertions.assertEquals(200, connectedRequest.statusCode());
	}

}
