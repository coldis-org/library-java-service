package org.coldis.library.service.warmup;

import java.util.List;

/**
 * Warm-up routine of a service: real calls that run before the service takes traffic, so that the JIT compiles the
 * code they exercise before requests queue behind it.
 *
 * <p>
 * Warm-ups run only when the service turns the warm-up on ({@value ServiceWarmUpProperties#ENABLED_PROPERTY}, off by
 * default). The library then reads {@link #inputs()} once and calls {@link #warmUp(Object)} once per input, from
 * several threads at once, while the health check answers 503. Each input is used at most once, so a cached method still runs its
 * uncached path. It stops when the JIT stops compiling after a minimum of calls, when the time limit is reached or
 * when the inputs end, and then opens the health check.
 * </p>
 *
 * <p>
 * The calls must follow read paths only: a change to a loaded entity is discarded without an error, so a write path
 * does not stop the call. The library runs each one inside a read-only transaction that is rolled back, runs the
 * service's {@code @Transactional} methods read-only, refuses any other new read-write transaction, refuses pooled JMS
 * connections, {@code RestTemplate} requests and statistics, and drops cache writes (see {@link ServiceWarmUpContext}).
 * A call that needs any of them fails and is counted as an error. Not covered, so the calls must avoid them: writes
 * made outside any transaction (auto-commit), work handed to another thread, other HTTP clients and native JMS
 * connection factories.
 * </p>
 *
 * @param <InputType> Input type.
 */
public interface ServiceWarmUp<InputType> {

	/**
	 * Gets the inputs of the calls, read once before them, for instance the ids of the most recent records in the
	 * service's own database.
	 *
	 * @return           The inputs.
	 * @throws Exception If the inputs cannot be read.
	 */
	List<InputType> inputs() throws Exception;

	/**
	 * Runs one call, the equivalent of one request, for an input. It is called from several threads at once.
	 *
	 * @param  input     Input.
	 * @throws Exception If the call fails.
	 */
	void warmUp(
			InputType input) throws Exception;

}
