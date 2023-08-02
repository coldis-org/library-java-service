package org.coldis.library.test.service.batch;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.coldis.library.exception.IntegrationException;
import org.springframework.stereotype.Component;

/**
 * Test service.
 */
@Component
public class BatchTestService {

	private static Random RANDOM = new Random();
	public static Long processedAlways = 0L;
	public static Long processedLatestCompleteBatch = 0L;
	public static Long processedLatestPartialBatch = 0L;

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
	public List<BatchObject> get(
			final BatchObject object,
			final Long size,
			final Map<String, String> arguments) {
		return BatchTestService.processedLatestCompleteBatch < 100 ? List.of(new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt())),
				new BatchObject(Objects.toString(BatchTestService.RANDOM.nextInt()))) : List.of();
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#finish()
	 */
	public void finish() {
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#execute(java.lang.String)
	 */
	public synchronized void execute(
			final BatchObject object) {
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
	}
}
