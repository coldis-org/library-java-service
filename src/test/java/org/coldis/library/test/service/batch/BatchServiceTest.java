package org.coldis.library.test.service.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValueService;
import org.coldis.library.service.batch.BatchExecutor;
import org.coldis.library.service.batch.BatchService;
import org.coldis.library.test.SpringTestHelper;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Batch record test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
public class BatchServiceTest extends SpringTestHelper {

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

	/**
	 * Regular clock.
	 */
	public static final Clock REGULAR_CLOCK = DateTimeHelper.getClock();

	/**
	 * Random.
	 */
	public static final Random RANDOM = new Random();

	/**
	 * Key/value service.
	 */
	@Autowired
	private KeyValueService keyValueService;

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
		this.batchService.start(testBatchExecutor, false);
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
		final BatchExecutor<BatchObject> testBatchExecutor = new BatchExecutor<>(BatchObject.class, "testBatchInTime", 10L, null, Duration.ofMillis(100),
				Duration.ofMinutes(1), Duration.ofMinutes(15), "batchTestService", null, null, null);
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
		final BatchExecutor<BatchObject> testBatchExecutor = new BatchExecutor<>(BatchObject.class, "testBatchNotInTime", 10L, null, Duration.ofMillis(100),
				Duration.ofMillis(500), Duration.ofMinutes(15), "batchTestService", null, null, null);
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
		this.batchService.start(testBatchExecutor, false);
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
		final BatchExecutor<BatchObject> testBatchExecutor = new BatchExecutor<>(BatchObject.class, "testBatchCancel", 10L, null, Duration.ofMillis(100),
				Duration.ofMinutes(1), Duration.ofMinutes(15), "batchTestService", null, null, null);
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
		this.batchService.start(testBatchExecutor, false);
		this.batchService.checkAll();

		// Waits a bit and cancels.
		Thread.sleep(700);
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

}
