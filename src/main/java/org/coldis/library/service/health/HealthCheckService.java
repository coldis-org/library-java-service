package org.coldis.library.service.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
	 * Repository health check service.
	 */
	@Autowired(required = false)
	private RepositoryHealthCheckService repositoryHealthCheckService;

	/**
	 * Health check service.
	 *
	 * @return           The health check value.
	 * @throws Throwable If the health cannot be checked.
	 */
	@RequestMapping(method = RequestMethod.GET)
	public HealthCheckValue check() throws Throwable {
		HealthCheckService.LOGGER.debug("Health check starting.");
		final long initMillis = System.currentTimeMillis();
		try {
			// The health check default value.
			HealthCheckValue checkValue = new HealthCheckValue();
			// If the repository is available.
			if (this.repositoryHealthCheckService != null) {
				// Touches the repository.
				checkValue = this.repositoryHealthCheckService.touch();
			}
			// Returns the entity id.
			return checkValue;
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
			// Logs the successful health check.
			final long executionTime = System.currentTimeMillis() - initMillis;
			HealthCheckService.LOGGER.debug("Health check successfully finished within '" + executionTime + "' milliseconds.");
		}
	}

}
