package org.coldis.library.service.jms;

import java.util.Map;

import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Applies the JMS thread settings that must be known before any bean is
 * created. Pins the CPU count of {@link JmsCpuLimits} from
 * {@value JmsCpuLimits#CPUS_PROPERTY}, so every reader sees it whatever the
 * bean creation order. Resolves the Artemis client's Netty remoting threads
 * ({@value #REMOTING_THREADS_PROPERTY}, used by
 * {@code spring.artemis.broker-url-query-default} as {@code remotingThreads})
 * when the application does not set it, first match:
 * {@value #REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY} times the CPUs
 * ({@link JmsCpuLimits}); the legacy {@value #LEGACY_REMOTING_THREADS_PROPERTY}
 * (kept working); {@value #DEFAULT_REMOTING_THREADS_CPU_MULTIPLIER} times the
 * CPUs. The value is added as the lowest-precedence property source, so an
 * application's own value always wins.
 */
public class JmsThreadsEnvironmentPostProcessor implements EnvironmentPostProcessor {

	/**
	 * Remoting threads property.
	 */
	public static final String REMOTING_THREADS_PROPERTY = "spring.artemis.remoting-threads";

	/**
	 * Remoting threads per CPU property.
	 */
	public static final String REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY = "spring.artemis.remoting-threads-cpu-multiplier";

	/**
	 * Legacy absolute value (an environment variable set by some images).
	 */
	public static final String LEGACY_REMOTING_THREADS_PROPERTY = "AVAILABLE_CPUS_30X";

	/**
	 * Default remoting threads per CPU (unchanged from the legacy default).
	 */
	public static final double DEFAULT_REMOTING_THREADS_CPU_MULTIPLIER = 30D;

	/**
	 * Property source name.
	 */
	public static final String PROPERTY_SOURCE_NAME = "org.coldis.library.service.jms.remoting-threads";

	/**
	 * Resolves the remoting threads.
	 *
	 * @param  environment Environment.
	 * @return             The remoting threads.
	 */
	public static int resolveRemotingThreads(
			final ConfigurableEnvironment environment) {
		final Double cpus = environment.getProperty(JmsCpuLimits.CPUS_PROPERTY, Double.class);
		final double actualCpus = ((cpus == null) || (cpus <= 0) ? JmsCpuLimits.detectCpus(JmsCpuLimits.CGROUP_ROOT, () -> Runtime.getRuntime().availableProcessors())
				: cpus);
		// A multiplier that is not positive is not a valid thread count: the default applies.
		final Double configuredMultiplier = environment.getProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY,
				Double.class);
		final Double multiplier = ((configuredMultiplier == null) || (configuredMultiplier <= 0) ? null : configuredMultiplier);
		final String legacyValue = environment.getProperty(JmsThreadsEnvironmentPostProcessor.LEGACY_REMOTING_THREADS_PROPERTY);
		final Integer legacyThreads = ((multiplier == null) && NumberUtils.isDigits(legacyValue) ? Integer.valueOf(legacyValue) : null);
		final Integer remotingThreads = JmsCpuLimits.resolve(legacyThreads, multiplier,
				JmsThreadsEnvironmentPostProcessor.DEFAULT_REMOTING_THREADS_CPU_MULTIPLIER, () -> actualCpus);
		return (remotingThreads == null ? 1 : remotingThreads);
	}

	/**
	 * @see org.springframework.boot.env.EnvironmentPostProcessor#postProcessEnvironment(org.springframework.core.env.ConfigurableEnvironment,
	 *      org.springframework.boot.SpringApplication)
	 */
	@Override
	public void postProcessEnvironment(
			final ConfigurableEnvironment environment,
			final SpringApplication application) {
		JmsCpuLimits.setConfiguredCpus(environment.getProperty(JmsCpuLimits.CPUS_PROPERTY, Double.class));
		if (!environment.containsProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY)) {
			environment.getPropertySources().addLast(new MapPropertySource(JmsThreadsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME,
					Map.of(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY,
							JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(environment))));
		}
	}

}
