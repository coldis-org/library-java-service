package org.coldis.library.test.service.warmup;

import java.util.List;
import java.util.concurrent.Callable;

import org.coldis.library.service.warmup.ServiceWarmUp;

/**
 * Warm-up made of a list of inputs and a call, for tests. It is not a bean, so it does not hold the health check of
 * the shared test context.
 *
 * @param <InputType> Input type.
 */
public class ServiceWarmUpTestRoutine<InputType> implements ServiceWarmUp<InputType> {

	/**
	 * Call made for each input.
	 *
	 * @param <CallInputType> Input type.
	 */
	@FunctionalInterface
	public interface Call<CallInputType> {

		/**
		 * Makes the call.
		 *
		 * @param  input     Input.
		 * @throws Exception If the call fails.
		 */
		void call(
				CallInputType input) throws Exception;

	}

	/**
	 * Inputs.
	 */
	private final Callable<List<InputType>> inputs;

	/**
	 * Call.
	 */
	private final Call<InputType> call;

	/**
	 * Constructor.
	 *
	 * @param inputs Inputs.
	 * @param call   Call made for each input.
	 */
	public ServiceWarmUpTestRoutine(final Callable<List<InputType>> inputs, final Call<InputType> call) {
		super();
		this.inputs = inputs;
		this.call = call;
	}

	/**
	 * @see org.coldis.library.service.warmup.ServiceWarmUp#inputs()
	 */
	@Override
	public List<InputType> inputs() throws Exception {
		return this.inputs.call();
	}

	/**
	 * @see org.coldis.library.service.warmup.ServiceWarmUp#warmUp(java.lang.Object)
	 */
	@Override
	public void warmUp(
			final InputType input) throws Exception {
		this.call.call(input);
	}

}
