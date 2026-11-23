package org.coldis.library.test.service.jms;

import java.nio.file.Path;

import org.coldis.library.service.cpu.CpuEnvironmentPostProcessor;
import org.coldis.library.service.jms.JmsThreadsEnvironmentPostProcessor;
import org.coldis.library.thread.CpuLimits;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link JmsThreadsEnvironmentPostProcessor}: the Artemis remoting threads. Uses
 * its own {@link CpuLimits} instances pinned to 2.08 CPUs.
 */
@DisplayName("JMS threads environment post-processor — remoting threads")
public class JmsThreadsEnvironmentPostProcessorTest {

	/**
	 * Empty cgroup tree.
	 */
	@TempDir
	private Path cgroupRoot;

	/**
	 * Creates CPU limits pinned to 2.08 CPUs.
	 *
	 * @return The CPU limits.
	 */
	private CpuLimits cpuLimits() {
		final CpuLimits cpuLimits = new CpuLimits(this.cgroupRoot, () -> 16);
		cpuLimits.pin(2.08D);
		return cpuLimits;
	}

	/**
	 * Tests the resolution order.
	 */
	@Test
	@DisplayName("Remoting threads: a positive multiplier, then the legacy AVAILABLE_CPUS_30X, then 30 per CPU; an application's value is kept")
	public void testRemotingThreads() {
		Assertions.assertEquals(63, JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(new MockEnvironment(), this.cpuLimits()));
		Assertions.assertEquals(60, JmsThreadsEnvironmentPostProcessor
				.resolveRemotingThreads(new MockEnvironment().withProperty("AVAILABLE_CPUS_30X", "60"), this.cpuLimits()));
		Assertions.assertEquals(9, JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(new MockEnvironment().withProperty("AVAILABLE_CPUS_30X", "60")
				.withProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY, "4"), this.cpuLimits()));
		Assertions.assertEquals(63, JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(
				new MockEnvironment().withProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_CPU_MULTIPLIER_PROPERTY, "0"), this.cpuLimits()));
		final MockEnvironment explicit = new MockEnvironment().withProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY, "12");
		new JmsThreadsEnvironmentPostProcessor().postProcessEnvironment(explicit, null);
		Assertions.assertEquals("12", explicit.getProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY));
		final MockEnvironment computed = new MockEnvironment();
		new JmsThreadsEnvironmentPostProcessor().postProcessEnvironment(computed, null);
		Assertions.assertEquals(String.valueOf(JmsThreadsEnvironmentPostProcessor.resolveRemotingThreads(computed, CpuLimits.getDefault())),
				computed.getProperty(JmsThreadsEnvironmentPostProcessor.REMOTING_THREADS_PROPERTY));
		Assertions.assertTrue(new JmsThreadsEnvironmentPostProcessor().getOrder() > new CpuEnvironmentPostProcessor().getOrder());
	}

}
