package org.coldis.library.service.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health check service.
 */
@RestController
@RequestMapping(path = "${org.coldis.configuration.health-check:/health-check}")
@ConditionalOnProperty(
		name = "org.coldis.configuration.health-check-enabled",
		matchIfMissing = true
)
public class HealthCheckService {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(HealthCheckService.class);

	/**
	 * Health check key.
	 */
	public static final String HEALTH_CHECK_KEY = "health-check";

	/**
	 * Readiness health check service.
	 */
	@Autowired
	private ReadinessHealthCheckServiceComponent readinessHealthCheckService;

	/**
	 * Liveness health check service.
	 */
	@Autowired
	private LivenessHealthCheckServiceComponent livenessHealthCheckService;

	/**
	 * Liveness scope of the health check.
	 */
	public static final String LIVENESS_SCOPE = "liveness";

	/**
	 * Health check service.
	 *
	 * @return           The health check value, or 503 (no body) while the
	 *                   application does not accept traffic.
	 * @throws Throwable If the health cannot be checked.
	 */
	@RequestMapping(method = RequestMethod.GET)
	public ResponseEntity<HealthCheckValue> check() throws Throwable {
		HealthCheckService.LOGGER.debug("Health check starting.");
		final long initMillis = System.currentTimeMillis();
		ResponseEntity<HealthCheckValue> response = null;
		try {
			// While the application does not accept traffic (starting or stopping),
			// answers 503 without an error: it is an expected state, not a failure.
			if (!this.readinessHealthCheckService.isReady()) {
				HealthCheckService.LOGGER.debug("Health check refused: the application does not accept traffic.");
				response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
			}
			// If the application accepts traffic.
			else {
				// Reads the repository (when there is one), recording the outcome for the liveness check.
				response = ResponseEntity.ok(this.livenessHealthCheckService.touchRepository());
			}
		}
		// If the health check does not succeed.
		catch (final Throwable throwable) {
			// Re-throws the error.
			HealthCheckService.LOGGER.error("Health check failed: " + throwable.getMessage());
			HealthCheckService.LOGGER.debug("Health check failed.", throwable);
			throw throwable;
		}
		// At the end.
		finally {
			// Logs the health check duration (whatever the outcome).
			final long executionTime = System.currentTimeMillis() - initMillis;
			HealthCheckService.LOGGER.debug("Health check finished within '" + executionTime + "' milliseconds.");
		}
		return response;
	}

	/**
	 * Liveness health check: whether the application is alive and should keep
	 * running (for restart checks). It does not depend on readiness or on any
	 * dependency being reachable, so a slow dependency or a shutdown in progress
	 * never restarts the instance.
	 *
	 * @return The health check value, or 503 (no body) when the application is
	 *         not alive.
	 */
	@RequestMapping(
			method = RequestMethod.GET,
			params = "scope=" + HealthCheckService.LIVENESS_SCOPE
	)
	public ResponseEntity<HealthCheckValue> checkLiveness() {
		ResponseEntity<HealthCheckValue> response = null;
		if (this.livenessHealthCheckService.isLive()) {
			response = ResponseEntity.ok(new HealthCheckValue());
		}
		else {
			HealthCheckService.LOGGER.warn("Liveness health check failed: the application is not alive.");
			response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		return response;
	}

}
