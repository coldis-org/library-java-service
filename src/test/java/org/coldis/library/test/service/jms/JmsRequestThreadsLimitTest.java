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

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;

/**
 * Limit on the request threads that may hold a pooled JMS connection at once,
 * through real requests: with every permit taken by requests that hold their
 * connection, as requests stuck on a broker would, a further request that needs
 * a connection fails after the maximum wait, while requests that need none, and
 * threads that serve no request, go on.
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
	 * The limit is half of the server's request threads; past it, a request that
	 * needs a connection fails after the maximum wait, while the health check
	 * and a thread that serves no request go on; once the held connections
	 * close, the permits come back.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Past the limit, a request that needs a JMS connection fails after the wait and the others go on")
	public void testRequestsPastTheLimitFailAfterTheWait() throws Exception {
		final Integer maxThreads = ((AbstractProtocol<?>) ((TomcatWebServer) this.webServerApplicationContext.getWebServer()).getTomcat().getConnector()
				.getProtocolHandler()).getMaxThreads();
		final Integer maxPermits = this.jmsRequestThreadsLimit.getMaxPermits();
		Assertions.assertEquals(JmsRequestThreadsLimit.getMaxPermits(maxThreads, 0.5), maxPermits);
		final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		final List<CompletableFuture<HttpResponse<Void>>> heldRequests = new ArrayList<>();
		this.jmsRequestThreadsLimitTestService.hold();
		try {
			// Takes every permit with requests that hold their connection.
			for (int requestIndex = 0; requestIndex < maxPermits; requestIndex++) {
				heldRequests.add(httpClient.sendAsync(HttpRequest.newBuilder(this.getUrl("/jms-request-threads-test/hold")).GET().build(),
						BodyHandlers.discarding()));
			}
			Assertions.assertTrue(TestHelper.waitUntilValid(this.jmsRequestThreadsLimit::getHeldPermits, heldPermits -> heldPermits >= maxPermits,
					TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			// A further request that needs a connection fails after the maximum wait.
			final long refusedStartNanos = System.nanoTime();
			final HttpResponse<Void> refusedRequest = httpClient.send(HttpRequest.newBuilder(this.getUrl("/jms-request-threads-test/connect")).GET().build(),
					BodyHandlers.discarding());
			final long refusedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - refusedStartNanos);
			Assertions.assertTrue(refusedRequest.statusCode() >= 400, "status " + refusedRequest.statusCode());
			Assertions.assertTrue((refusedMillis >= 1500) && (refusedMillis < 10000), refusedMillis + " ms");
			// A request that needs no connection still gets a thread.
			final HttpResponse<Void> healthCheck = httpClient
					.sendAsync(HttpRequest.newBuilder(this.getUrl("/health-check")).GET().build(), BodyHandlers.discarding()).get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(healthCheck.statusCode() > 0);
			// A thread that serves no request is not limited.
			try (Connection connection = this.connectionFactory.createConnection()) {
				Assertions.assertNotNull(connection.getMetaData());
			}
		}
		finally {
			this.jmsRequestThreadsLimitTestService.release();
			CompletableFuture.allOf(heldRequests.toArray(CompletableFuture[]::new)).get(1, TimeUnit.MINUTES);
		}
		// The permits come back once the held connections close.
		Assertions.assertTrue(TestHelper.waitUntilValid(this.jmsRequestThreadsLimit::getHeldPermits, heldPermits -> heldPermits == 0, TestHelper.LONG_WAIT,
				TestHelper.VERY_SHORT_WAIT));
		final HttpResponse<Void> connectedRequest = httpClient.send(HttpRequest.newBuilder(this.getUrl("/jms-request-threads-test/connect")).GET().build(),
				BodyHandlers.discarding());
		Assertions.assertEquals(200, connectedRequest.statusCode());
	}

}
