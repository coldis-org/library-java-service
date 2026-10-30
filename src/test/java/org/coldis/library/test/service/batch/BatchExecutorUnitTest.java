package org.coldis.library.test.service.batch;

import java.math.BigDecimal;
import java.time.Duration;

import org.coldis.library.service.batch.BatchExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Covers the safety margin applied by adaptive batch pacing. The expected targets are written out as
 * plain milliseconds, so a regression in the margin arithmetic cannot move the expectation with it.
 */
@DisplayName("BatchExecutor — adaptive pacing safety margin")
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
	@DisplayName("new adaptive executor targets 90% of tryToFinishWithin")
	public void testDefaultSafetyMargin() {
		final BatchExecutor<BatchObject> batchExecutor = this.createAdaptiveExecutor();
		Assertions.assertEquals(0, BigDecimal.TEN.compareTo(batchExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(18000L, batchExecutor.getTargetFinishWithin().toMillis());
	}

	/** Tests custom safety margins, including disabling it. */
	@Test
	@DisplayName("custom margins shorten the target accordingly; null restores the default")
	public void testCustomSafetyMargin() {
		Assertions.assertEquals(20000L, this.targetMillisForSafetyMargin(BigDecimal.ZERO));
		Assertions.assertEquals(10000L, this.targetMillisForSafetyMargin(BigDecimal.valueOf(50)));
		Assertions.assertEquals(17500L, this.targetMillisForSafetyMargin(new BigDecimal("12.5")));
		Assertions.assertEquals(18000L, this.targetMillisForSafetyMargin(null));
	}

	/** Tests that out-of-range safety margins are stored bounded to [0, 99]. */
	@Test
	@DisplayName("out-of-range margins are stored bounded to [0, 99]")
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

	/** Tests that safety margins keep at most 4 decimal places, whatever the input scale. */
	@Test
	@DisplayName("margins keep at most 4 decimal places; extreme scales are bounded without exact arithmetic")
	public void testSafetyMarginScale() {
		final BatchExecutor<BatchObject> roundedMarginExecutor = this.createAdaptiveExecutor();
		roundedMarginExecutor.setSafetyMarginPercentage(new BigDecimal("12.345678"));
		Assertions.assertEquals(new BigDecimal("12.3457"), roundedMarginExecutor.getSafetyMarginPercentage());

		final BatchExecutor<BatchObject> tinyMarginExecutor = this.createAdaptiveExecutor();
		tinyMarginExecutor.setSafetyMarginPercentage(new BigDecimal("1E-999999999"));
		Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(tinyMarginExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(20000L, tinyMarginExecutor.getTargetFinishWithin().toMillis());

		final BatchExecutor<BatchObject> hugeMarginExecutor = this.createAdaptiveExecutor();
		hugeMarginExecutor.setSafetyMarginPercentage(new BigDecimal("1E+999999999"));
		Assertions.assertEquals(0, BigDecimal.valueOf(99).compareTo(hugeMarginExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(200L, hugeMarginExecutor.getTargetFinishWithin().toMillis());
	}

	/** Tests that fixed-rate executors have no adaptive target. */
	@Test
	@DisplayName("fixed-rate executors have no adaptive target")
	public void testFixedRateHasNoTarget() {
		final BatchExecutor<BatchObject> batchExecutor = BatchExecutor.withFixedRate(BatchObject.class, "batchExecutorUnitTest", 10L, Duration.ofMillis(100),
				Duration.ofSeconds(20), "batchTestService", null, null);
		Assertions.assertNull(batchExecutor.getTargetFinishWithin());
	}

	/** Tests that records persisted before the safety margin existed get the default when read. */
	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("JSON written without the margin reads back with the default (BatchExecutor.OBJECT_MAPPER)")
	public void testPersistedRecordWithoutSafetyMargin() throws Exception {
		final ObjectNode persistedRecord = BatchExecutor.OBJECT_MAPPER.valueToTree(this.createAdaptiveExecutor());
		persistedRecord.remove("safetyMarginPercentage");
		persistedRecord.remove("targetFinishWithin");
		final BatchExecutor<BatchObject> readExecutor = BatchExecutor.OBJECT_MAPPER.treeToValue(persistedRecord, BatchExecutor.class);
		Assertions.assertEquals(0, BigDecimal.TEN.compareTo(readExecutor.getSafetyMarginPercentage()));
		Assertions.assertEquals(18000L, readExecutor.getTargetFinishWithin().toMillis());
	}

}
