package org.coldis.library.service.batch;

import org.coldis.library.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch service.
 */
@RestController
@RequestMapping(path = "batch")
@ConditionalOnProperty(
		name = "org.coldis.configuration.service.batch-enabled",
		matchIfMissing = false
)
public class BatchService {

	/**
	 * Batch key prefix.
	 */
	public static final String BATCH_KEY_PREFIX = BatchServiceComponent.BATCH_KEY_PREFIX;

	/**
	 * Batch service component.
	 */
	@Autowired
	private BatchServiceComponent batchServiceComponent;

	/**
	 * Starts a batch.
	 *
	 * @param  executor               Executor.
	 * @param  restart                If the batch should be restarted.
	 * @param  useLastCountAsExpected If the last count should be used as expected.
	 * @throws BusinessException      If the batch fails.
	 */
	@RequestMapping(method = RequestMethod.POST)
	public <Type> void start(
			@RequestBody
			final BatchExecutor<Type> executor,
			@RequestParam(defaultValue = "false")
			final Boolean restart,
			@RequestParam(defaultValue = "true")
			final Boolean useLastCountAsExpected) throws BusinessException {
		this.batchServiceComponent.start(executor, restart, useLastCountAsExpected);
	}

	/**
	 * Resumes a batch.
	 *
	 * @param  keySuffix         Key suffix.
	 * @throws BusinessException If the batch fails.
	 */
	@RequestMapping(
			method = RequestMethod.PUT,
			path = "{keySuffix}"
	)
	public <Type> void resume(
			@PathVariable
			final String keySuffix) throws BusinessException {
		this.batchServiceComponent.resume(keySuffix);
	}

	/**
	 * Cancels a batch.
	 *
	 * @param  keySuffix         Key suffix.
	 * @throws BusinessException If the batch fails.
	 */
	@RequestMapping(
			method = RequestMethod.DELETE,
			path = "{keySuffix}"
	)
	public <Type> void cancel(
			@PathVariable
			final String keySuffix) throws BusinessException {
		this.batchServiceComponent.cancel(keySuffix);
	}

	/**
	 * Cleans old batches.
	 *
	 * @param  sync              If the deletion should be synchronous.
	 * @throws BusinessException If the batches cannot be cleaned.
	 */
	@RequestMapping(
			method = RequestMethod.DELETE,
			path = "*/clean"
	)
	public void cleanAll(
			@RequestParam(defaultValue = "false")
			final Boolean sync) throws BusinessException {
		this.batchServiceComponent.cleanAll(sync);
	}

	/**
	 * Checks all batches (cleans old ones and rescues overdue ones).
	 *
	 * @throws BusinessException If the batches cannot be checked.
	 */
	@RequestMapping(
			method = RequestMethod.POST,
			path = "*/check"
	)
	public void checkAll() throws BusinessException {
		this.batchServiceComponent.checkAll();
	}

}
