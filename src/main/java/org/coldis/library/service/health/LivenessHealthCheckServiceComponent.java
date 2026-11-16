package org.coldis.library.service.health;

import java.time.Duration;
import java.time.LocalDateTime;

import org.coldis.library.helper.DateTimeHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.stereotype.Service;

/**
 * Liveness health check: whether the application is alive and should keep running, as opposed to
 * whether it accepts traffic (readiness). A slow or unreachable dependency does not make the
 * application dead: restarting it would not fix the dependency, and every instance would restart at
 * once. The only dependency this check looks at is the repository, and only as a last resort: it
 * fails when the repository touch of the routing check has been failing continuously for longer than
 * the configured limit, for an instance stuck with connections that never recover.
 */
@Service
@ConditionalOnProperty(
		name = "org.coldis.configuration.health-check-enabled",
		matchIfMissing = true
)
public class LivenessHealthCheckServiceComponent {

	/**
	 * Application availability (readiness and liveness, kept by Spring Boot).
	 */
	@Autowired(required = false)
	private ApplicationAvailability applicationAvailability;

	/**
	 * How long the repository touch may fail continuously before the application is taken as not
	 * alive. Zero (or negative) turns the repository off for liveness.
	 */
	@Value("${org.coldis.configuration.health-check-liveness-repository-failure-limit:15m}")
	private Duration repositoryFailureLimit;

	/**
	 * When the repository touch started failing (null while it succeeds).
	 */
	private volatile LocalDateTime repositoryFailingSince;

	/**
	 * Records a successful repository touch.
	 */
	public void recordRepositorySuccess() {
		this.repositoryFailingSince = null;
	}

	/**
	 * Records a failed repository touch: the first failure in a row starts the count.
	 */
	public void recordRepositoryFailure() {
		if (this.repositoryFailingSince == null) {
			this.repositoryFailingSince = DateTimeHelper.getCurrentLocalDateTime();
		}
	}

	/**
	 * Whether the repository has been failing continuously for longer than the limit.
	 *
	 * @return If the repository failure limit is exceeded.
	 */
	private Boolean isRepositoryFailureLimitExceeded() {
		Boolean exceeded = false;
		final LocalDateTime failingSince = this.repositoryFailingSince;
		final Boolean limitEnabled = (this.repositoryFailureLimit != null) && !this.repositoryFailureLimit.isNegative()
				&& !this.repositoryFailureLimit.isZero();
		if (limitEnabled && (failingSince != null)) {
			final Duration failingFor = Duration.between(failingSince, DateTimeHelper.getCurrentLocalDateTime());
			exceeded = failingFor.compareTo(this.repositoryFailureLimit) > 0;
		}
		return exceeded;
	}

	/**
	 * Whether the application is alive: Spring's liveness state is correct (or not tracked), and the
	 * repository has not been failing for longer than the limit.
	 *
	 * @return If the application is alive.
	 */
	public Boolean isLive() {
		Boolean live = true;
		if ((this.applicationAvailability != null) && !LivenessState.CORRECT.equals(this.applicationAvailability.getLivenessState())) {
			live = false;
		}
		else if (this.isRepositoryFailureLimitExceeded()) {
			live = false;
		}
		return live;
	}

}
