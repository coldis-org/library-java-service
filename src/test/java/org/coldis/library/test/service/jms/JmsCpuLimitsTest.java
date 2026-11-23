package org.coldis.library.test.service.jms;

import org.coldis.library.service.jms.JmsCpuLimits;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JmsCpuLimits}: the resolution order of a limit. The global CPU source
 * is not changed here (tests run in parallel); the overload with a given CPU
 * source is.
 */
@DisplayName("JMS CPU limits")
public class JmsCpuLimitsTest {

	/**
	 * Tests the resolution order.
	 */
	@Test
	@DisplayName("Explicit absolute value, then explicit ratio, then default ratio, times the CPUs rounded up")
	public void testResolutionOrder() {
		Assertions.assertEquals(7, JmsCpuLimits.resolve(7, 2D, 3D, () -> 4));
		Assertions.assertEquals(8, JmsCpuLimits.resolve(null, 2D, 3D, () -> 4));
		Assertions.assertEquals(12, JmsCpuLimits.resolve(null, null, 3D, () -> 4));
		Assertions.assertEquals(8, JmsCpuLimits.resolve(0, 2D, 3D, () -> 4));
		Assertions.assertEquals(2, JmsCpuLimits.resolve(null, 0.3D, null, () -> 4));
		Assertions.assertEquals(1, JmsCpuLimits.resolve(null, 0.5D, null, () -> 0));
		Assertions.assertNull(JmsCpuLimits.resolve(null, null, null, () -> 4));
		Assertions.assertNull(JmsCpuLimits.resolve(-1, null, null, () -> 4));
		Assertions.assertNull(JmsCpuLimits.resolve(null, 0D, null, () -> 4));
	}

	/**
	 * Tests the default CPU source.
	 */
	@Test
	@DisplayName("The default CPU source is the runtime's available processors")
	public void testDefaultCpuSource() {
		Assertions.assertEquals(Runtime.getRuntime().availableProcessors(), JmsCpuLimits.getAvailableCpus());
		Assertions.assertEquals(Runtime.getRuntime().availableProcessors() * 2, JmsCpuLimits.resolve(null, 2D, null));
	}

}
