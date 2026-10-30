package org.coldis.library.test.service.batch;

import java.math.BigDecimal;
import java.time.Duration;

import org.coldis.library.service.batch.BatchExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Covers the safety margin applied by adaptive batch pacing. The expected targets are written out as
 * plain milliseconds, so a regression in the margin arithmetic cannot move the expectation with it.
 */
public class BatchExecutorUnitTest {

	/**
	 * Try to finish within used by the adaptive executors.
	 */
	private static final Duration TRY_TO_FINISH_WITHIN = Duration.ofSeconds(20);

	/**
	 * Creates an adaptive executor.
	 *
	 * @return Adaptive executor.
	 */
	private BatchExecutor<BatchObject> createAdaptiveExecutor() {
		return BatchExecutor.withAdaptiveRate(BatchObject.class, "batchExecutorUnitTest", 10L, BatchExecutorUnitTest.TRY_TO_FINISH_WITHIN,
				Duration.ofMillis(100), "batchTestService", null, null);
	}

	/**
	 * Sets a safety margin and returns the resulting target in milliseconds.
	 *
	 * @param  safetyMarginPercentage Safety margin percentage.
	 * @return                        Target finish within, in milliseconds.
	 */
	private long targetMillisForSafetyMargin(
			final BigDecimal safetyMarginPercentage) {
		final BatchExecutor<BatchObject> batchExecutor = this.createAdaptiveExecutor();
		batchExecutor.setSafetyMarginPercentage(safetyMarginPercentage);
		return batchExecutor.getTargetFinishWithin().toMillis();
	}

	/** Tests that a new adaptive executor aims to finish 10% ahead of its target. */
	@Test
	public void testDefaultSafetyMargin() {
		final BatchExecutor<BatchObject> batchExecutor = this.createAdaptiveExecutor();
		Assertions.assertEquals(0, BigDecimal.TEN.compareTo(batchExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(18000L, batchExecutor.getTargetFinishWithin().toMillis());
	}

	/** Tests custom safety margins, including disabling it. */
	@Test
	public void testCustomSafetyMargin() {
		Assertions.assertEquals(20000L, this.targetMillisForSafetyMargin(BigDecimal.ZERO));
		Assertions.assertEquals(10000L, this.targetMillisForSafetyMargin(BigDecimal.valueOf(50)));
		Assertions.assertEquals(17500L, this.targetMillisForSafetyMargin(new BigDecimal("12.5")));
		Assertions.assertEquals(18000L, this.targetMillisForSafetyMargin(null));
	}

	/** Tests that out-of-range safety margins are stored bounded to [0, 99]. */
	@Test
	public void testSafetyMarginBounds() {
		final BatchExecutor<BatchObject> negativeMarginExecutor = this.createAdaptiveExecutor();
		negativeMarginExecutor.setSafetyMarginPercentage(BigDecimal.valueOf(-5));
		Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(negativeMarginExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(20000L, negativeMarginExecutor.getTargetFinishWithin().toMillis());

		final BatchExecutor<BatchObject> excessiveMarginExecutor = this.createAdaptiveExecutor();
		excessiveMarginExecutor.setSafetyMarginPercentage(BigDecimal.valueOf(150));
		Assertions.assertEquals(0, BigDecimal.valueOf(99).compareTo(excessiveMarginExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(200L, excessiveMarginExecutor.getTargetFinishWithin().toMillis());
	}

	/** Tests that fixed-rate executors have no adaptive target. */
	@Test
	public void testFixedRateHasNoTarget() {
		final BatchExecutor<BatchObject> batchExecutor = BatchExecutor.withFixedRate(BatchObject.class, "batchExecutorUnitTest", 10L, Duration.ofMillis(100),
				Duration.ofSeconds(20), "batchTestService", null, null);
		Assertions.assertNull(batchExecutor.getTargetFinishWithin());
	}

	/** Tests that records persisted before the safety margin existed get the default when read. */
	@Test
	@SuppressWarnings("unchecked")
	public void testPersistedRecordWithoutSafetyMargin() throws Exception {
		final ObjectNode persistedRecord = BatchExecutor.OBJECT_MAPPER.valueToTree(this.createAdaptiveExecutor());
		persistedRecord.remove("safetyMarginPercentage");
		persistedRecord.remove("targetFinishWithin");
		final BatchExecutor<BatchObject> readExecutor = BatchExecutor.OBJECT_MAPPER.treeToValue(persistedRecord, BatchExecutor.class);
		Assertions.assertEquals(0, BigDecimal.TEN.compareTo(readExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(18000L, readExecutor.getTargetFinishWithin().toMillis());
	}

}
