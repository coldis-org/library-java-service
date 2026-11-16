package org.coldis.library.service.health;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Readiness health check: whether the application accepts traffic.
 */
@Service
public class ReadinessHealthCheckServiceComponent {

	/**
	 * Message code for an application that does not accept traffic.
	 */
	public static final String NOT_READY_CODE = "health.check.not.ready";

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

	/**
	 * Checks that the application accepts traffic.
	 *
	 * @throws IntegrationException With status 503 if the application does not
	 *                                  accept traffic.
	 */
	public void checkReady() throws IntegrationException {
		if (!this.isReady()) {
			throw new IntegrationException(
					new SimpleMessage(ReadinessHealthCheckServiceComponent.NOT_READY_CODE, "The application does not accept traffic yet or anymore."),
					HttpStatus.SERVICE_UNAVAILABLE.value());
		}
	}

}
