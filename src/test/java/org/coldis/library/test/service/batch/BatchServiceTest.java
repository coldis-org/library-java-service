package org.coldis.library.test.service.batch;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.batch.BatchExecutor;
import org.coldis.library.service.batch.BatchPostponed;
import org.coldis.library.service.batch.BatchAction;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.service.batch.BatchServiceComponent;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Batch record test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class BatchServiceTest extends ContainerTestHelper {

	/**
	 * Regular clock.
	 */
	public static final Clock REGULAR_CLOCK = DateTimeHelper.getClock();

	/**
	 * Key/value service.
	 */
	@Autowired
	private KeyValueServiceComponent keyValueService;

	/**
	 * Batch service.
	 */
	@Autowired
	private BatchService batchService;

	/**
	 * Batch service component.
	 */
	@Autowired
	private BatchServiceComponent batchServiceComponent;

	/**
	 * Cleans after each test.
	 *
	 * @throws Exception If the test fails.
	 */
	@BeforeEach
	public void cleanBeforeEachTest() throws Exception {
		TestHelper.waitUntilValid(() -> {
			try {
				this.batchService.cleanAll(true);
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException exception) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.REGULAR_WAIT * 3, TestHelper.SHORT_WAIT);
		BatchTestService.clear();
	}

	/**
	 * Cleans after each test.
	 *
	 * @throws BusinessException
	 */
	@AfterEach
	public void cleanAfterEachTest() throws BusinessException {
		// Sets back to the regular clock.
		DateTimeHelper.setClock(BatchServiceTest.REGULAR_CLOCK);
	}

	/**
	 * Tests a batch execution.
	 *
	 * @param  testBatchExecutor Executor.
	 * @throws Exception         If the test fails.
	 */
	@SuppressWarnings("unchecked")
	private void testBatch(
			final BatchExecutor<BatchObject> testBatchExecutor,
			final Long processedNow,
			final Long processedTotal) throws BusinessException, Exception {

		final String batchKey = this.batchServiceComponent.getKey(testBatchExecutor.getKeySuffix());

		// Initial record.
		BatchExecutor<BatchObject> batchRecord = null;
		try {
			batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
		}
		catch (final BusinessException exception) {
		}
		final LocalDateTime initialStartTime = ((batchRecord == null) || (batchRecord.getLastStartedAt() == null)
				? DateTimeHelper.getCurrentLocalDateTime().minusYears(1L)
				: batchRecord.getLastStartedAt());
		final LocalDateTime initialFinishTime = ((batchRecord == null) || (batchRecord.getLastFinishedAt() == null)
				? DateTimeHelper.getCurrentLocalDateTime().minusYears(1L)
				: batchRecord.getLastFinishedAt());

		// Starts the batch and makes sure it has started.
		this.batchService.checkAll();
		this.batchService.start(testBatchExecutor, false, false);
		this.batchService.checkAll();

		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			}
			catch (final BusinessException exception) {
				return null;
			}
		}, record -> ((record != null) && (record.getLastStartedAt() != null) && record.getLastStartedAt().isAfter(initialStartTime)
				&& (record.getLastProcessedCount() > 0)), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT);
		batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
		Assertions.assertTrue(batchRecord.getLastProcessedCount() > 0);
		Assertions.assertNotNull(batchRecord.getLastStartedAt());
		Assertions.assertTrue(batchRecord.getLastStartedAt().isAfter(initialStartTime));
		Assertions.assertNotNull(batchRecord.getLastProcessed());

		// Waits until batch is finished.
		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			}
			catch (final BusinessException exception) {
				return null;
			}
		}, record -> (record != null) && (record.getLastFinishedAt() != null) && record.getLastFinishedAt().isAfter(record.getLastStartedAt())
				&& record.getLastFinishedAt().isAfter(initialFinishTime), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT);
		batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
		Assertions.assertNotNull(batchRecord.getLastStartedAt());
		Assertions.assertTrue(batchRecord.getLastStartedAt().isAfter(initialStartTime));
		Assertions.assertNotNull(batchRecord.getLastProcessed());
		Assertions.assertEquals(processedNow, batchRecord.getLastProcessedCount());
		Assertions.assertEquals(processedTotal, BatchTestService.processedAlways);
		Assertions.assertNotNull(batchRecord.getLastFinishedAt());
		Assertions.assertTrue(batchRecord.getLastFinishedAt().isAfter(initialFinishTime));
		Assertions.assertTrue(batchRecord.isFinished());

	}

	/**
	 * Tests a batch.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testBatchInTime() throws Exception {

		// Makes sure the batch is not started.
		final BatchExecutor<BatchObject> testBatchExecutor = BatchExecutor.withFixedRate(BatchObject.class, "testBatchInTime", 10L,
				Duration.ofMillis(100), Duration.ofMinutes(1), "batchTestService", null, null);
		testBatchExecutor.setCleansWithin(Duration.ofMinutes(15));
		final String batchKey = this.batchServiceComponent.getKey(testBatchExecutor.getKeySuffix());

		// Record should not exist.
		try {
			this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			Assertions.fail("Record should not exist.");
		}
		catch (final Exception exception) {
		}

		// Tests the batch twice.
		this.testBatch(testBatchExecutor, 100L, 100L);
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(1)));
		this.testBatch(testBatchExecutor, 100L, 200L);

		// Advances the clock and make sure the record is deleted.
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(6)));
		this.batchService.checkAll();
		TestHelper.waitUntilValid(() -> {
			try {
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException exception) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.REGULAR_WAIT * 3, TestHelper.SHORT_WAIT);
		try {
			this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false);
			Assertions.fail("Batch should no longer exist.");
		}
		catch (final Exception exception) {
		}

	}

	/**
	 * Tests a batch.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testBatchNotInTime() throws Exception {

		// Makes sure the batch is not started.
		final BatchExecutor<BatchObject> testBatchExecutor = BatchExecutor.withFixedRate(BatchObject.class, "testBatchNotInTime", 10L,
				Duration.ofMillis(100), Duration.ofMillis(500), "batchTestService", null, null);
		testBatchExecutor.setCleansWithin(Duration.ofMinutes(15));
		final String batchKey = this.batchServiceComponent.getKey(testBatchExecutor.getKeySuffix());

		// Record should not exist.
		try {
			this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			Assertions.fail("Record should not exist.");
		}
		catch (final Exception exception) {
		}

		// Runs the clock forward and executes the batch again (now with a bigger delay
		// so it should not finish in time).
		this.batchService.checkAll();
		this.batchService.start(testBatchExecutor, false, false);
		this.batchService.checkAll();

		// Waits for a while (this batch should not reach the end).
		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			}
			catch (final BusinessException exception) {
				return null;
			}
		}, record -> (record != null) && (record.getLastFinishedAt() != null), TestHelper.LONG_WAIT, TestHelper.VERY_SHORT_WAIT);
		final BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false)
				.getValue();
		Assertions.assertTrue(BatchTestService.processedAlways > 0);
		Assertions.assertTrue(BatchTestService.processedAlways < 100);
		Assertions.assertTrue(batchRecord.getLastProcessedCount() > 0);
		Assertions.assertTrue(batchRecord.getLastProcessedCount() < 100);
		Assertions.assertNull(batchRecord.getLastFinishedAt());
		Assertions.assertFalse(batchRecord.isFinished());

		// Advances the clock and make sure the record is deleted.
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(6)));
		this.batchService.checkAll();
		TestHelper.waitUntilValid(() -> {
			try {
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException exception) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT);
		try {
			this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false);
			Assertions.fail("Batch should no longer exist.");
		}
		catch (final Exception exception) {
		}

	}

	/**
	 * Tests a batch.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testBatchCancel() throws Exception {

		// Makes sure the batch is not started.
		final BatchExecutor<BatchObject> testBatchExecutor = BatchExecutor.withFixedRate(BatchObject.class, "testBatchCancel", 10L,
				Duration.ofMillis(100), Duration.ofMinutes(1), "batchTestService", null, null);
		testBatchExecutor.setCleansWithin(Duration.ofMinutes(15));
		final String batchKey = this.batchServiceComponent.getKey(testBatchExecutor.getKeySuffix());

		// Record should not exist.
		try {
			this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			Assertions.fail("Record should not exist.");
		}
		catch (final Exception exception) {
		}

		// Runs the clock forward and executes the batch again (now with a bigger delay
		// so it should not finish in time).
		this.batchService.checkAll();
		this.batchService.start(testBatchExecutor, false, false);
		this.batchService.checkAll();

		// Waits a bit and cancels.
		Thread.sleep(200);
		this.batchService.cancel(testBatchExecutor.getKeySuffix());

		// This batch should not reach the end.
		final BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false)
				.getValue();
		Assertions.assertTrue(BatchTestService.processedAlways > 0);
		Assertions.assertTrue(BatchTestService.processedAlways < 100);
		Assertions.assertTrue(batchRecord.getLastProcessedCount() > 0);
		Assertions.assertTrue(batchRecord.getLastProcessedCount() < 100);
		Assertions.assertNull(batchRecord.getLastFinishedAt());
		Assertions.assertFalse(batchRecord.isFinished());

		// Advances the clock and make sure the record is deleted.
		DateTimeHelper.setClock(Clock.offset(DateTimeHelper.getClock(), Duration.ofHours(6)));
		this.batchService.checkAll();
		TestHelper.waitUntilValid(() -> {
			try {
				return this.keyValueService.findByKeyStart("batch-record");
			}
			catch (final BusinessException exception) {
				return List.of();
			}
		}, List::isEmpty, TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT);
		try {
			this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false);
			Assertions.fail("Batch should no longer exist.");
		}
		catch (final Exception exception) {
		}

	}

	/**
	 * Verifies that a concurrent resume attempt on a running batch is dropped immediately (SKIP
	 * LOCKED) rather than blocking until the running transaction commits (WAIT_AND_LOCK). The test
	 * holds the batch execution open via a CountDownLatch, fires a concurrent resume from a
	 * background thread while the row lock is held, and asserts the background call completes in
	 * well under the hold duration.
	 */
	@Test
	public void testConcurrentResumeIsDroppedNotBlocked() throws Exception {
		final CountDownLatch batchExecuting = new CountDownLatch(1);
		final CountDownLatch batchCanProceed = new CountDownLatch(1);
		BatchTestService.executingSignal = batchExecuting;
		BatchTestService.holdLatch = batchCanProceed;

		try {
			final BatchExecutor<BatchObject> executor = BatchExecutor.withFixedRate(BatchObject.class, "testSkipLock", 10L, Duration.ZERO,
					Duration.ofMinutes(5), "batchTestService", null, null);
			this.batchService.start(executor, false, false);

			// Wait until the first item has started (row lock is now held by the JMS listener's transaction).
			Assertions.assertTrue(batchExecuting.await(10, TimeUnit.SECONDS), "Batch should start executing");

			// Call resume() from a background thread while the row is locked.
			final CompletableFuture<Long> concurrentResume = CompletableFuture.supplyAsync(() -> {
				final long start = System.currentTimeMillis();
				try {
					this.batchService.resume(executor.getKeySuffix());
				}
				catch (final Exception ignored) {
				}
				return System.currentTimeMillis() - start;
			});

			// Hold the batch locked for 1 second, then release.
			Thread.sleep(1000);
			batchCanProceed.countDown();

			// SKIP LOCKED: the concurrent resume should have completed in << 1 second.
			// WAIT_AND_LOCK: it would have blocked for ~1 second, failing this assertion.
			final long elapsed = concurrentResume.get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(elapsed < 500,
					"Concurrent resume took " + elapsed + "ms — expected < 500ms (SKIP LOCKED); a result near 1000ms means WAIT_AND_LOCK is in effect");
		}
		finally {
			BatchTestService.executingSignal = null;
			BatchTestService.holdLatch = null;
		}
	}

	/**
	 * A scheduled batch is started by every instance: a start while the batch is running (neither
	 * finished nor expired) does not change it. Here the second start waits for the row lock held by
	 * the running batch, and then finds it running: the batch keeps the first start's arguments.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void testStartKeepsRunningBatch() throws Exception {
		final CountDownLatch batchExecuting = new CountDownLatch(1);
		final CountDownLatch batchCanProceed = new CountDownLatch(1);
		BatchTestService.executingSignal = batchExecuting;
		BatchTestService.holdLatch = batchCanProceed;
		try {
			final BatchExecutor<BatchObject> firstStart = BatchExecutor.withFixedRate(BatchObject.class, "testStartKeepsRunningBatch", 10L,
					Duration.ofSeconds(1), Duration.ofMinutes(5), "batchTestService", null, Map.of("start", "first"));
			this.batchService.start(firstStart, false, false);
			Assertions.assertTrue(batchExecuting.await(10, TimeUnit.SECONDS), "Batch should start executing");

			// Another instance starts the same batch while it runs.
			final BatchExecutor<BatchObject> secondStart = BatchExecutor.withFixedRate(BatchObject.class, "testStartKeepsRunningBatch", 10L,
					Duration.ofSeconds(1), Duration.ofMinutes(5), "batchTestService", null, Map.of("start", "second"));
			final CompletableFuture<Void> concurrentStart = CompletableFuture.runAsync(() -> {
				try {
					this.batchService.start(secondStart, false, false);
				}
				catch (final BusinessException exception) {
					throw new IllegalStateException(exception);
				}
			});
			batchCanProceed.countDown();
			concurrentStart.get(10, TimeUnit.SECONDS);

			final BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService
					.findById(this.batchServiceComponent.getKey(firstStart.getKeySuffix()), LockBehavior.NO_LOCK, false).getValue();
			Assertions.assertFalse(batchRecord.isFinished());
			Assertions.assertEquals("first", batchRecord.getArguments().get("start"));
		}
		finally {
			BatchTestService.executingSignal = null;
			BatchTestService.holdLatch = null;
		}
	}

	/**
	 * The batch record of a key suffix.
	 *
	 * @param  keySuffix Key suffix.
	 * @return           The record, or null.
	 */
	@SuppressWarnings("unchecked")
	private BatchExecutor<BatchObject> batchRecord(
			final String keySuffix) {
		BatchExecutor<BatchObject> batchRecord = null;
		try {
			batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(this.batchServiceComponent.getKey(keySuffix), LockBehavior.NO_LOCK, false).getValue();
		}
		catch (final Exception exception) {
			batchRecord = null;
		}
		return batchRecord;
	}

	/**
	 * A GET returning {@link BatchPostponed} postpones the run: not finished, the record says
	 * postponed, START logged once; then items come and the run finishes normally.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testPostponedBatch() throws Exception {
		BatchTestService.postponeSteps = 10;
		final BatchExecutor<BatchObject> executor = BatchExecutor.withFixedRate(BatchObject.class, "testPostponedBatch", 10L, Duration.ofMillis(200),
				Duration.ofMinutes(5), "batchTestService",
				Map.of(BatchAction.START, "start", BatchAction.RESUME, "resume", BatchAction.GET, "getAfterPostponing", BatchAction.EXECUTE, "execute"), null);
		this.batchService.start(executor, false, false);
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.batchRecord("testPostponedBatch"),
				batchRecord -> (batchRecord != null) && batchRecord.getLastBatchPostponed() && (batchRecord.getPostponedCount() >= 2), TestHelper.REGULAR_WAIT * 2,
				TestHelper.VERY_SHORT_WAIT));
		final BatchExecutor<BatchObject> postponedRecord = this.batchRecord("testPostponedBatch");
		Assertions.assertFalse(postponedRecord.isFinished());
		Assertions.assertEquals(0L, postponedRecord.getLastProcessedCount());
		Assertions.assertNull(postponedRecord.getLastProcessed());
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.batchRecord("testPostponedBatch"), batchRecord -> (batchRecord != null) && batchRecord.isFinished(),
				TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		final BatchExecutor<BatchObject> finishedRecord = this.batchRecord("testPostponedBatch");
		Assertions.assertFalse(finishedRecord.getLastBatchPostponed());
		Assertions.assertEquals(0L, finishedRecord.getPostponedCount());
		Assertions.assertEquals(100L, finishedRecord.getLastProcessedCount());
		Assertions.assertEquals(10, BatchTestService.postponedSteps);
		Assertions.assertEquals(1, BatchTestService.startCount);
		Assertions.assertNull(finishedRecord.getLastBatchError());
	}

	/**
	 * A GET returning null, or a batch with no GET delegate, fails loudly: an ERROR names the batch
	 * and the run neither finishes, advances nor is postponed. (Like every batch error, it is not
	 * persisted on the record: the resume listener's transaction rolls back and the broker retries.)
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testNullOrMissingGetFails() throws Exception {
		final Logger batchLogger = (Logger) LoggerFactory.getLogger(BatchServiceComponent.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		batchLogger.addAppender(appender);
		final Map<String, Map<BatchAction, String>> delegatesByBatch = Map.of("testNullGet", Map.of(BatchAction.GET, "getNull", BatchAction.EXECUTE, "execute"),
				"testMissingGet", Map.of(BatchAction.EXECUTE, "execute"));
		try {
			for (final Map.Entry<String, Map<BatchAction, String>> delegates : delegatesByBatch.entrySet()) {
				this.batchService.start(BatchExecutor.withFixedRate(BatchObject.class, delegates.getKey(), 10L, Duration.ofMinutes(1), Duration.ofMinutes(5),
						"batchTestService", delegates.getValue(), null), false, false);
			}
			for (final String keySuffix : delegatesByBatch.keySet()) {
				final String expectedError = "Error processing batch '" + this.batchServiceComponent.getKey(keySuffix) + "': Batch '"
						+ this.batchServiceComponent.getKey(keySuffix) + "' GET returned null";
				Assertions.assertTrue(TestHelper.waitUntilValid(() -> List.copyOf(appender.list),
						events -> events.stream().anyMatch(event -> (event.getLevel() == Level.ERROR) && event.getFormattedMessage().startsWith(expectedError)),
						TestHelper.REGULAR_WAIT * 2, TestHelper.SHORT_WAIT));
				final BatchExecutor<BatchObject> failedRecord = this.batchRecord(keySuffix);
				Assertions.assertFalse(failedRecord.isFinished());
				Assertions.assertEquals(0L, failedRecord.getLastProcessedCount());
				Assertions.assertFalse(failedRecord.getLastBatchPostponed());
				this.batchService.cancel(keySuffix);
			}
		}
		finally {
			batchLogger.detachAppender(appender);
		}
	}

	/** Tests executing the batch within a specific time. */
	@Test
	@SuppressWarnings("unchecked")
	public void testBatchWithExpectedSize() throws Exception {

		// Starts the batch.
		final BatchExecutor<BatchObject> testBatchExecutor = BatchExecutor.withAdaptiveRate(BatchObject.class, "testBatchCancel", 10L,
				Duration.ofSeconds(20), Duration.ofMillis(100), "batchTestService", null, null);
		testBatchExecutor.setExpectedCount(100L);
		final String batchKey = this.batchServiceComponent.getKey(testBatchExecutor.getKeySuffix());
		this.batchService.start(testBatchExecutor, false, false);

		// Waits until batch is finished.
		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			}
			catch (final BusinessException exception) {
				return null;
			}
		}, record -> (record != null) && (record.getLastFinishedAt() != null),
				((Long) testBatchExecutor.getTryToFinishWithin().plusSeconds(1L).toMillis()).intValue(), TestHelper.VERY_SHORT_WAIT);
		final BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false)
				.getValue();
		Assertions.assertNotNull(batchRecord.getLastStartedAt());
		Assertions.assertNotNull(batchRecord.getLastProcessed());
		Assertions.assertNotNull(batchRecord.getLastFinishedAt());
		Assertions.assertTrue(batchRecord.isFinished());

		// Validates the batch was finished close enough to the target time (tryToFinishWithin minus
		// the default 10% safety margin). Tolerance is generous
		// (1 s) because the assertion runs against wall-clock differences on machines that may be
		// busy with parallel testcontainers — the original 150 ms bound was flaky in CI/release.
		final Long absoluteMillisDifferenceFromExpected = Math.abs(
				batchRecord.getLastStartedAt().until(batchRecord.getLastFinishedAt(), ChronoUnit.MILLIS) - ((testBatchExecutor.getTryToFinishWithin().toMillis() * 9L) / 10L));
		Assertions.assertTrue(absoluteMillisDifferenceFromExpected < 1000,
				"expected batch finish within 1000 ms of target, drifted by " + absoluteMillisDifferenceFromExpected + " ms");

	}

	/** Tests that a custom safety margin is persisted and drives the batch pacing. */
	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("custom 25% safety margin is persisted and the batch finishes ~15 s into a 20 s target")
	public void testBatchWithExpectedSizeAndCustomSafetyMargin() throws Exception {

		// Starts the batch aiming to finish 25% ahead of the 20 seconds target.
		final BatchExecutor<BatchObject> testBatchExecutor = BatchExecutor.withAdaptiveRate(BatchObject.class, "testBatchCustomSafetyMargin", 10L,
				Duration.ofSeconds(20), Duration.ofMillis(100), "batchTestService", null, null);
		testBatchExecutor.setExpectedCount(100L);
		testBatchExecutor.setSafetyMarginPercentage(BigDecimal.valueOf(25));
		final String batchKey = this.batchServiceComponent.getKey(testBatchExecutor.getKeySuffix());
		this.batchService.start(testBatchExecutor, false, false);

		// Waits until batch is finished.
		TestHelper.waitUntilValid(() -> {
			try {
				return (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false).getValue();
			}
			catch (final BusinessException exception) {
				return null;
			}
		}, record -> (record != null) && (record.getLastFinishedAt() != null),
				((Long) testBatchExecutor.getTryToFinishWithin().plusSeconds(1L).toMillis()).intValue(), TestHelper.VERY_SHORT_WAIT);
		final BatchExecutor<BatchObject> batchRecord = (BatchExecutor<BatchObject>) this.keyValueService.findById(batchKey, LockBehavior.NO_LOCK, false)
				.getValue();
		Assertions.assertTrue(batchRecord.isFinished());
		Assertions.assertEquals(0, BigDecimal.valueOf(25).compareTo(batchRecord.getSafetyMarginPercentage()));

		// Validates the batch was finished close enough to 15 seconds (20 seconds minus 25%).
		final Long absoluteMillisDifferenceFromExpected = Math
				.abs(batchRecord.getLastStartedAt().until(batchRecord.getLastFinishedAt(), ChronoUnit.MILLIS) - 15000L);
		Assertions.assertTrue(absoluteMillisDifferenceFromExpected < 1000,
				"expected batch finish within 1000 ms of target, drifted by " + absoluteMillisDifferenceFromExpected + " ms");

	}

}
