package org.coldis.library.test.service.batch;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Test service.
 */
@Component
public class BatchTestService {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(BatchTestService.class);

	private static Random RANDOM = new SecureRandom();
	public static Long processedAlways = 0L;
	public static Long processedLatestCompleteBatch = 0L;
	public static Long processedLatestPartialBatch = 0L;

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#start()
	 */
	public static void clear() {
		BatchTestService.processedAlways = 0L;
		BatchTestService.processedLatestCompleteBatch = 0L;
		BatchTestService.processedLatestPartialBatch = 0L;
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#start()
	 */
	public void start() {
		BatchTestService.processedLatestCompleteBatch = 0L;
		BatchTestService.processedLatestPartialBatch = 0L;
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#resume()
	 */
	public void resume() {
		BatchTestService.processedLatestPartialBatch = 0L;
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#get()
	 */
	public synchronized List<BatchObject> get(
			final BatchObject object,
			final Long size,
			final Map<String, String> arguments) {
		return BatchTestService.processedLatestCompleteBatch < 100 ? List.of(

				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())), new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())), new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())), new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())), new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong())), new BatchObject(Objects.toString(BatchTestService.RANDOM.nextLong()))

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
		BatchTestService.LOGGER.info("Starting batch from '{}' at {}.", object, DateTimeHelper.getCurrentLocalDateTime());
		if (BatchTestService.processedLatestPartialBatch >= 10) {
			throw new IntegrationException();
		}
		try {
			BatchTestService.processedAlways++;
			BatchTestService.processedLatestCompleteBatch++;
			BatchTestService.processedLatestPartialBatch++;
		}
		catch (final Exception exception) {
		}
		BatchTestService.LOGGER.info("Batch item processed. Total of {} items processed.", 
				BatchTestService.processedLatestCompleteBatch);
	}
}
