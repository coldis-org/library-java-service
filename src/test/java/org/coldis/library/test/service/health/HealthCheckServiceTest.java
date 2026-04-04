package org.coldis.library.test.service.health;

import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Health check service test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class HealthCheckServiceTest extends ContainerTestHelper {

	/**
	 * Health check service client.
	 */
	@Autowired
	private HealthCheckServiceClient checkEntityServiceClient;

	/**
	 * Tests the check service.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testCheck() throws Exception {
		Assertions.assertEquals(1, this.checkEntityServiceClient.check().getValue().intValue());
	}
}
