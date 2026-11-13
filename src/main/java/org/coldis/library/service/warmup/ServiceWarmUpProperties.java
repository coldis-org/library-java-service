package org.coldis.library.service.warmup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Service warm-up properties. The defaults are in {@code service.properties}.
 */
@Component
@ConfigurationProperties(prefix = "org.coldis.library.service.warm-up")
public class ServiceWarmUpProperties {

	/**
	 * Property that turns the service warm-up on. Off by default: until a service sets it to true, the library registers
	 * none of the warm-up beans, filters or guards.
	 */
	public static final String ENABLED_PROPERTY = "org.coldis.library.service.warm-up.enabled";

	/**
	 * Whether the service warm-up is on.
	 */
	private Boolean enabled;

	/**
	 * Longest time the warm-ups run before the health check opens.
	 */
	private Duration timeLimit;

	/**
	 * Calls every running warm-up makes before an idle JIT can stop them.
	 */
	private Integer minimumCalls;

	/**
	 * Threads that make the calls. Zero or less means one per available processor.
	 */
	private Integer parallelism;

	/**
	 * JIT compilation time per second, in milliseconds, under which the JIT counts as idle.
	 */
	private Long idleCompilationMillisPerSecond;

	/**
	 * How long the JIT must stay idle to stop the warm-ups.
	 */
	private Duration idleCompilationPeriod;

	/**
	 * Consecutive failed calls that stop a warm-up.
	 */
	private Integer maximumConsecutiveErrors;

	/**
	 * Constructor.
	 */
	public ServiceWarmUpProperties() {
		super();
	}

	/**
	 * Copy constructor.
	 *
	 * @param properties Properties to copy.
	 */
	public ServiceWarmUpProperties(final ServiceWarmUpProperties properties) {
		super();
		this.enabled = properties.getEnabled();
		this.timeLimit = properties.getTimeLimit();
		this.minimumCalls = properties.getMinimumCalls();
		this.parallelism = properties.getParallelism();
		this.idleCompilationMillisPerSecond = properties.getIdleCompilationMillisPerSecond();
		this.idleCompilationPeriod = properties.getIdleCompilationPeriod();
		this.maximumConsecutiveErrors = properties.getMaximumConsecutiveErrors();
	}

	/**
	 * Gets whether the service warm-up is on.
	 *
	 * @return Whether the service warm-up is on.
	 */
	public Boolean getEnabled() {
		return this.enabled;
	}

	/**
	 * Sets whether the service warm-up is on.
	 *
	 * @param enabled Whether the service warm-up is on.
	 */
	public void setEnabled(
			final Boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Gets the time limit.
	 *
	 * @return The time limit.
	 */
	public Duration getTimeLimit() {
		return this.timeLimit;
	}

	/**
	 * Sets the time limit.
	 *
	 * @param timeLimit The time limit.
	 */
	public void setTimeLimit(
			final Duration timeLimit) {
		this.timeLimit = timeLimit;
	}

	/**
	 * Gets the minimum calls.
	 *
	 * @return The minimum calls.
	 */
	public Integer getMinimumCalls() {
		return this.minimumCalls;
	}

	/**
	 * Sets the minimum calls.
	 *
	 * @param minimumCalls The minimum calls.
	 */
	public void setMinimumCalls(
			final Integer minimumCalls) {
		this.minimumCalls = minimumCalls;
	}

	/**
	 * Gets the parallelism.
	 *
	 * @return The parallelism.
	 */
	public Integer getParallelism() {
		return this.parallelism;
	}

	/**
	 * Sets the parallelism.
	 *
	 * @param parallelism The parallelism.
	 */
	public void setParallelism(
			final Integer parallelism) {
		this.parallelism = parallelism;
	}

	/**
	 * Gets the idle compilation milliseconds per second.
	 *
	 * @return The idle compilation milliseconds per second.
	 */
	public Long getIdleCompilationMillisPerSecond() {
		return this.idleCompilationMillisPerSecond;
	}

	/**
	 * Sets the idle compilation milliseconds per second.
	 *
	 * @param idleCompilationMillisPerSecond The idle compilation milliseconds per second.
	 */
	public void setIdleCompilationMillisPerSecond(
			final Long idleCompilationMillisPerSecond) {
		this.idleCompilationMillisPerSecond = idleCompilationMillisPerSecond;
	}

	/**
	 * Gets the idle compilation period.
	 *
	 * @return The idle compilation period.
	 */
	public Duration getIdleCompilationPeriod() {
		return this.idleCompilationPeriod;
	}

	/**
	 * Sets the idle compilation period.
	 *
	 * @param idleCompilationPeriod The idle compilation period.
	 */
	public void setIdleCompilationPeriod(
			final Duration idleCompilationPeriod) {
		this.idleCompilationPeriod = idleCompilationPeriod;
	}

	/**
	 * Gets the maximum consecutive errors.
	 *
	 * @return The maximum consecutive errors.
	 */
	public Integer getMaximumConsecutiveErrors() {
		return this.maximumConsecutiveErrors;
	}

	/**
	 * Sets the maximum consecutive errors.
	 *
	 * @param maximumConsecutiveErrors The maximum consecutive errors.
	 */
	public void setMaximumConsecutiveErrors(
			final Integer maximumConsecutiveErrors) {
		this.maximumConsecutiveErrors = maximumConsecutiveErrors;
	}

}
