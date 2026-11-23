package org.coldis.library.service.jms;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Resolves the JMS thread and handler limits, and holds the one CPU source
 * they are multiplied by. A limit resolves, first match: the explicit absolute
 * value (when positive), the explicit ratio (per CPU), the default ratio (per
 * CPU); ratios are multiplied by {@link #getAvailableCpus()} and rounded up.
 * <p>
 * The CPU source defaults to {@link Runtime#availableProcessors()}. Where that
 * reports more CPUs than the process may use (e.g. CPU shares rather than a
 * CPU quota), replace it with {@link #setAvailableCpusSupplier(IntSupplier)}
 * before the JMS configuration is created.
 */
public final class JmsCpuLimits {

	/**
	 * Default CPU source.
	 */
	public static final IntSupplier RUNTIME_AVAILABLE_CPUS = () -> Runtime.getRuntime().availableProcessors();

	/**
	 * CPU source.
	 */
	private static volatile IntSupplier availableCpusSupplier = JmsCpuLimits.RUNTIME_AVAILABLE_CPUS;

	/**
	 * No instances.
	 */
	private JmsCpuLimits() {
	}

	/**
	 * Sets the CPU source.
	 *
	 * @param availableCpusSupplier CPU source (null restores the default).
	 */
	public static void setAvailableCpusSupplier(
			final IntSupplier availableCpusSupplier) {
		JmsCpuLimits.availableCpusSupplier = Objects.requireNonNullElse(availableCpusSupplier, JmsCpuLimits.RUNTIME_AVAILABLE_CPUS);
	}

	/**
	 * Gets the CPUs limits are multiplied by (at least 1).
	 *
	 * @return The CPUs.
	 */
	public static int getAvailableCpus() {
		return Math.max(1, JmsCpuLimits.availableCpusSupplier.getAsInt());
	}

	/**
	 * Resolves a limit against a given CPU source: the absolute value when
	 * positive, else the ratio, else the default ratio, times the CPUs (rounded
	 * up).
	 *
	 * @param  absolute      Explicit absolute value (null when not set).
	 * @param  ratio         Explicit ratio per CPU (null when not set).
	 * @param  defaultRatio  Default ratio per CPU (null for no default).
	 * @param  availableCpus CPU source.
	 * @return               The limit, or null when none applies (or it is not
	 *                       positive).
	 */
	public static Integer resolve(
			final Integer absolute,
			final Double ratio,
			final Double defaultRatio,
			final IntSupplier availableCpus) {
		final Double actualRatio = (ratio == null ? defaultRatio : ratio);
		Integer limit = null;
		if ((absolute != null) && (absolute > 0)) {
			limit = absolute;
		}
		else if (actualRatio != null) {
			limit = (int) Math.ceil(Math.max(1, availableCpus.getAsInt()) * actualRatio);
		}
		return ((limit == null) || (limit <= 0) ? null : limit);
	}

	/**
	 * Resolves a limit against the CPU source ({@link #getAvailableCpus()}).
	 *
	 * @param  absolute     Explicit absolute value (null when not set).
	 * @param  ratio        Explicit ratio per CPU (null when not set).
	 * @param  defaultRatio Default ratio per CPU (null for no default).
	 * @return              The limit, or null when none applies.
	 * @see                 #resolve(Integer, Double, Double, IntSupplier)
	 */
	public static Integer resolve(
			final Integer absolute,
			final Double ratio,
			final Double defaultRatio) {
		return JmsCpuLimits.resolve(absolute, ratio, defaultRatio, JmsCpuLimits.availableCpusSupplier);
	}

}
