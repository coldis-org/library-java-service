package org.coldis.library.test.service.jms;

import java.nio.file.Files;
import java.nio.file.Path;

import org.coldis.library.service.jms.JmsCpuLimits;
import org.coldis.library.service.jms.JmsThreadsEnvironmentPostProcessor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link JmsCpuLimits} (CPU source and limit resolution) and
 * {@link JmsThreadsEnvironmentPostProcessor}. The global pinned CPUs
 * are not changed here (tests run in parallel): fake cgroup trees and the
 * {@value JmsCpuLimits#CPUS_PROPERTY} property of a mock environment are.
 */
@DisplayName("JMS CPU limits and remoting threads")
public class JmsCpuLimitsTest {

	/**
	 * Fake cgroup tree.
	 */
	@TempDir
	private Path cgroupRoot;

	/**
	 * Tests the limit resolution order.
	 */
	@Test
	@DisplayName("Explicit absolute value, then explicit ratio, then default ratio, times the CPUs rounded up, at least 1; a ratio of 0 is no limit")
	public void testResolutionOrder() {
		Assertions.assertEquals(7, JmsCpuLimits.resolve(7, 2D, 3D, () -> 4D));
		Assertions.assertEquals(8, JmsCpuLimits.resolve(null, 2D, 3D, () -> 4D));
		Assertions.assertEquals(12, JmsCpuLimits.resolve(null, null, 3D, () -> 4D));
		Assertions.assertEquals(8, JmsCpuLimits.resolve(0, 2D, 3D, () -> 4D));
		Assertions.assertEquals(5, JmsCpuLimits.resolve(null, 2D, null, () -> 2.08D));
		Assertions.assertEquals(1, JmsCpuLimits.resolve(null, 0.1D, null, () -> 2D));
		Assertions.assertNull(JmsCpuLimits.resolve(null, 0D, 3D, () -> 4D));
		Assertions.assertNull(JmsCpuLimits.resolve(null, null, null, () -> 4D));
		Assertions.assertNull(JmsCpuLimits.resolve(-1, null, null, () -> 4D));
	}

	/**
	 * Tests the CPU detection order.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("CPUs: cgroup v2 cpu.max, then cgroup v1 quota, capped at the runtime's processors, else the runtime's processors")
	public void testCpuDetection() throws Exception {
		Assertions.assertEquals(16D, JmsCpuLimits.detectCpus(this.cgroupRoot, () -> 16));
		Files.createDirectories(this.cgroupRoot.resolve("cpu,cpuacct"));
		Files.writeString(this.cgroupRoot.resolve("cpu,cpuacct").resolve("cpu.cfs_quota_us"), "150000\n");
		Files.writeString(this.cgroupRoot.resolve("cpu,cpuacct").resolve("cpu.cfs_period_us"), "100000\n");
		Assertions.assertEquals(1.5D, JmsCpuLimits.detectCpus(this.cgroupRoot, () -> 16));
		Files.writeString(this.cgroupRoot.resolve("cpu.max"), "max 100000\n");
		Assertions.assertEquals(1.5D, JmsCpuLimits.detectCpus(this.cgroupRoot, () -> 16));
		Files.writeString(this.cgroupRoot.resolve("cpu.max"), "208000 100000\n");
		Assertions.assertEquals(2.08D, JmsCpuLimits.detectCpus(this.cgroupRoot, () -> 16), 0.0001D);
		Assertions.assertEquals(2D, JmsCpuLimits.detectCpus(this.cgroupRoot, () -> 2));
		Files.writeString(this.cgroupRoot.resolve("cpu.max"), "max 100000\n");
		Files.writeString(this.cgroupRoot.resolve("cpu,cpuacct").resolve("cpu.cfs_quota_us"), "-1\n");
		Assertions.assertEquals(16D, JmsCpuLimits.detectCpus(this.cgroupRoot, () -> 16));
	}

	/**
	 * Tests the remoting threads resolution.
	 */
	@Test
	@DisplayName("Remoting threads: an application's value wins, then a positive multiplier, then the legacy AVAILABLE_CPUS_30X, then 30 per CPU")
	public void testRemotingThreads() {
		final MockEnvironment pinned = new MockEnvironment().withProperty(JmsCpuLimits.CPUS_PROPERTY, "2.08");
		Assertions.assertEquals(63, JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(pinned));
		Assertions.assertEquals(60, JmsThreadsEnvironmentPostProcessor
				.resolveRemotingThreads(new MockEnvironment().withProperty(JmsCpuLimits.CPUS_PROPERTY, "2.08").withProperty("AVAILABLE_CPUS_30X", "60")));
		Assertions.assertEquals(9, JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(new MockEnvironment()
				.withProperty(JmsCpuLimits.CPUS_PROPERTY, "2.08").withProperty("AVAILABLE_CPUS_30X", "60")
				.withProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY, "4")));
		Assertions.assertEquals(63, JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(new MockEnvironment()
				.withProperty(JmsCpuLimits.CPUS_PROPERTY, "2.08").withProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY, "0")));
		final MockEnvironment explicit = new MockEnvironment().withProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY, "12");
		new JmsThreadsEnvironmentPostProcessor().postProcessEnvironment(explicit, null);
		Assertions.assertEquals("12", explicit.getProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY));
		// Without a pinned CPU count (the post-processor pins it process-wide, which parallel tests share).
		final MockEnvironment unpinned = new MockEnvironment();
		final int expectedThreads = JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(unpinned);
		new JmsThreadsEnvironmentPostProcessor().postProcessEnvironment(unpinned, null);
		Assertions.assertEquals(String.valueOf(expectedThreads), unpinned.getProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY));
	}

}
