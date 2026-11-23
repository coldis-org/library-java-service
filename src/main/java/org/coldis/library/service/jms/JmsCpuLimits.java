package org.coldis.library.service.jms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CPU source of the JMS limits introduced with the listener bulkhead (the
 * bulkhead's {@code max-in-flight-cpu-multiplier} and the Artemis remoting
 * threads), and their resolution. The deprecated legacy listener
 * {@code concurrency-cpu-multiplier}, the bounded listener pool
 * ({@code unbounded=false}) and the Artemis global executors keep their previous
 * sizing from {@link Runtime#availableProcessors()}.
 * <p>
 * CPUs, first match: the property {@value #CPUS_PROPERTY} (a project can pin
 * it; applied at startup by {@link JmsThreadsEnvironmentPostProcessor}); the cgroup v2 quota ({@code cpu.max}: quota / period); the cgroup v1
 * quota ({@code cpu.cfs_quota_us / cpu.cfs_period_us}); else
 * {@link Runtime#availableProcessors()}. A quota is capped at
 * {@code availableProcessors()}. The JVM rounds a fractional quota up (2.08
 * CPUs report as 3), so the quota itself is used.
 * <p>
 * A limit resolves, first match: the explicit absolute value (when positive),
 * the explicit ratio per CPU, the default ratio per CPU; a ratio is multiplied
 * by the CPUs and rounded up, with a minimum of 1 (a ratio of 0 means no
 * limit).
 */
public final class JmsCpuLimits {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JmsCpuLimits.class);

	/**
	 * Property that pins the CPUs.
	 */
	public static final String CPUS_PROPERTY = "org.coldis.library.service.cpus";

	/**
	 * Default cgroup root.
	 */
	public static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");

	/**
	 * CPUs set through {@value #CPUS_PROPERTY} (null when not set).
	 */
	private static volatile Double configuredCpus;

	/**
	 * CPUs detected from the cgroup or the runtime (null until first use).
	 */
	private static volatile Double detectedCpus;

	/**
	 * No instances.
	 */
	private JmsCpuLimits() {
	}

	/**
	 * Pins the CPUs (the value of {@value #CPUS_PROPERTY}).
	 *
	 * @param cpus CPUs (null or not positive: detect them).
	 */
	public static void setConfiguredCpus(
			final Double cpus) {
		JmsCpuLimits.configuredCpus = ((cpus == null) || (cpus <= 0) ? null : cpus);
	}

	/**
	 * Reads a file of the cgroup, trimmed.
	 *
	 * @param  file File.
	 * @return      The content, or null when it cannot be read.
	 */
	private static String read(
			final Path file) {
		String content = null;
		try {
			content = (Files.isReadable(file) ? StringUtils.trimToNull(Files.readString(file, StandardCharsets.UTF_8)) : null);
		}
		catch (final IOException exception) {
			JmsCpuLimits.LOGGER.debug("Could not read '{}'.", file, exception);
		}
		return content;
	}

	/**
	 * Divides a quota by its period.
	 *
	 * @param  quota  Quota ("max" or negative: none).
	 * @param  period Period.
	 * @return        The CPUs, or null when there is no quota.
	 */
	private static Double divide(
			final String quota,
			final String period) {
		Double cpus = null;
		if ((quota != null) && (period != null) && quota.matches("\\d+") && period.matches("\\d+") && (Long.parseLong(period) > 0)) {
			cpus = Long.parseLong(quota) / (double) Long.parseLong(period);
		}
		return ((cpus == null) || (cpus <= 0) ? null : cpus);
	}

	/**
	 * Detects the CPUs from a cgroup tree: v2 {@code cpu.max}, then v1
	 * {@code cpu.cfs_quota_us / cpu.cfs_period_us} (under the root or its
	 * {@code cpu} / {@code cpu,cpuacct} controllers), capped at the runtime's
	 * processors; without a quota, the runtime's processors.
	 *
	 * @param  cgroupRoot        Cgroup root.
	 * @param  runtimeProcessors Runtime processors.
	 * @return                   The CPUs.
	 */
	public static double detectCpus(
			final Path cgroupRoot,
			final IntSupplier runtimeProcessors) {
		final double processors = Math.max(1, runtimeProcessors.getAsInt());
		final String cpuMax = JmsCpuLimits.read(cgroupRoot.resolve("cpu.max"));
		Double quota = (cpuMax == null ? null : JmsCpuLimits.divide(StringUtils.substringBefore(cpuMax, " "), StringUtils.substringAfter(cpuMax, " ")));
		for (final String controller : List.of("", "cpu", "cpu,cpuacct")) {
			final Path directory = (controller.isEmpty() ? cgroupRoot : cgroupRoot.resolve(controller));
			quota = (quota == null
					? JmsCpuLimits.divide(JmsCpuLimits.read(directory.resolve("cpu.cfs_quota_us")), JmsCpuLimits.read(directory.resolve("cpu.cfs_period_us")))
					: quota);
		}
		return (quota == null ? processors : Math.min(quota, processors));
	}

	/**
	 * Gets the CPUs limits are multiplied by: the pinned value, else the
	 * detected one (detected once).
	 *
	 * @return The CPUs.
	 */
	public static double getAvailableCpus() {
		final Double pinnedCpus = JmsCpuLimits.configuredCpus;
		if ((pinnedCpus == null) && (JmsCpuLimits.detectedCpus == null)) {
			JmsCpuLimits.detectedCpus = JmsCpuLimits.detectCpus(JmsCpuLimits.CGROUP_ROOT, () -> Runtime.getRuntime().availableProcessors());
			JmsCpuLimits.LOGGER.info("JMS limits use {} CPUs (cgroup quota or runtime; pin with {}).", JmsCpuLimits.detectedCpus,
					JmsCpuLimits.CPUS_PROPERTY);
		}
		return (pinnedCpus == null ? JmsCpuLimits.detectedCpus : pinnedCpus);
	}

	/**
	 * Resolves a limit against a CPU source: the absolute value when positive,
	 * else the ratio, else the default ratio, times the CPUs, rounded up, at
	 * least 1.
	 *
	 * @param  absolute      Explicit absolute value (null when not set).
	 * @param  ratio         Explicit ratio per CPU (null when not set).
	 * @param  defaultRatio  Default ratio per CPU (null for no default).
	 * @param  availableCpus CPU source.
	 * @return               The limit, or null when none applies (no value, or
	 *                       a ratio not positive).
	 */
	public static Integer resolve(
			final Integer absolute,
			final Double ratio,
			final Double defaultRatio,
			final DoubleSupplier availableCpus) {
		final Double actualRatio = (ratio == null ? defaultRatio : ratio);
		Integer limit = null;
		if ((absolute != null) && (absolute > 0)) {
			limit = absolute;
		}
		else if ((actualRatio != null) && (actualRatio > 0)) {
			limit = Math.max(1, (int) Math.ceil(availableCpus.getAsDouble() * actualRatio));
		}
		return limit;
	}

	/**
	 * Resolves a limit against {@link #getAvailableCpus()}.
	 *
	 * @param  absolute     Explicit absolute value (null when not set).
	 * @param  ratio        Explicit ratio per CPU (null when not set).
	 * @param  defaultRatio Default ratio per CPU (null for no default).
	 * @return              The limit, or null when none applies.
	 * @see                 #resolve(Integer, Double, Double, DoubleSupplier)
	 */
	public static Integer resolve(
			final Integer absolute,
			final Double ratio,
			final Double defaultRatio) {
		return JmsCpuLimits.resolve(absolute, ratio, defaultRatio, JmsCpuLimits::getAvailableCpus);
	}

}
