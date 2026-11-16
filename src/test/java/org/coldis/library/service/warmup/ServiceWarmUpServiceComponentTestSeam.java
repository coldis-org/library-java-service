package org.coldis.library.service.warmup;

import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * Test-only access to {@link ServiceWarmUpServiceComponent}'s package-private warm-up run and start, so tests can run
 * warm-ups with their own limits and start them with their own threads.
 */
public final class ServiceWarmUpServiceComponentTestSeam {

	/**
	 * No instances.
	 */
	private ServiceWarmUpServiceComponentTestSeam() {
		super();
	}

	/**
	 * Runs warm-ups now, holding the health check while they run.
	 *
	 * @param  component  Warm-up service.
	 * @param  warmUps    Warm-ups.
	 * @param  properties Properties.
	 * @return            The results, in the order of the warm-ups.
	 */
	public static List<ServiceWarmUpResult> warmUp(
			final ServiceWarmUpServiceComponent component,
			final List<? extends ServiceWarmUp<?>> warmUps,
			final ServiceWarmUpProperties properties) {
		return component.warmUp(warmUps, properties);
	}

	/**
	 * Starts the warm-up beans as the application's ready event does, with a thread factory of the test's.
	 *
	 * @param component     Warm-up service.
	 * @param threadFactory Factory of the thread that runs the warm-up beans.
	 */
	public static void startStartupWarmUps(
			final ServiceWarmUpServiceComponent component,
			final ThreadFactory threadFactory) {
		component.startStartupWarmUps(threadFactory);
	}

	/**
	 * Copies warm-up properties, so a test can change its copy.
	 *
	 * @param  properties Properties.
	 * @return            The copy.
	 */
	public static ServiceWarmUpProperties copy(
			final ServiceWarmUpProperties properties) {
		final ServiceWarmUpProperties copy = new ServiceWarmUpProperties();
		copy.setEnabled(properties.getEnabled());
		copy.setReadOnlyTransaction(properties.getReadOnlyTransaction());
		copy.setTimeLimit(properties.getTimeLimit());
		copy.setMinimumCalls(properties.getMinimumCalls());
		copy.setParallelism(properties.getParallelism());
		copy.setIdleCompilationMillisPerSecond(properties.getIdleCompilationMillisPerSecond());
		copy.setIdleCompilationPeriod(properties.getIdleCompilationPeriod());
		copy.setMaximumConsecutiveErrors(properties.getMaximumConsecutiveErrors());
		return copy;
	}

}
