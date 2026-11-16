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
 * fails when the repository read of the routing check has been failing continuously for longer than
 * the configured limit, for an instance stuck with connections that never recover.
 *
 * The repository evidence comes from the routing check (/health-check), so it only exists while that
 * check is polled: a failure not followed by another one within the configured recency does not count
 * as a continuous failure.
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
	 * Repository health check service.
	 */
	@Autowired(required = false)
	private RepositoryHealthCheckServiceComponent repositoryHealthCheckService;

	/**
	 * How long the repository read may fail continuously before the application is taken as not
	 * alive. Zero (or negative) turns the repository off for liveness.
	 */
	@Value("${org.coldis.configuration.health-check-liveness-repository-failure-limit:15m}")
	private Duration repositoryFailureLimit;

	/**
	 * How recent the last failed repository read must be for the failure to count as continuing.
	 */
	@Value("${org.coldis.configuration.health-check-liveness-repository-failure-recency:2m}")
	private Duration repositoryFailureRecency;

	/**
	 * When the repository read started failing (null while it succeeds).
	 */
	private LocalDateTime repositoryFailingSince;

	/**
	 * When the repository read last failed (null while it succeeds).
	 */
	private LocalDateTime repositoryLastFailure;

	/**
	 * Records a successful repository read.
	 */
	public synchronized void recordRepositorySuccess() {
		this.repositoryFailingSince = null;
		this.repositoryLastFailure = null;
	}

	/**
	 * Records a failed repository read: the first failure in a row starts the count, and so does a
	 * failure after a gap longer than the recency (the failures before the gap were not continuous).
	 */
	public synchronized void recordRepositoryFailure() {
		final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
		final Boolean previousFailureRecent = (this.repositoryLastFailure != null) && ((this.repositoryFailureRecency == null)
				|| (Duration.between(this.repositoryLastFailure, now).compareTo(this.repositoryFailureRecency) <= 0));
		if ((this.repositoryFailingSince == null) || !previousFailureRecent) {
			this.repositoryFailingSince = now;
		}
		this.repositoryLastFailure = now;
	}

	/**
	 * Reads the repository (when there is one) and records the outcome for the liveness check. A
	 * failure is recorded and re-thrown. The outcome is recorded outside the read's transaction, so a
	 * failure on commit counts too.
	 *
	 * @return           The health check value.
	 * @throws Throwable If the repository cannot be read.
	 */
	public HealthCheckValue touchRepository() throws Throwable {
		HealthCheckValue checkValue = new HealthCheckValue();
		if (this.repositoryHealthCheckService != null) {
			try {
				checkValue = this.repositoryHealthCheckService.touch();
				this.recordRepositorySuccess();
			}
			catch (final Throwable throwable) {
				this.recordRepositoryFailure();
				throw throwable;
			}
		}
		return checkValue;
	}

	/**
	 * Whether the repository has been failing continuously for longer than the limit: the first
	 * failure in a row is older than the limit, and the last one is recent.
	 *
	 * @return If the repository failure limit is exceeded.
	 */
	private synchronized Boolean isRepositoryFailureLimitExceeded() {
		Boolean exceeded = false;
		final Boolean limitEnabled = (this.repositoryFailureLimit != null) && !this.repositoryFailureLimit.isNegative()
				&& !this.repositoryFailureLimit.isZero();
		if (limitEnabled && (this.repositoryFailingSince != null) && (this.repositoryLastFailure != null)) {
			final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
			final Boolean failingLongerThanLimit = Duration.between(this.repositoryFailingSince, now).compareTo(this.repositoryFailureLimit) > 0;
			final Boolean lastFailureRecent = (this.repositoryFailureRecency == null)
					|| (Duration.between(this.repositoryLastFailure, now).compareTo(this.repositoryFailureRecency) <= 0);
			exceeded = failingLongerThanLimit && lastFailureRecent;
		}
		return exceeded;
	}

	/**
	 * Whether the application is alive: Spring's liveness state is correct (taken as correct before
	 * Spring first publishes it, and when it is not tracked), and the repository has not been failing
	 * continuously for longer than the limit.
	 *
	 * @return If the application is alive.
	 */
	public Boolean isLive() {
		Boolean live = true;
		if ((this.applicationAvailability != null)
				&& !LivenessState.CORRECT.equals(this.applicationAvailability.getState(LivenessState.class, LivenessState.CORRECT))) {
			live = false;
		}
		else if (this.isRepositoryFailureLimitExceeded()) {
			live = false;
		}
		return live;
	}

}
