package org.coldis.library.test.service.properties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.coldis.library.service.properties.PropertiesConnectorCustomizer;
import org.coldis.library.service.properties.PropertiesService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;

import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Properties service test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class PropertiesServiceTest extends ContainerTestHelper {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesServiceTest.class);

	/**
	 * Service port.
	 */
	@Value("${local.server.port}")
	private Integer port;

	/**
	 * Test properties.
	 */
	@Autowired
	private TestProperties1 testProperties1;

	/**
	 * Rest template.
	 */
	@Autowired
	private RestTemplate restTemplate;

	/** Datasource. */
	@Autowired
	private DataSource dataSource;

	/**
	 * Properties connector.
	 */
	@Autowired
	private PropertiesConnectorCustomizer propertiesConnectorCustomizer;

	/**
	 * Endpoint that holds request threads.
	 */
	@Autowired
	private PropertiesConnectorTestService propertiesConnectorTestService;

	/**
	 * Web server application context.
	 */
	@Autowired
	private ServletWebServerApplicationContext webServerApplicationContext;

	/**
	 * JMS connection factory.
	 */
	@Autowired
	private JmsPoolConnectionFactory connectionFactory;

	/** Jms template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** Original datasource URL from environment. */
	@Value("${spring.datasource.url}")
	private String originalJdbcUrl;

	/** Original Artemis port from environment. */
	@Value("${ARTEMIS_CONTAINER_61616}")
	private String originalArtemisPort;

	/** Resets shared singletons after each test to their initial environment-based values. */
	@org.junit.jupiter.api.AfterEach
	public void tearDown() {
		try {
			this.restTemplate.put("http://localhost:" + this.port + "/properties/string/testProperties1/property1", 1, Void.class);
			this.restTemplate.put("http://localhost:" + this.port + "/properties/long/testProperties1/property2", 1, Void.class);
			this.restTemplate.put("http://localhost:" + this.port + "/properties/integer/testProperties1/property3", 1, Void.class);
			this.restTemplate.put("http://localhost:" + this.port + "/properties/double/testProperties1/property4", 1, Void.class);
			this.restTemplate.put("http://localhost:" + this.port + "/properties/float/testProperties1/property5", 1, Void.class);
			this.restTemplate.put("http://localhost:" + this.port + "/properties/boolean/testProperties1/property6", true, Void.class);
		} catch (final Exception exception) {
		}
		try {
			this.restTemplate.put("http://localhost:" + this.port + "/properties/string/dataSource/pool.dataSource.jdbcUrl?fieldAccess=true",
					this.originalJdbcUrl, Void.class);
		} catch (final Exception exception) {
		}
		try {
			this.restTemplate.put(
					"http://localhost:" + this.port
							+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.topologyArray.0.a.params.host?fieldAccess=true",
					"localhost", Void.class);
			this.restTemplate.put(
					"http://localhost:" + this.port
							+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.host?fieldAccess=true",
					"localhost", Void.class);
			this.restTemplate.put(
					"http://localhost:" + this.port
							+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.topologyArray.0.a.params.port?fieldAccess=true",
					this.originalArtemisPort, Void.class);
			this.restTemplate.put(
					"http://localhost:" + this.port
							+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.port?fieldAccess=true",
					this.originalArtemisPort, Void.class);
			this.connectionFactory.clear();
		} catch (final Exception exception) {
		}
	}

	/** A value set through the endpoint is logged masked: a password-like string by its length only. */
	@Test
	@DisplayName("A value set through the endpoint is logged masked")
	public void testPropertyValueLoggedMasked() {
		final ch.qos.logback.classic.Logger propertiesLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PropertiesService.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		propertiesLogger.addAppender(appender);
		try {
			this.restTemplate.put("http://localhost:" + this.port + "/properties/string/testProperties1/property1", "FAKE_TEST_PASSWORD", Void.class);
			Assertions.assertEquals("FAKE_TEST_PASSWORD", this.testProperties1.getProperty1());
			final List<ILoggingEvent> events = List.copyOf(appender.list);
			Assertions.assertTrue(events.stream().anyMatch(event -> (event.getLevel() == Level.INFO)
					&& event.getFormattedMessage().contains("property1 to <18 characters> in bean testProperties1")));
			// At any level: the secret must not reach the log at all.
			Assertions.assertTrue(events.stream().noneMatch(event -> event.getFormattedMessage().contains("FAKE_TEST_PASSWORD")));
		}
		finally {
			propertiesLogger.detachAppender(appender);
		}
	}

	/** Test properties update. */
	@Test
	public void testPropertiesUpdate() {
		// Validates initial property value.
		Assertions.assertEquals("1", this.testProperties1.getProperty1());
		// Updates property.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/string/testProperties1/property1", 2, Void.class);
		// Validates updated property value.
		Assertions.assertEquals("2", this.testProperties1.getProperty1());

		// Validates initial property value.
		Assertions.assertEquals(1L, this.testProperties1.getProperty2());
		// Updates property.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/long/testProperties1/property2", 2, Void.class);
		// Validates updated property value.
		Assertions.assertEquals(2L, this.testProperties1.getProperty2());

		// Validates initial property value.
		Assertions.assertEquals(1, this.testProperties1.getProperty3());
		// Updates property.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/integer/testProperties1/property3", 2, Void.class);
		// Validates updated property value.
		Assertions.assertEquals(2, this.testProperties1.getProperty3());

		// Validates initial property value.
		Assertions.assertEquals(1D, this.testProperties1.getProperty4());
		// Updates property.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/double/testProperties1/property4", 2, Void.class);
		// Validates updated property value.
		Assertions.assertEquals(2D, this.testProperties1.getProperty4());

		// Validates initial property value.
		Assertions.assertEquals(1F, this.testProperties1.getProperty5());
		// Updates property.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/float/testProperties1/property5", 2, Void.class);
		// Validates updated property value.
		Assertions.assertEquals(2F, this.testProperties1.getProperty5());

		// Validates initial property value.
		Assertions.assertEquals(true, this.testProperties1.getProperty6());
		// Updates property.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/boolean/testProperties1/property6", false, Void.class);
		// Validates updated property value.
		Assertions.assertEquals(false, this.testProperties1.getProperty6());

	}

	/**
	 * Tests updating database connection.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testDatabaseConnectionUpdate() throws Exception {
		// Validates initial property value.
		final boolean execute = this.dataSource.getConnection().prepareCall("SELECT 1").execute();

		// Updates property to an invalid database connection.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/string/dataSource/pool.dataSource.jdbcUrl?fieldAccess=true",
				"jdbc:postgresql://localhost:1234/test", Void.class);

		// Makes sure the connection is not valid.
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			try {
				PropertiesServiceTest.LOGGER.info("Testing database connection...");
				final Connection connection = this.dataSource.getConnection();
				connection.prepareCall("SELECT 1").execute();
				((HikariDataSource) this.dataSource).evictConnection(connection);
				return Boolean.FALSE;
			}
			catch (final Exception exception) {
				PropertiesServiceTest.LOGGER.error("Error while executing database connection test.", exception);
				return Boolean.TRUE;
			}
		}, valid -> valid, TestHelper.VERY_LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

		// Updates property to a valid database connection.
		this.restTemplate.put("http://localhost:" + this.port + "/properties/string/dataSource/pool.dataSource.jdbcUrl?fieldAccess=true",
				"jdbc:postgresql://localhost:" + PropertiesServiceTest.POSTGRES_CONTAINER.getMappedPort(5432) + "/test", Void.class);

		// Makes sure the connection is valid.
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			try {
				PropertiesServiceTest.LOGGER.info("Testing database connection...");
				final Connection connection = this.dataSource.getConnection();
				connection.prepareCall("SELECT 1").execute();
				((HikariDataSource) this.dataSource).evictConnection(connection);
				return Boolean.TRUE;
			}
			catch (final Exception exception) {
				PropertiesServiceTest.LOGGER.error("Error while executing database connection test.", exception);
				return Boolean.FALSE;
			}
		}, valid -> valid, TestHelper.VERY_LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

	}

	/**
	 * Tests updating Artemis connection.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testArtemisConnectionUpdate() throws Exception {
		// Validates initial property value.
		this.jmsTemplate.convertAndSend("testQueue", "testMessage");

		// Updates property to an invalid database connection.
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.topologyArray.0.a.params.host?fieldAccess=true",
				"192.168.0.200", Void.class);
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.host?fieldAccess=true",
				"192.168.0.200", Void.class);
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.topologyArray.0.a.params.port?fieldAccess=true",
				"1234", Void.class);
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.port?fieldAccess=true",
				"1234", Void.class);
		this.connectionFactory.clear();

		// Makes sure the connection is not valid.
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			try {
				PropertiesServiceTest.LOGGER.info("Testing Artemis connection...");
				this.connectionFactory.clear();
				this.jmsTemplate.convertAndSend("testQueue", "testMessage");
				return Boolean.FALSE;
			}
			catch (final Exception exception) {
				PropertiesServiceTest.LOGGER.error("Error while executing Artemis connection test.", exception);
				return Boolean.TRUE;
			}
		}, valid -> valid, TestHelper.VERY_LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

		// Updates property to a valid database connection.
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.topologyArray.0.a.params.host?fieldAccess=true",
				"127.0.0.1", Void.class);
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.host?fieldAccess=true",
				"127.0.0.1", Void.class);
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.topologyArray.0.a.params.port?fieldAccess=true",
				PropertiesServiceTest.ARTEMIS_CONTAINER.getMappedPort(61616).toString(), Void.class);
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.port?fieldAccess=true",
				PropertiesServiceTest.ARTEMIS_CONTAINER.getMappedPort(61616).toString(), Void.class);
		this.connectionFactory.clear();

		// Makes sure the connection is valid.
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			try {
				PropertiesServiceTest.LOGGER.info("Testing Artemis connection...");
				this.jmsTemplate.convertAndSend("testQueue", "testMessage");
				return Boolean.TRUE;
			}
			catch (final Exception exception) {
				PropertiesServiceTest.LOGGER.error("Error while executing Artemis connection test.", exception);
				return Boolean.FALSE;
			}
		}, valid -> valid, TestHelper.VERY_LONG_WAIT, TestHelper.VERY_SHORT_WAIT));

	}

	/**
	 * With every request thread of the main connector taken, an update on the
	 * main connector waits, and the same update on the properties connector runs.
	 * A few more requests than threads are held, so a thread another test frees
	 * goes to one of them, not to the waiting update.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The properties connector takes an update while the main connector has no thread left")
	public void testPropertiesConnectorWithMainThreadsTaken() throws Exception {
		final Connector mainConnector = ((TomcatWebServer) this.webServerApplicationContext.getWebServer()).getTomcat().getConnector();
		final AbstractProtocol<?> mainProtocol = (AbstractProtocol<?>) mainConnector.getProtocolHandler();
		final Integer mainMaxThreads = mainProtocol.getMaxThreads();
		final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		final List<CompletableFuture<HttpResponse<Void>>> heldRequests = new ArrayList<>();
		final List<CompletableFuture<HttpResponse<Void>>> mainUpdates = new ArrayList<>();
		this.propertiesConnectorTestService.hold();
		try {
			// Takes every request thread of the main connector.
			for (int requestIndex = 0; requestIndex < (mainMaxThreads + 5); requestIndex++) {
				heldRequests.add(httpClient.sendAsync(
						HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/properties-connector-test/wait")).GET().build(),
						BodyHandlers.discarding()));
			}
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> ((org.apache.tomcat.util.threads.ThreadPoolExecutor) mainProtocol.getExecutor()).getActiveCount(),
					activeThreads -> activeThreads >= mainMaxThreads, TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
			// An update on the main connector waits for a thread.
			mainUpdates.add(httpClient.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/properties/string/testProperties1/property1"))
					.PUT(BodyPublishers.ofString("3")).build(), BodyHandlers.discarding()));
			Assertions.assertThrows(TimeoutException.class, () -> mainUpdates.get(0).get(1, TimeUnit.SECONDS));
			Assertions.assertEquals("1", this.testProperties1.getProperty1());
			// The same update on the properties connector runs, well before the held requests give up, and while they still hold every
			// main thread.
			final HttpResponse<Void> propertiesConnectorUpdate = httpClient
					.sendAsync(HttpRequest.newBuilder(URI.create(this.getPropertiesConnectorUrl() + "/properties/string/testProperties1/property1"))
							.PUT(BodyPublishers.ofString("4")).build(), BodyHandlers.discarding())
					.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(200, propertiesConnectorUpdate.statusCode());
			Assertions.assertEquals("4", this.testProperties1.getProperty1());
			Assertions.assertTrue(((org.apache.tomcat.util.threads.ThreadPoolExecutor) mainProtocol.getExecutor()).getActiveCount() >= mainMaxThreads);
		}
		finally {
			this.propertiesConnectorTestService.release();
			heldRequests.addAll(mainUpdates);
			// Waits for the held requests, so the next test finds the threads free; a failure here must
			// not hide one above.
			try {
				CompletableFuture.allOf(heldRequests.toArray(CompletableFuture[]::new)).get(1, TimeUnit.MINUTES);
			}
			catch (final ExecutionException | TimeoutException exception) {
				PropertiesServiceTest.LOGGER.warn("Not every request held on the main connector ended well.", exception);
			}
		}
	}

	/**
	 * Gets the base URL of the properties connector.
	 *
	 * @return The base URL of the properties connector.
	 */
	private String getPropertiesConnectorUrl() {
		return "http://127.0.0.1:" + this.propertiesConnectorCustomizer.getLocalPort();
	}

	/**
	 * Sends a GET as written, with no client in between to normalize its path,
	 * and gets the response status.
	 *
	 * @param  port      Port.
	 * @param  rawPath   Path, as sent.
	 * @return           The response status.
	 * @throws Exception If the request fails.
	 */
	private static Integer getStatusAsSent(
			final Integer port,
			final String rawPath) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			final OutputStream output = socket.getOutputStream();
			output.write(("GET " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			output.flush();
			final String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
			return Integer.valueOf(statusLine.split(" ")[1]);
		}
	}

	/**
	 * The properties connector listens on the loopback address the library
	 * ships, and serves no path but the properties endpoint, judged as sent and
	 * as Tomcat resolves it.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The properties connector listens on loopback and serves the properties endpoint only")
	public void testPropertiesConnectorLoopbackAndPropertiesOnly() throws Exception {
		final Connector propertiesConnector = Arrays
				.stream(((TomcatWebServer) this.webServerApplicationContext.getWebServer()).getTomcat().getService().findConnectors())
				.filter(connector -> connector.getLocalPort() == this.propertiesConnectorCustomizer.getLocalPort()).findFirst().orElseThrow();
		Assertions.assertTrue(((AbstractProtocol<?>) propertiesConnector.getProtocolHandler()).getAddress().isLoopbackAddress());
		Assertions.assertEquals(2, ((AbstractProtocol<?>) propertiesConnector.getProtocolHandler()).getMaxThreads());
		final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		final HttpResponse<Void> healthCheckOnPropertiesConnector = httpClient.send(
				HttpRequest.newBuilder(URI.create(this.getPropertiesConnectorUrl() + "/health-check")).GET().build(), BodyHandlers.discarding());
		Assertions.assertEquals(404, healthCheckOnPropertiesConnector.statusCode());
		final HttpResponse<Void> healthCheckOnMainConnector = httpClient
				.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/health-check")).GET().build(), BodyHandlers.discarding());
		Assertions.assertNotEquals(404, healthCheckOnMainConnector.statusCode());
		// A path that only starts like the endpoint is outside it too.
		final HttpResponse<Void> pathOutsideTheEndpoint = httpClient.send(
				HttpRequest.newBuilder(URI.create(this.getPropertiesConnectorUrl() + "/properties-connector-test/wait")).GET().build(), BodyHandlers.discarding());
		Assertions.assertEquals(404, pathOutsideTheEndpoint.statusCode());
		// A path that starts with the endpoint and leaves it is outside it: Tomcat routes it, by the path it
		// resolves, to a servlet that answers on the main connector, and the properties connector refuses it.
		for (final String pathLeavingTheEndpoint : new String[] { "/properties/../", "/properties/%2e%2e/" }) {
			final String path = pathLeavingTheEndpoint + PropertiesConnectorTestServletConfiguration.PATH.substring(1) + "/any";
			Assertions.assertEquals(200, PropertiesServiceTest.getStatusAsSent(this.port, path), path);
			Assertions.assertEquals(404, PropertiesServiceTest.getStatusAsSent(this.propertiesConnectorCustomizer.getLocalPort(), path), path);
		}
		// A path sent from elsewhere that Tomcat resolves into the endpoint goes, by the path as sent, to another
		// handler on the main connector, and the properties connector refuses it.
		final String pathIntoTheEndpoint = "/properties-connector-test/any/../../properties/string/testProperties1/property1";
		Assertions.assertEquals(200, PropertiesServiceTest.getStatusAsSent(this.port, pathIntoTheEndpoint));
		Assertions.assertEquals(404, PropertiesServiceTest.getStatusAsSent(this.propertiesConnectorCustomizer.getLocalPort(), pathIntoTheEndpoint));
	}

}
