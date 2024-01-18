package org.coldis.library.test.service.batch;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.coldis.library.exception.IntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test service.
 */
public class BatchTestServiceBase {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(BatchTestServiceBase.class);

	private static Random RANDOM = new SecureRandom();
	public static Long processedAlways = 0L;
	public static Long processedLatestCompleteBatch = 0L;
	public static Long processedLatestPartialBatch = 0L;

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#start()
	 */
	public void start() {
		BatchTestServiceBase.processedLatestCompleteBatch = 0L;
		BatchTestServiceBase.processedLatestPartialBatch = 0L;
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#resume()
	 */
	public void resume() {
		BatchTestServiceBase.processedLatestPartialBatch = 0L;
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#get()
	 */
	public List<BatchObject> get(
			final BatchObject object,
			final Long size,
			final Map<String, String> arguments) {
		return BatchTestServiceBase.processedLatestCompleteBatch < 100 ? List.of(

				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestServiceBase.RANDOM.nextLong()))

		) : List.of();
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#finish()
	 */
	public void finish() {
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#execute(java.lang.String)
	 */
	public void execute(
			final BatchObject object) {
		if (BatchTestServiceBase.processedLatestPartialBatch >= 10) {
			throw new IntegrationException();
		}
		try {
			BatchTestServiceBase.processedAlways++;
			BatchTestServiceBase.processedLatestCompleteBatch++;
			BatchTestServiceBase.processedLatestPartialBatch++;
		}
		catch (final Exception exception) {
		}
		BatchTestServiceBase.LOGGER.info("Batch item processed: " + BatchTestServiceBase.processedLatestCompleteBatch);
	}
}
