package org.coldis.library.test.service.health;

import java.time.Duration;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.service.health.HealthCheckValue;
import org.coldis.library.service.health.LivenessHealthCheckServiceComponent;
import org.coldis.library.service.health.RepositoryHealthCheckServiceComponent;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Liveness health check component test (no Spring context).
 */
public class LivenessHealthCheckServiceComponentTest {

	/**
	 * Repository failure limit used by the tests.
	 */
	private static final Duration LIMIT = Duration.ofMinutes(15);

	/**
	 * Repository failure recency used by the tests.
	 */
	private static final Duration RECENCY = Duration.ofMinutes(2);

	/**
	 * Margin around the limits (the test clock keeps running).
	 */
	private static final Duration MARGIN = Duration.ofSeconds(5);

	/**
	 * Repository read that fails or succeeds on demand.
	 */
	private static class SwitchableRepositoryHealthCheck extends RepositoryHealthCheckServiceComponent {

		/**
		 * Whether the read fails.
		 */
		private Boolean failing = false;

		/**
		 * Sets whether the read fails.
		 *
		 * @param failing Whether the read fails.
		 */
		public void setFailing(
				final Boolean failing) {
			this.failing = failing;
		}

		/**
		 * @see RepositoryHealthCheckServiceComponent#touch()
		 */
		@Override
		public HealthCheckValue touch() throws BusinessException {
			if (this.failing) {
				throw new IllegalStateException("Repository unavailable.");
			}
			return new HealthCheckValue();
		}

	}

	/**
	 * Restores the clock.
	 */
	@AfterEach
	public void restoreClock() {
		TestHelper.cleanClock();
	}

	/**
	 * Creates the component.
	 *
	 * @param  repositoryFailureLimit  Repository failure limit.
	 * @param  applicationAvailability Application availability (or null).
	 * @param  repository              Repository read (or null).
	 * @return                         The component.
	 */
	private LivenessHealthCheckServiceComponent createComponent(
			final Duration repositoryFailureLimit,
			final ApplicationAvailabilityBean applicationAvailability,
			final RepositoryHealthCheckServiceComponent repository) {
		final LivenessHealthCheckServiceComponent component = new LivenessHealthCheckServiceComponent();
		ReflectionTestUtils.setField(component, "repositoryFailureLimit", repositoryFailureLimit);
		ReflectionTestUtils.setField(component, "repositoryFailureRecency", LivenessHealthCheckServiceComponentTest.RECENCY);
		ReflectionTestUtils.setField(component, "applicationAvailability", applicationAvailability);
		ReflectionTestUtils.setField(component, "repositoryHealthCheckService", repository);
		return component;
	}

	/**
	 * Creates an availability, in the given liveness state when there is one.
	 *
	 * @param  livenessState Liveness state (or null for none published yet).
	 * @return               The availability.
	 */
	private ApplicationAvailabilityBean createAvailability(
			final LivenessState livenessState) {
		final ApplicationAvailabilityBean applicationAvailability = new ApplicationAvailabilityBean();
		if (livenessState != null) {
			applicationAvailability.onApplicationEvent(new AvailabilityChangeEvent<>(this, livenessState));
		}
		return applicationAvailability;
	}

