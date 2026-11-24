package org.coldis.library.test.service.cpu;

import java.nio.file.Path;

import org.coldis.library.service.cpu.CpuEnvironmentPostProcessor;
import org.coldis.library.thread.CpuLimits;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link CpuEnvironmentPostProcessor}: pins the CPU count from the property. Uses
 * its own {@link CpuLimits} instances (the process-wide one is never pinned
 * here).
 */
@DisplayName("CPU environment post-processor")
public class CpuEnvironmentPostProcessorTest {

	/**
	 * Empty cgroup tree (no quota).
	 */
	@TempDir
	private Path cgroupRoot;

	/**
	 * Tests the pinning.
	 */
	@Test
	@DisplayName("org.coldis.library.service.cpus pins the CPU count; without it (or not positive) nothing is pinned")
	public void testPin() {
		final CpuLimits pinned = new CpuLimits(this.cgroupRoot, () -> 16);
		new CpuEnvironmentPostProcessor(pinned).postProcessEnvironment(
				new MockEnvironment().withProperty(CpuEnvironmentPostProcessor.CPUS_PROPERTY, "2.5"), null);
		Assertions.assertEquals(2.5D, pinned.getCpus());
		Assertions.assertEquals(5, pinned.resolve(2D));
		final CpuLimits unpinned = new CpuLimits(this.cgroupRoot, () -> 16);
		new CpuEnvironmentPostProcessor(unpinned).postProcessEnvironment(new MockEnvironment(), null);
		new CpuEnvironmentPostProcessor(unpinned).postProcessEnvironment(
				new MockEnvironment().withProperty(CpuEnvironmentPostProcessor.CPUS_PROPERTY, "0"), null);
		Assertions.assertNull(unpinned.getPinnedCpus());
		Assertions.assertEquals(16D, unpinned.getCpus());
	}

}
