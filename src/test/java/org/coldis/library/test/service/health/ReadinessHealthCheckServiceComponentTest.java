package org.coldis.library.test.service.health;

import org.coldis.library.service.health.ReadinessHealthCheckServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Readiness health check component test (no Spring context).
 */
public class ReadinessHealthCheckServiceComponentTest {

	/**
	 * Creates the component with the given switch and availability.
	 *
	 * @param  readinessEnabled        Whether the readiness is part of the
	 *                                     health check.
	 * @param  applicationAvailability Application availability (or null).
	 * @return                         The component.
	 */
	private ReadinessHealthCheckServiceComponent createComponent(
			final Boolean readinessEnabled,
			final ApplicationAvailabilityBean applicationAvailability) {
		final ReadinessHealthCheckServiceComponent component = new ReadinessHealthCheckServiceComponent();
		ReflectionTestUtils.setField(component, "readinessEnabled", readinessEnabled);
		ReflectionTestUtils.setField(component, "applicationAvailability", applicationAvailability);
		return component;
	}

	/**
	 * Creates an availability in the given readiness state.
	 *
	 * @param  readinessState Readiness state.
	 * @return                The availability.
	 */
	private ApplicationAvailabilityBean createAvailability(
			final ReadinessState readinessState) {
		final ApplicationAvailabilityBean applicationAvailability = new ApplicationAvailabilityBean();
		applicationAvailability.onApplicationEvent(new AvailabilityChangeEvent<>(this, readinessState));
		return applicationAvailability;
	}

	/**
	 * Tests that the component follows the readiness state when the check is on.
	 */
	@Test
	@DisplayName("Readiness on: ready only while the application accepts traffic")
	public void testReadinessFollowsStateWhenEnabled() {
		Assertions.assertFalse(this.createComponent(true, this.createAvailability(ReadinessState.REFUSING_TRAFFIC)).isReady());
		Assertions.assertTrue(this.createComponent(true, this.createAvailability(ReadinessState.ACCEPTING_TRAFFIC)).isReady());
	}

	/**
	 * Tests that the switch turns the readiness check off.
	 */
	@Test
	@DisplayName("Readiness off: ready even while the application refuses traffic")
	public void testReadinessIgnoredWhenDisabled() {
		Assertions.assertTrue(this.createComponent(false, this.createAvailability(ReadinessState.REFUSING_TRAFFIC)).isReady());
	}

	/**
	 * Tests that, without availability tracking, the application is taken as
	 * ready.
	 */
	@Test
	@DisplayName("No availability tracking: taken as ready")
	public void testReadyWithoutAvailability() {
		Assertions.assertTrue(this.createComponent(true, null).isReady());
	}

}
