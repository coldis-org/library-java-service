package org.coldis.library.service.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Service;

/**
 * Readiness health check: whether the application accepts traffic.
 */
@Service
@ConditionalOnProperty(
		name = "org.coldis.configuration.health-check-enabled",
		matchIfMissing = true
)
public class ReadinessHealthCheckServiceComponent {

	/**
	 * Application availability (readiness and liveness, kept by Spring Boot).
	 */
	@Autowired(required = false)
	private ApplicationAvailability applicationAvailability;

	/**
	 * Whether the readiness is part of the health check.
	 */
	@Value("${org.coldis.configuration.health-check-readiness-enabled:true}")
	private Boolean readinessEnabled;

	/**
	 * Whether the application accepts traffic. Without availability tracking, or
	 * with the check disabled, the application is taken as ready.
	 *
	 * @return If the application accepts traffic.
	 */
	public Boolean isReady() {
		Boolean ready = true;
		if (Boolean.TRUE.equals(this.readinessEnabled) && (this.applicationAvailability != null)) {
			ready = ReadinessState.ACCEPTING_TRAFFIC.equals(this.applicationAvailability.getReadinessState());
		}
		return ready;
	}

}
