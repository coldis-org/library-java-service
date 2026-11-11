package org.coldis.library.service.batch;

/**
 * Thrown by a batch GET delegate to postpone the run: nothing to do yet, but the run is not over.
 * The batch service catches it (also when wrapped by the delegate invocation), keeps the cursor
 * and the count, does not finish the run, records the postponement ({@code lastBatchPostponed},
 * {@code postponedCount}) and runs the next step after the usual delay. GET delegates that never
 * throw it are unaffected: an empty list finishes the run, and a null result (or a missing GET
 * delegate) is an error.
 *
 * <p>A GET delegate running in its own {@code @Transactional} must declare
 * {@code noRollbackFor = BatchPostponedException.class}, or the step's transaction is marked
 * rollback-only.
 */
public class BatchPostponedException extends RuntimeException {

	/** Serial. */
	private static final long serialVersionUID = 4113268146353790118L;

	/**
	 * Constructor.
	 *
	 * @param reason Why the run waits (logged with the batch key: no item values).
	 */
	public BatchPostponedException(final String reason) {
		super(reason);
	}

}
