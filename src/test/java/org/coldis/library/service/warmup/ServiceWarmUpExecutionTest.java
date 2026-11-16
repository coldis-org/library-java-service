package org.coldis.library.service.warmup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the median of call durations a warm-up reports.
 */
public class ServiceWarmUpExecutionTest {

	/**
	 * Tests that the median of an odd count of durations is the middle one, and of an even count the average of the two
	 * middle ones, whatever their order.
	 */
	@Test
	@DisplayName("The median is the middle duration, or the average of the two middle ones for an even count")
	public void testMedian() {
		Assertions.assertEquals(2.0, ServiceWarmUpExecution.getMedianMillis(new long[] { 3_000_000L, 1_000_000L, 2_000_000L }, 3));
		Assertions.assertEquals(2.5, ServiceWarmUpExecution.getMedianMillis(new long[] { 4_000_000L, 1_000_000L, 3_000_000L, 2_000_000L }, 4));
		// Only the first durations of the array count.
		Assertions.assertEquals(1.5, ServiceWarmUpExecution.getMedianMillis(new long[] { 2_000_000L, 1_000_000L, 9_000_000L }, 2));
		Assertions.assertNull(ServiceWarmUpExecution.getMedianMillis(new long[3], 0));
	}

}
