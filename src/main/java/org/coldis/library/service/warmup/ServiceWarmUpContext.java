package org.coldis.library.service.warmup;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marks the threads that run warm-up calls, so that the library refuses or drops what those calls must not do. Code
 * outside this library can use {@link #isWarmingUp()} to skip its own side effects, such as sending logs. Checking a
 * thread costs a single volatile read once no warm-up runs.
 */
public final class ServiceWarmUpContext {

	/**
	 * Number of threads running warm-up calls.
	 */
	private static final AtomicInteger WARMING_UP_THREADS = new AtomicInteger();

	/**
	 * Whether the current thread runs warm-up calls.
	 */
	private static final ThreadLocal<Boolean> WARMING_UP = new ThreadLocal<>();

	/**
	 * No instances.
	 */
	private ServiceWarmUpContext() {
		super();
	}

	/**
	 * Gets whether the current thread runs warm-up calls.
	 *
	 * @return Whether the current thread runs warm-up calls.
	 */
	public static boolean isWarmingUp() {
		return (ServiceWarmUpContext.WARMING_UP_THREADS.get() > 0) && Boolean.TRUE.equals(ServiceWarmUpContext.WARMING_UP.get());
	}

	/**
	 * Refuses a side effect when the current thread runs warm-up calls.
	 *
	 * @param  sideEffect                       Name of the side effect, used in the error message.
	 * @throws ServiceWarmUpSideEffectException If the current thread runs warm-up calls.
	 */
	public static void rejectDuringWarmUp(
			final String sideEffect) {
		if (ServiceWarmUpContext.isWarmingUp()) {
			throw new ServiceWarmUpSideEffectException(sideEffect);
		}
	}

	/**
	 * Marks the current thread as running warm-up calls.
	 */
	static void enter() {
		ServiceWarmUpContext.WARMING_UP.set(Boolean.TRUE);
		ServiceWarmUpContext.WARMING_UP_THREADS.incrementAndGet();
	}

	/**
	 * Unmarks the current thread.
	 */
	static void exit() {
		ServiceWarmUpContext.WARMING_UP_THREADS.decrementAndGet();
		ServiceWarmUpContext.WARMING_UP.remove();
	}

}
