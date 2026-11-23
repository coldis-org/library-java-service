package org.coldis.library.test.service.properties;

import java.sql.Connection;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.coldis.library.service.jms.ArtemisReconnectOnAddressChange;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
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
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
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
	 * JMS connection factory.
	 */
	@Autowired
	private JmsPoolConnectionFactory connectionFactory;

	/** Jms template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** Moves live Artemis connections to a changed broker address. */
	@Autowired
	private ArtemisReconnectOnAddressChange artemisReconnectOnAddressChange;

	/** Properties service. */
	@Autowired
	private PropertiesService propertiesService;

	/** JMS listener containers of the context. */
	@Autowired
	private JmsListenerEndpointRegistry jmsListenerEndpointRegistry;

	/** Original datasource URL from environment. */
	@Value("${spring.datasource.url}")
	private String originalJdbcUrl;

	/** Original Artemis port from environment. */
	@Value("${ARTEMIS_CONTAINER_61616}")
	private String originalArtemisPort;

	/**
	 * Whether every running listener container of the context has a consumer
	 * registered with its destination. The test context does not let the client
	 * reconnect, so a failed listener connection is replaced by the container's
	 * recovery, a few seconds later.
	 *
	 * @return Whether every listener is registered.
	 */
	private Boolean areJmsListenersRegistered() {
		return this.jmsListenerEndpointRegistry.getListenerContainers().stream()
				.filter(container -> container.isRunning() && (container instanceof DefaultMessageListenerContainer))
				.allMatch(container -> ((DefaultMessageListenerContainer) container).isRegisteredWithDestination());
	}

	/**
	 * Resets shared singletons after each test to their initial environment-based values.
	 *
	 * @throws Exception If the Artemis connections do not come back.
	 */
	@org.junit.jupiter.api.AfterEach
	public void tearDown() throws Exception {
		try {
			final Set<RemotingConnection> connectionsBeforeReverts = this.getLiveArtemisConnections();
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
				// A reconnection the reverts scheduled (after a test that changed the address and failed
				// before changing it back) runs now, and fails its connections, before the next test.
				final Boolean isReconnectScheduled = this.artemisReconnectOnAddressChange.isReconnectPending();
				Assertions.assertTrue(TestHelper.waitUntilValid(this.artemisReconnectOnAddressChange::isReconnectPending, isPending -> !isPending,
						TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
				if (isReconnectScheduled) {
					Assertions.assertTrue(TestHelper.waitUntilValid(this::getLiveArtemisConnections,
							liveConnections -> liveConnections.stream().noneMatch(connectionsBeforeReverts::contains), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
				}
				this.connectionFactory.clear();
			} catch (final Exception exception) {
			}
		}
		finally {
			// Restores the reconnection a test may have swapped.
			this.propertiesService.setArtemisReconnectOnAddressChange(this.artemisReconnectOnAddressChange);
		}
		// The next test, in this class or in one that shares the context, finds every listener consuming.
		Assertions.assertTrue(TestHelper.waitUntilValid(this::areJmsListenersRegistered, isRegistered -> isRegistered, TestHelper.VERY_LONG_WAIT,
				TestHelper.VERY_SHORT_WAIT));
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
		// The live connections stay: this test checks the new ones, and moving the listeners to an
		// address that does not answer would leave them reconnecting there (the teardown restores it).
		this.propertiesService.setArtemisReconnectOnAddressChange(new ArtemisReconnectOnAddressChange(false, Duration.ofSeconds(1)));

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
	 * Gets the live connections of the pooled factory's Artemis factory.
	 *
	 * @return The live connections.
	 */
	private Set<RemotingConnection> getLiveArtemisConnections() {
		final ServerLocatorImpl serverLocator = (ServerLocatorImpl) ((ActiveMQConnectionFactory) this.connectionFactory.getConnectionFactory())
				.getServerLocator();
		final Set<RemotingConnection> liveConnections = new HashSet<>();
		final Set<ClientSessionFactoryInternal> sessionFactories = serverLocator.getFactories();
		synchronized (sessionFactories) {
			for (final ClientSessionFactoryInternal sessionFactory : sessionFactories) {
				if (sessionFactory.numConnections() > 0) {
					liveConnections.add(sessionFactory.getConnection());
				}
			}
		}
		return liveConnections;
	}

	/**
	 * Sends a broker host through the endpoint, as the update scripts send it,
	 * and waits until every connection that was live is replaced, with no
	 * clear() of the pool, and a send goes through.
	 *
	 * @param  host      Broker host.
	 * @throws Exception If the test fails.
	 */
	private void changeArtemisHostAndAwaitMove(
			final String host) throws Exception {
		final Set<RemotingConnection> connectionsBefore = this.getLiveArtemisConnections();
		Assertions.assertFalse(connectionsBefore.isEmpty());
		this.restTemplate.put(
				"http://localhost:" + this.port
						+ "/properties/string/pooledJmsConnectionFactory/connectionFactory.serverLocator.initialConnectors.0.params.host?fieldAccess=true",
				host, Void.class);
		Assertions.assertTrue(TestHelper.waitUntilValid(this::getLiveArtemisConnections,
				liveConnections -> liveConnections.stream().noneMatch(connectionsBefore::contains), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			try {
				this.jmsTemplate.convertAndSend("propertiesAddressChangeQueue", "after the move to " + host);
				return Boolean.TRUE;
			}
			catch (final Exception exception) {
				return Boolean.FALSE;
			}
		}, isSent -> isSent, TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT));
		// The listeners, whose connections moved too, consume again.
		Assertions.assertTrue(TestHelper.waitUntilValid(this::areJmsListenersRegistered, isRegistered -> isRegistered, TestHelper.VERY_LONG_WAIT,
				TestHelper.VERY_SHORT_WAIT));
	}

	/**
	 * A new broker host sent through the endpoint, as the update scripts send
	 * it, moves the live connections of the pooled factory to it without
	 * clearing the pool: each is replaced by a new one, and sends go on.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A broker host changed through the endpoint moves the live connections, without clearing the pool")
	public void testArtemisAddressChangeMovesLiveConnections() throws Exception {
		this.jmsTemplate.convertAndSend("propertiesAddressChangeQueue", "before");
		// The same broker under another host name: the address changed.
		this.changeArtemisHostAndAwaitMove("127.0.0.1");
		// Back to the first host, so no move is left for the next test.
		this.changeArtemisHostAndAwaitMove("localhost");
		Assertions.assertFalse(this.artemisReconnectOnAddressChange.isReconnectPending());
	}

}
