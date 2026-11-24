package org.coldis.library.service.cpu;

import java.util.Objects;

import org.coldis.library.thread.CpuLimits;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Pins the CPU count that CPU-based limits multiply ({@link CpuLimits}, in core)
 * from {@value #CPUS_PROPERTY}, at startup, before any bean exists, so every
 * reader sees the same count whatever the bean creation order. Runs before
 * {@code @PropertySource} files are loaded: set the property in Spring Boot
 * config data ({@code application.properties}, profiles) or an environment
 * variable. Without it, {@link CpuLimits} detects the cgroup quota or the
 * runtime processors (doc/cpu-source.md). The pin is process-wide: the first
 * valid value wins, and {@link CpuLimits#pin(double)} decides which values are
 * valid (a later different value, or one that is not positive, is ignored).
 */
public class CpuEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	/**
	 * Property that pins the CPU count.
	 */
	public static final String CPUS_PROPERTY = "org.coldis.library.service.cpus";

	/**
	 * Order: after the config data, before the other library post-processors
	 * that read the CPU count.
	 */
	public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

	/**
	 * CPU limits to pin.
	 */
	private final CpuLimits cpuLimits;

	/**
	 * Constructor (the process-wide CPU limits).
	 */
	public CpuEnvironmentPostProcessor() {
		this(CpuLimits.getDefault());
	}

	/**
	 * Constructor.
	 *
	 * @param cpuLimits CPU limits to pin.
	 */
	public CpuEnvironmentPostProcessor(final CpuLimits cpuLimits) {
		this.cpuLimits = Objects.requireNonNull(cpuLimits);
	}

	/**
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	@Override
	public int getOrder() {
		return CpuEnvironmentPostProcessor.ORDER;
	}

	/**
	 * @see org.springframework.boot.env.EnvironmentPostProcessor#postProcessEnvironment(org.springframework.core.env.ConfigurableEnvironment,
	 *      org.springframework.boot.SpringApplication)
	 */
	@Override
	public void postProcessEnvironment(
			final ConfigurableEnvironment environment,
			final SpringApplication application) {
		final Double cpus = environment.getProperty(CpuEnvironmentPostProcessor.CPUS_PROPERTY, Double.class);
		if (cpus != null) {
			this.cpuLimits.pin(cpus);
		}
	}

}
