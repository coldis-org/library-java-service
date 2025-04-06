package org.coldis.library.test.service.properties;

import java.sql.Connection;

import javax.sql.DataSource;

import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Properties service test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
public class PropertiesServiceTest {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesServiceTest.class);

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

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
		this.restTemplate.put("http://localhost:" + this.port + "/properties/string/dataSource/pool.dataSource.jdbcUrl?fieldAccess=true", "jdbc:postgresql://localhost:1234/test",
				Void.class);

		// Makes sure the connection is not valid.
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> {
			try {
				LOGGER.info("Testing database connection...");
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

	}

}
