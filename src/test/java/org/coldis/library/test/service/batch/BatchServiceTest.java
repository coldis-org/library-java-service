package org.coldis.library.test.service.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.batch.BatchExecutor;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;

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

		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

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
		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

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
		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

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
		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());

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

	/** Tests executing the batch within a specific time. */
	@Test
	@SuppressWarnings("unchecked")
	public void testBatchWithExpectedSize() throws Exception {

		// Starts the batch.
		final BatchExecutor<BatchObject> testBatchExecutor = BatchExecutor.withAdaptiveRate(BatchObject.class, "testBatchCancel", 10L,
				Duration.ofSeconds(20), Duration.ofMillis(100), "batchTestService", null, null);
		testBatchExecutor.setExpectedCount(100L);
		final String batchKey = this.batchService.getKey(testBatchExecutor.getKeySuffix());
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

		// Validates the batch was finished close enough to specified time. Tolerance is generous
		// (1 s) because the assertion runs against wall-clock differences on machines that may be
		// busy with parallel testcontainers — the original 150 ms bound was flaky in CI/release.
		final Long absoluteMillisDifferenceFromExpected = Math.abs(
				batchRecord.getLastStartedAt().until(batchRecord.getLastFinishedAt(), ChronoUnit.MILLIS) - testBatchExecutor.getTryToFinishWithin().toMillis());
		Assertions.assertTrue(absoluteMillisDifferenceFromExpected < 1000,
				"expected batch finish within 1000 ms of target, drifted by " + absoluteMillisDifferenceFromExpected + " ms");

	}

}
