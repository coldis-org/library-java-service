package org.coldis.library.test.service.health;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
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
	 * Application context (to publish readiness changes).
	 */
	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * Tests the check service.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testCheck() throws Exception {
		Assertions.assertEquals(1, this.checkEntityServiceClient.check().getValue().intValue());
	}

	/**
	 * Tests that the check answers 503 while the application refuses traffic, and
	 * passes again once it accepts traffic.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Health check answers 503 while the application refuses traffic and recovers once it accepts traffic again")
	public void testCheckFollowsReadiness() throws Exception {
		try {
			AvailabilityChangeEvent.publish(this.applicationContext, ReadinessState.REFUSING_TRAFFIC);
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class, () -> this.checkEntityServiceClient.check());
			Assertions.assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getStatusCode());
		}
		finally {
			AvailabilityChangeEvent.publish(this.applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
		}
		Assertions.assertEquals(1, this.checkEntityServiceClient.check().getValue().intValue());
	}
}
