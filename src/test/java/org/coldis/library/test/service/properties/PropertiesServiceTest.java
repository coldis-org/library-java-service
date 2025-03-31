package org.coldis.library.test.service.properties;

import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Properties service test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
public class PropertiesServiceTest {

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
	}

}
