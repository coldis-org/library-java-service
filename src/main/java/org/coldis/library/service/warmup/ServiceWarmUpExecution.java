package org.coldis.library.service.warmup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

/**
 * State of one warm-up while it runs: its inputs, the next input to use, the call counts and the durations of the
 * first and of the last calls.
 *
 * @param <InputType> Input type.
 */
final class ServiceWarmUpExecution<InputType> {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ServiceWarmUpExecution.class);

	/**
	 * Number of call durations kept from the start and from the end of the calls.
	 */
	private static final int DURATION_WINDOW = 100;

	/**
	 * Nanoseconds per millisecond.
	 */
	private static final double NANOSECONDS_PER_MILLISECOND = 1_000_000.0;

	/**
	 * Warm-up.
	 */
	private final ServiceWarmUp<InputType> warmUp;

	/**
	 * Warm-up name.
	 */
	private final String name;

	/**
	 * Read-only transaction around each call, or null to call without one.
	 */
	private final TransactionTemplate transactionTemplate;

	/**
	 * Consecutive failed calls that stop the warm-up.
	 */
	private final Integer maximumConsecutiveErrors;

	/**
	 * Lock that serializes the read of the inputs, apart from the lock of the counters, so that a slow read does not
	 * block the checks of the stop conditions.
	 */
	private final Object inputsLock = new Object();

	/**
	 * Index of the next input to use.
	 */
	private final AtomicInteger nextInput = new AtomicInteger();

	/**
	 * Durations of the first calls, in nanoseconds.
	 */
	private final long[] firstDurations = new long[ServiceWarmUpExecution.DURATION_WINDOW];

	/**
	 * Durations of the last calls, in nanoseconds (a ring).
	 */
	private final long[] lastDurations = new long[ServiceWarmUpExecution.DURATION_WINDOW];

	/**
	 * Inputs, read on the first call.
	 */
	private volatile List<InputType> inputs;

	/**
	 * Number of calls.
	 */
	private long callCount;

	/**
	 * Number of failed calls.
	 */
	private long failedCallCount;

	/**
	 * Number of consecutive failed calls.
	 */
	private long consecutiveFailedCallCount;

	/**
	 * First error.
	 */
	private Throwable firstError;

	/**
	 * Why the warm-up stopped, or null while it runs.
	 */
	private volatile ServiceWarmUpStopReason stopReason;

	/**
	 * Unchecked carrier of a checked exception thrown by a call inside a transaction callback.
	 */
	private static final class CallException extends RuntimeException {

		/**
		 * Serial.
		 */
		private static final long serialVersionUID = 6411283097540318466L;

		/**
		 * Constructor.
		 *
		 * @param callError Error thrown by the call.
		 */
		CallException(final Exception callError) {
			super(callError);
		}

		/**
		 * Gets the error thrown by the call.
		 *
		 * @return The error thrown by the call.
		 */
		Exception getCallError() {
			return (Exception) this.getCause();
		}

	}

	/**
	 * Constructor.
	 *
	 * @param warmUp                   Warm-up.
	 * @param transactionTemplate      Read-only transaction around each call, or null to call without one.
	 * @param maximumConsecutiveErrors Consecutive failed calls that stop the warm-up.
	 */
	private ServiceWarmUpExecution(
			final ServiceWarmUp<InputType> warmUp,
			final TransactionTemplate transactionTemplate,
			final Integer maximumConsecutiveErrors) {
		super();
		this.warmUp = warmUp;
		this.name = ClassUtils.getShortName(ClassUtils.getUserClass(warmUp));
		this.transactionTemplate = transactionTemplate;
		this.maximumConsecutiveErrors = maximumConsecutiveErrors;
	}

	/**
	 * Creates the execution of a warm-up.
	 *
	 * @param  <InputType>              Input type.
	 * @param  warmUp                   Warm-up.
	 * @param  transactionTemplate      Read-only transaction around each call, or null to call without one.
	 * @param  maximumConsecutiveErrors Consecutive failed calls that stop the warm-up.
	 * @return                          The execution.
	 */
	static <InputType> ServiceWarmUpExecution<InputType> of(
			final ServiceWarmUp<InputType> warmUp,
			final TransactionTemplate transactionTemplate,
			final Integer maximumConsecutiveErrors) {
		return new ServiceWarmUpExecution<>(warmUp, transactionTemplate, maximumConsecutiveErrors);
	}

	/**
	 * Gets the median duration of calls, in milliseconds.
	 *
	 * @param  durations Call durations, in nanoseconds.
	 * @param  count     Number of durations to use, from the start of the array.
	 * @return           The median duration, in milliseconds, or null without durations.
	 */
	private static Double getMedianMillis(
			final long[] durations,
			final int count) {
		Double median = null;
		if (count > 0) {
			final long[] sortedDurations = Arrays.copyOf(durations, count);
			Arrays.sort(sortedDurations);
			median = sortedDurations[count / 2] / ServiceWarmUpExecution.NANOSECONDS_PER_MILLISECOND;
		}
		return median;
	}

	/**
	 * Runs a call and rethrows its checked exceptions unchecked, wrapped in a {@link CallException}.
	 *
	 * @param  <ResultType> Result type.
	 * @param  call         Call.
	 * @return              The call result.
	 */
	private static <ResultType> ResultType callUnchecked(
			final Callable<ResultType> call) {
		ResultType result = null;
		try {
			result = call.call();
		}
		catch (final RuntimeException exception) {
			throw exception;
		}
		catch (final Exception exception) {
			throw new CallException(exception);
		}
		return result;
	}

	/**
	 * Runs a call inside a read-only transaction that is always rolled back, or without a transaction when there is no
	 * transaction template.
	 *
	 * @param  <ResultType> Result type.
	 * @param  call         Call.
	 * @return              The call result.
	 * @throws Exception    If the call fails.
	 */
	private <ResultType> ResultType callInReadOnlyTransaction(
			final Callable<ResultType> call) throws Exception {
		ResultType result = null;
		if (this.transactionTemplate == null) {
			result = call.call();
		}
		else {
			try {
				result = this.transactionTemplate.execute(transactionStatus -> {
					// Nothing a warm-up call does may be committed.
					transactionStatus.setRollbackOnly();
					return ServiceWarmUpExecution.callUnchecked(call);
				});
			}
			catch (final CallException exception) {
				throw exception.getCallError();
			}
		}
		return result;
	}

	/**
	 * Stops the warm-up, unless it already stopped for another reason.
	 *
	 * @param reason Why it stops, or null to keep it running.
	 */
	synchronized void stop(
			final ServiceWarmUpStopReason reason) {
		if ((this.stopReason == null) && (reason != null)) {
			this.stopReason = reason;
		}
	}

	/**
	 * Records that the inputs could not be read, which stops the warm-up.
	 *
	 * @param error Error from reading the inputs.
	 */
	private synchronized void failInputs(
			final Throwable error) {
		ServiceWarmUpExecution.LOGGER.warn("Warm-up '" + this.name + "' could not read its inputs, so it makes no calls.", error);
		if (this.firstError == null) {
			this.firstError = error;
		}
		this.stop(ServiceWarmUpStopReason.INPUTS_FAILED);
	}

	/**
	 * Gets the inputs, reading them on the first call.
	 *
	 * @return The inputs, empty when they could not be read.
	 */
	private List<InputType> getInputs() {
		List<InputType> currentInputs = this.inputs;
		if (currentInputs == null) {
			synchronized (this.inputsLock) {
				if (this.inputs == null) {
					List<InputType> readInputs = List.of();
					try {
						final List<InputType> warmUpInputs = this.callInReadOnlyTransaction(this.warmUp::inputs);
						readInputs = (warmUpInputs == null ? List.of() : new ArrayList<>(warmUpInputs));
					}
					catch (final Exception exception) {
						this.failInputs(exception);
					}
					// Only errors the JVM cannot recover from end the thread.
					catch (final Error inputsError) {
						if (inputsError instanceof VirtualMachineError) {
							throw inputsError;
						}
						this.failInputs(inputsError);
					}
					this.inputs = readInputs;
				}
				currentInputs = this.inputs;
			}
		}
		return currentInputs;
	}

	/**
	 * Records a call.
	 *
	 * @param durationNanos Call duration, in nanoseconds.
	 * @param error         Error thrown by the call, or null when it succeeded.
	 */
	private synchronized void recordCall(
			final long durationNanos,
			final Throwable error) {
		if (this.callCount < ServiceWarmUpExecution.DURATION_WINDOW) {
			this.firstDurations[(int) this.callCount] = durationNanos;
		}
		this.lastDurations[(int) (this.callCount % ServiceWarmUpExecution.DURATION_WINDOW)] = durationNanos;
		this.callCount++;
		if (error == null) {
			this.consecutiveFailedCallCount = 0;
		}
		else {
			this.failedCallCount++;
			this.consecutiveFailedCallCount++;
			if (this.firstError == null) {
				this.firstError = error;
				ServiceWarmUpExecution.LOGGER.warn("Warm-up '" + this.name + "' call failed. Later failures are only counted and logged at debug.", error);
			}
			else {
				ServiceWarmUpExecution.LOGGER.debug("Warm-up '" + this.name + "' call failed.", error);
			}
			if (this.consecutiveFailedCallCount >= this.maximumConsecutiveErrors) {
				this.stop(ServiceWarmUpStopReason.ERRORS);
			}
		}
	}

	/**
	 * Gets whether the warm-up still runs.
	 *
	 * @return Whether the warm-up still runs.
	 */
	boolean isActive() {
		return this.stopReason == null;
	}

	/**
	 * Gets the number of calls.
	 *
	 * @return The number of calls.
	 */
	synchronized long getCallCount() {
		return this.callCount;
	}

	/**
	 * Makes the call for the next input, or stops the warm-up when the inputs ended.
	 */
	void callNext() {
		final List<InputType> currentInputs = this.getInputs();
		final int inputIndex = this.nextInput.getAndIncrement();
		if (inputIndex >= currentInputs.size()) {
			this.stop(ServiceWarmUpStopReason.INPUTS_EXHAUSTED);
		}
		else {
			final InputType input = currentInputs.get(inputIndex);
			final long startNanos = System.nanoTime();
			Throwable error = null;
			try {
				this.callInReadOnlyTransaction(() -> {
					this.warmUp.warmUp(input);
					return null;
				});
			}
			catch (final Exception exception) {
				error = exception;
			}
			// An error from the call (an assertion, a class that fails to initialize) fails the call too; only errors the
			// JVM cannot recover from end the thread.
			catch (final Error callError) {
				if (callError instanceof VirtualMachineError) {
					throw callError;
				}
				error = callError;
			}
			this.recordCall(System.nanoTime() - startNanos, error);
		}
	}

	/**
	 * Gets the result of the warm-up.
	 *
	 * @return The result of the warm-up.
	 */
	synchronized ServiceWarmUpResult toResult() {
		final int durationCount = (int) Math.min(this.callCount, ServiceWarmUpExecution.DURATION_WINDOW);
		return new ServiceWarmUpResult(this.name, this.callCount, this.failedCallCount, ServiceWarmUpExecution.getMedianMillis(this.firstDurations, durationCount),
				ServiceWarmUpExecution.getMedianMillis(this.lastDurations, durationCount), this.stopReason, this.firstError);
	}

}
