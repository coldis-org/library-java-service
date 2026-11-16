package org.coldis.library.test.service.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.health.LivenessHealthCheckServiceComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
	 * Clock before the test.
	 */
	private Clock originalClock;

	/**
	 * Fixes the clock.
	 */
	@BeforeEach
	public void fixClock() {
		this.originalClock = DateTimeHelper.getClock();
		DateTimeHelper.setClock(Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneId.of("UTC")));
	}

	/**
	 * Restores the clock.
	 */
	@AfterEach
	public void restoreClock() {
		DateTimeHelper.setClock(this.originalClock);
	}

	/**
	 * Moves the clock forward.
	 *
	 * @param duration How much.
	 */
	private void advanceClock(
			final Duration duration) {
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), duration));
	}

	/**
	 * Creates the component with the given limit and availability.
	 *
	 * @param  repositoryFailureLimit  Repository failure limit.
	 * @param  applicationAvailability Application availability (or null).
	 * @return                         The component.
	 */
	private LivenessHealthCheckServiceComponent createComponent(
			final Duration repositoryFailureLimit,
			final ApplicationAvailabilityBean applicationAvailability) {
		final LivenessHealthCheckServiceComponent component = new LivenessHealthCheckServiceComponent();
		ReflectionTestUtils.setField(component, "repositoryFailureLimit", repositoryFailureLimit);
		ReflectionTestUtils.setField(component, "applicationAvailability", applicationAvailability);
		return component;
	}

	/**
	 * Creates an availability in the given liveness state.
	 *
	 * @param  livenessState Liveness state.
	 * @return               The availability.
	 */
	private ApplicationAvailabilityBean createAvailability(
			final LivenessState livenessState) {
		final ApplicationAvailabilityBean applicationAvailability = new ApplicationAvailabilityBean();
		applicationAvailability.onApplicationEvent(new AvailabilityChangeEvent<>(this, livenessState));
		return applicationAvailability;
	}

	/**
	 * Tests that the component follows Spring's liveness state.
	 */
	@Test
	@DisplayName("Live only while Spring's liveness state is correct (or not tracked)")
	public void testLivenessFollowsState() {
		Assertions.assertTrue(this.createComponent(Duration.ofMinutes(15), this.createAvailability(LivenessState.CORRECT)).isLive());
		Assertions.assertFalse(this.createComponent(Duration.ofMinutes(15), this.createAvailability(LivenessState.BROKEN)).isLive());
		Assertions.assertTrue(this.createComponent(Duration.ofMinutes(15), null).isLive());
	}

	/**
	 * Tests that the repository fails liveness only after failing continuously for longer than the
	 * limit, and that a success resets the count.
	 */
	@Test
	@DisplayName("Repository failing continuously for longer than the limit fails liveness; a success resets it")
	public void testRepositoryFailureLimit() {
		final LivenessHealthCheckServiceComponent component = this.createComponent(Duration.ofMinutes(15),
				this.createAvailability(LivenessState.CORRECT));
		component.recordRepositoryFailure();
		this.advanceClock(Duration.ofMinutes(10));
		// A later failure does not restart the count.
		component.recordRepositoryFailure();
		this.advanceClock(Duration.ofMinutes(5));
		Assertions.assertTrue(component.isLive(), "exactly at the limit: still alive");
		this.advanceClock(Duration.ofSeconds(1));
		Assertions.assertFalse(component.isLive(), "past the limit: not alive");
		// A success makes it alive again, and the next failure starts a new count.
		component.recordRepositorySuccess();
		Assertions.assertTrue(component.isLive());
		component.recordRepositoryFailure();
		this.advanceClock(Duration.ofMinutes(15));
		Assertions.assertTrue(component.isLive());
	}

	/**
	 * Tests that a zero limit turns the repository off for liveness.
	 */
	@Test
	@DisplayName("Zero limit: the repository never fails liveness")
	public void testRepositoryFailureLimitDisabled() {
		final LivenessHealthCheckServiceComponent component = this.createComponent(Duration.ZERO, this.createAvailability(LivenessState.CORRECT));
		component.recordRepositoryFailure();
		this.advanceClock(Duration.ofDays(1));
		Assertions.assertTrue(component.isLive());
	}

}
