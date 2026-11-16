package org.coldis.library.test.service.health;

import java.time.Duration;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.health.LivenessHealthCheckServiceComponent;
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
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
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

	/**
	 * Tests that the liveness check does not follow readiness: an instance that refuses traffic
	 * (starting or stopping) is still alive and must not be restarted.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Liveness check passes while the application refuses traffic")
	public void testLivenessIgnoresReadiness() throws Exception {
		try {
			AvailabilityChangeEvent.publish(this.applicationContext, ReadinessState.REFUSING_TRAFFIC);
			Assertions.assertEquals(1, this.checkEntityServiceClient.checkLiveness().getValue().intValue());
		}
		finally {
			AvailabilityChangeEvent.publish(this.applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
		}
	}

	/**
	 * Tests that the liveness check answers 503 once the application is marked broken, and passes
	 * again once it is correct.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Liveness check answers 503 while the application is broken and recovers once it is correct")
	public void testLivenessFollowsLivenessState() throws Exception {
		try {
			AvailabilityChangeEvent.publish(this.applicationContext, LivenessState.BROKEN);
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> this.checkEntityServiceClient.checkLiveness());
			Assertions.assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getStatusCode());
		}
		finally {
			AvailabilityChangeEvent.publish(this.applicationContext, LivenessState.CORRECT);
		}
		Assertions.assertEquals(1, this.checkEntityServiceClient.checkLiveness().getValue().intValue());
	}

	/**
	 * Tests that the routing check reports its repository read to the liveness check: with a
	 * repository failing for longer than the limit, liveness answers 503 until a routing check
	 * reads the (real) repository successfully.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A successful routing check clears a repository failure past the limit")
	public void testRoutingCheckRecordsRepositoryOutcome() throws Exception {
		final LivenessHealthCheckServiceComponent liveness = this.applicationContext.getBean(LivenessHealthCheckServiceComponent.class);
		try {
			// The repository has been failing for longer than the limit (15 minutes by default), the last failure just now.
			ReflectionTestUtils.setField(liveness, "repositoryFailingSince", DateTimeHelper.getCurrentLocalDateTime().minus(Duration.ofMinutes(16)));
			ReflectionTestUtils.setField(liveness, "repositoryLastFailure", DateTimeHelper.getCurrentLocalDateTime());
			final IntegrationException exception = Assertions.assertThrows(IntegrationException.class,
					() -> this.checkEntityServiceClient.checkLiveness());
			Assertions.assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getStatusCode());
			// The routing check reads the repository successfully and records it.
			Assertions.assertEquals(1, this.checkEntityServiceClient.check().getValue().intValue());
			Assertions.assertEquals(1, this.checkEntityServiceClient.checkLiveness().getValue().intValue());
		}
		finally {
			liveness.recordRepositorySuccess();
		}
	}

}