	/**
	 * Tests that the component follows Spring's liveness state, and is alive before Spring publishes
	 * it.
	 */
	@Test
	@DisplayName("Live while Spring's liveness state is correct, not yet published or not tracked; not live while broken")
	public void testLivenessFollowsState() {
		Assertions.assertTrue(this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT, this.createAvailability(LivenessState.CORRECT), null).isLive());
		Assertions.assertFalse(this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT, this.createAvailability(LivenessState.BROKEN), null).isLive());
		Assertions.assertTrue(this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT, this.createAvailability(null), null).isLive(),
				"startup: no liveness state published yet");
		Assertions.assertTrue(this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT, null, null).isLive());
	}

	/**
	 * Tests that a repository failing continuously (recent failures) fails liveness only past the
	 * limit, and that a success resets the count.
	 */
	@Test
	@DisplayName("Repository failing continuously for longer than the limit fails liveness; a success resets it")
	public void testRepositoryFailureLimit() {
		final LivenessHealthCheckServiceComponent component = this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT,
				this.createAvailability(LivenessState.CORRECT), null);
		component.recordRepositoryFailure();
		// Keeps failing every minute (as a polled routing check would), just short of the limit.
		for (int minute = 1; minute < 15; minute++) {
			TestHelper.moveClockBy(Duration.ofMinutes(1));
			component.recordRepositoryFailure();
		}
		TestHelper.moveClockBy(Duration.ofMinutes(1).minus(LivenessHealthCheckServiceComponentTest.MARGIN));
		Assertions.assertTrue(component.isLive(), "just short of the limit: still alive");
		TestHelper.moveClockBy(LivenessHealthCheckServiceComponentTest.MARGIN.multipliedBy(2));
		Assertions.assertFalse(component.isLive(), "past the limit, last failure recent: not alive");
		// A success makes it alive again, and the next failure starts a new count.
		component.recordRepositorySuccess();
		Assertions.assertTrue(component.isLive());
		component.recordRepositoryFailure();
		TestHelper.moveClockBy(Duration.ofMinutes(1));
		component.recordRepositoryFailure();
		Assertions.assertTrue(component.isLive(), "new count started after the success");
	}

	/**
	 * Tests that a single old failure, with no later evidence, never fails liveness.
	 */
	@Test
	@DisplayName("A failure not followed by recent ones does not count as continuous")
	public void testStaleRepositoryFailureIgnored() {
		final LivenessHealthCheckServiceComponent component = this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT,
				this.createAvailability(LivenessState.CORRECT), null);
		component.recordRepositoryFailure();
		TestHelper.moveClockBy(LivenessHealthCheckServiceComponentTest.LIMIT.plus(LivenessHealthCheckServiceComponentTest.MARGIN));
		Assertions.assertTrue(component.isLive(), "one failure, then no reads: no evidence of a continuous failure");
		// A new failure after the gap starts a new count: still alive.
		component.recordRepositoryFailure();
		Assertions.assertTrue(component.isLive(), "failure after a gap: new count, not the old one");
		// That new count fails liveness only once it is itself past the limit, with recent failures.
		for (int minute = 1; minute <= 15; minute++) {
			TestHelper.moveClockBy(Duration.ofMinutes(1));
			component.recordRepositoryFailure();
		}
		TestHelper.moveClockBy(LivenessHealthCheckServiceComponentTest.MARGIN);
		Assertions.assertFalse(component.isLive(), "new count past the limit");
	}

	/**
	 * Tests that a zero limit turns the repository off for liveness.
	 */
	@Test
	@DisplayName("Zero limit: the repository never fails liveness")
	public void testRepositoryFailureLimitDisabled() {
		final LivenessHealthCheckServiceComponent component = this.createComponent(Duration.ZERO, this.createAvailability(LivenessState.CORRECT), null);
		component.recordRepositoryFailure();
		TestHelper.moveClockBy(Duration.ofDays(1));
		component.recordRepositoryFailure();
		Assertions.assertTrue(component.isLive());
	}

	/**
	 * Tests that reading the repository records its outcome: a failure is recorded and re-thrown, a
	 * success resets the count.
	 *
	 * @throws Throwable If the test fails.
	 */
	@Test
	@DisplayName("Reading the repository records failures (re-thrown) and successes")
	public void testTouchRepositoryRecordsOutcome() throws Throwable {
		final SwitchableRepositoryHealthCheck repository = new SwitchableRepositoryHealthCheck();
		final LivenessHealthCheckServiceComponent component = this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT,
				this.createAvailability(LivenessState.CORRECT), repository);
		repository.setFailing(true);
		// The read fails every minute (as a polled routing check would) for longer than the limit.
		Assertions.assertThrows(IllegalStateException.class, () -> component.touchRepository());
		for (int minute = 1; minute <= 16; minute++) {
			TestHelper.moveClockBy(Duration.ofMinutes(1));
			Assertions.assertThrows(IllegalStateException.class, () -> component.touchRepository());
		}
		Assertions.assertFalse(component.isLive(), "failures recorded by the read");
		repository.setFailing(false);
		Assertions.assertEquals(HealthCheckValue.VALUE, component.touchRepository().getValue());
		Assertions.assertTrue(component.isLive(), "success recorded by the read");
	}

	/**
	 * Tests that without a repository the read answers the default value.
	 *
	 * @throws Throwable If the test fails.
	 */
	@Test
	@DisplayName("No repository: the read answers the default value")
	public void testTouchRepositoryWithoutRepository() throws Throwable {
		final LivenessHealthCheckServiceComponent component = this.createComponent(LivenessHealthCheckServiceComponentTest.LIMIT, null, null);
		Assertions.assertEquals(HealthCheckValue.VALUE, component.touchRepository().getValue());
		Assertions.assertTrue(component.isLive());
	}

}
