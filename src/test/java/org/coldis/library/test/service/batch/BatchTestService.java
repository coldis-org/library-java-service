package org.coldis.library.test.service.batch;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.batch.BatchPostponed;
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

	/** Counted down when the first item of an iteration begins executing. */
	public static volatile CountDownLatch executingSignal = null;

	/** Batch awaits on this before processing each item (used to hold the row lock open). */
	public static volatile CountDownLatch holdLatch = null;

	/** GET calls {@link #getAfterPostponing} postpones before returning items. */
	public static volatile Integer postponeSteps = 0;

	/** GET calls postponed so far. */
	public static volatile Integer postponedSteps = 0;

	/** START delegate calls. */
	public static volatile Integer startCount = 0;

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#start()
	 */
	public static void clear() {
		BatchTestService.processedAlways = 0L;
		BatchTestService.processedLatestCompleteBatch = 0L;
		BatchTestService.processedLatestPartialBatch = 0L;
		BatchTestService.executingSignal = null;
		BatchTestService.holdLatch = null;
		BatchTestService.postponeSteps = 0;
		BatchTestService.postponedSteps = 0;
		BatchTestService.startCount = 0;
	}

	/**
	 * @see org.coldis.library.persistence.batch.BatchExecutor#start()
	 */
	public void start() {
		BatchTestService.startCount++;
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
	 * GET that postpones its first {@link #postponeSteps} calls, then behaves as {@link #get}.
	 *
	 * @param  object    Last processed.
	 * @param  size      Size.
	 * @param  arguments Arguments.
	 * @return           The postponed marker, then the items.
	 */
	public synchronized List<BatchObject> getAfterPostponing(
			final BatchObject object,
			final Long size,
			final Map<String, String> arguments) {
		List<BatchObject> batch;
		if (BatchTestService.postponedSteps < BatchTestService.postponeSteps) {
			BatchTestService.postponedSteps++;
			batch = BatchPostponed.of();
		}
		else {
			batch = this.get(object, size, arguments);
		}
		return batch;
	}

	/**
	 * GET that (wrongly) returns null.
	 *
	 * @param  object    Last processed.
	 * @param  size      Size.
	 * @param  arguments Arguments.
	 * @return           Null.
	 */
	public List<BatchObject> getNull(
			final BatchObject object,
			final Long size,
			final Map<String, String> arguments) {
		return null;
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
		final CountDownLatch signal = BatchTestService.executingSignal;
		if (signal != null) {
			signal.countDown();
		}
		final CountDownLatch hold = BatchTestService.holdLatch;
		if (hold != null) {
			try {
				hold.await();
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
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
