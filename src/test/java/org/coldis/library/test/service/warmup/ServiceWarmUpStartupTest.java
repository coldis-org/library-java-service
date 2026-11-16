package org.coldis.library.test.service.warmup;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.coldis.library.service.ServiceConfiguration;
import org.coldis.library.service.warmup.ServiceWarmUp;
import org.coldis.library.service.warmup.ServiceWarmUpAutoConfiguration;
import org.coldis.library.service.warmup.ServiceWarmUpProperties;
import org.coldis.library.service.warmup.ServiceWarmUpResult;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponent;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponentTestSeam;
import org.coldis.library.service.warmup.ServiceWarmUpStopReason;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests the start-up path a service uses: the warm-up beans hold the health check from the start, run when the
 * application is ready and open it when they stop. No containers, and no transaction or cache configuration, which the
 * shared test contexts configure.
 */
@ExtendWith(OutputCaptureExtension.class)
public class ServiceWarmUpStartupTest {

	/**
	 * What the warm-up beans did.
	 */
	public static class StartupProbe {

		/**
		 * Calls of the quick warm-up.
		 */
		private final AtomicInteger quickCalls = new AtomicInteger();

		/**
		 * Released when the blocking warm-up's call starts.
		 */
		private final CountDownLatch blockingCallStarted = new CountDownLatch(1);

		/**
		 * Releases the blocking warm-up's call.
		 */
		private final CountDownLatch blockingCallReleased = new CountDownLatch(1);

		/**
		 * Gets the calls of the quick warm-up.
		 *
		 * @return The calls of the quick warm-up.
		 */
		public AtomicInteger getQuickCalls() {
			return this.quickCalls;
		}

		/**
		 * Gets the latch released when the blocking warm-up's call starts.
		 *
		 * @return The latch.
		 */
		public CountDownLatch getBlockingCallStarted() {
			return this.blockingCallStarted;
		}

		/**
		 * Gets the latch that releases the blocking warm-up's call.
		 *
		 * @return The latch.
		 */
		public CountDownLatch getBlockingCallReleased() {
			return this.blockingCallReleased;
		}

	}

	/**
	 * Two warm-up beans: one with a few quick calls, one whose call waits to be released.
	 */
	@Configuration
	public static class StartupWarmUps {

		/**
		 * Probe.
		 *
		 * @return The probe.
		 */
		@Bean
		public StartupProbe startupProbe() {
			return new StartupProbe();
		}

		/**
		 * Warm-up with three quick calls.
		 *
		 * @param  startupProbe Probe.
		 * @return              The warm-up.
		 */
		@Bean
		public ServiceWarmUp<Integer> quickWarmUp(
				final StartupProbe startupProbe) {
			return new ServiceWarmUpTestRoutine<>(() -> List.of(1, 2, 3), input -> startupProbe.getQuickCalls().incrementAndGet());
		}

		/**
		 * Warm-up whose call waits to be released.
		 *
		 * @param  startupProbe Probe.
		 * @return              The warm-up.
		 */
		@Bean
		public ServiceWarmUp<Integer> blockingWarmUp(
				final StartupProbe startupProbe) {
			return new ServiceWarmUpTestRoutine<>(() -> List.of(1), input -> {
				startupProbe.getBlockingCallStarted().countDown();
				startupProbe.getBlockingCallReleased().await(TestHelper.VERY_LONG_WAIT, TimeUnit.MILLISECONDS);
			});
		}

	}

	/**
	 * Context with the library defaults and the warm-up configuration, without warm-up beans.
	 */
	private final ApplicationContextRunner contextRunnerWithoutWarmUps = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
			.withUserConfiguration(ServiceConfiguration.class, ServiceWarmUpProperties.class, ServiceWarmUpServiceComponent.class, ServiceWarmUpAutoConfiguration.class);

	/**
	 * Context with the library defaults, the warm-up configuration and two warm-up beans.
	 */
	private final ApplicationContextRunner contextRunner = this.contextRunnerWithoutWarmUps.withUserConfiguration(StartupWarmUps.class);

	/**
	 * Publishes the event the application publishes when it is ready.
	 *
	 * @param context Context.
	 */
	private static void publishApplicationReady(
			final AssertableApplicationContext context) {
		context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
	}

	/**
	 * Tests that, with the warm-up on, the warm-up beans hold the health check before the application is ready, all run
	 * when it is ready, and open it when they stop.
	 */
	@Test
	@DisplayName("Warm-up beans hold the health check from the start, run when the application is ready and open it when they stop")
	public void testStartupWarmUps() {
		this.contextRunner.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent = context.getBean(ServiceWarmUpServiceComponent.class);
			final StartupProbe startupProbe = context.getBean(StartupProbe.class);
			Assertions.assertFalse(serviceWarmUpServiceComponent.isTrafficAllowed());
			ServiceWarmUpStartupTest.publishApplicationReady(context);
			try {
				Assertions.assertTrue(startupProbe.getBlockingCallStarted().await(TestHelper.VERY_LONG_WAIT, TimeUnit.MILLISECONDS));
				Assertions.assertFalse(serviceWarmUpServiceComponent.isTrafficAllowed());
			}
			finally {
				startupProbe.getBlockingCallReleased().countDown();
			}
			Assertions.assertTrue(TestHelper.waitUntilValid(serviceWarmUpServiceComponent::isTrafficAllowed, Boolean::booleanValue, TestHelper.VERY_LONG_WAIT,
					TestHelper.SHORT_WAIT));
			Assertions.assertEquals(3, startupProbe.getQuickCalls().get());
		});
	}

	/**
	 * Tests that, with the warm-up off, declared warm-up beans hold nothing, do not run, and get a warning.
	 *
	 * @param output Captured output.
	 */
	@Test
	@DisplayName("With the warm-up off, declared warm-up beans hold nothing, do not run and get a warning")
	public void testStartupWarmUpsOff(
			final CapturedOutput output) {
		this.contextRunner.run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(0, context.getBeanNamesForType(ServiceWarmUpServiceComponent.class).length);
			ServiceWarmUpStartupTest.publishApplicationReady(context);
			// The warm-ups would run on their own thread: had anything started them, the blocking one would start within
			// the wait, and wait to be released.
			Assertions.assertFalse(context.getBean(StartupProbe.class).getBlockingCallStarted().await(TestHelper.SHORT_WAIT, TimeUnit.MILLISECONDS));
			Assertions.assertEquals(0, context.getBean(StartupProbe.class).getQuickCalls().get());
			Assertions.assertTrue(output.getAll().contains("which do not run"), output.getAll());
		});
	}

	/**
	 * Tests that, with the warm-up on and no warm-up beans, the health check is held until the application is ready, and
	 * opens then.
	 */
	@Test
	@DisplayName("Without warm-up beans, the health check opens when the application is ready")
	public void testNoWarmUpBeans() {
		this.contextRunnerWithoutWarmUps.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent = context.getBean(ServiceWarmUpServiceComponent.class);
			Assertions.assertFalse(serviceWarmUpServiceComponent.isTrafficAllowed());
			ServiceWarmUpStartupTest.publishApplicationReady(context);
			Assertions.assertTrue(serviceWarmUpServiceComponent.isTrafficAllowed());
		});
	}

	/**
	 * Tests that a warm-up thread that cannot start, even on an error the JVM cannot recover from, opens the health
	 * check, and that the error is not swallowed.
	 */
	@Test
	@DisplayName("A warm-up thread that cannot start opens the health check")
	public void testThreadStartErrorOpensHealthCheck() {
		this.contextRunner.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent = context.getBean(ServiceWarmUpServiceComponent.class);
			Assertions.assertFalse(serviceWarmUpServiceComponent.isTrafficAllowed());
			Assertions.assertThrows(OutOfMemoryError.class, () -> ServiceWarmUpServiceComponentTestSeam.startStartupWarmUps(serviceWarmUpServiceComponent, runnable -> {
				throw new OutOfMemoryError("Test: unable to create native thread.");
			}));
			Assertions.assertTrue(serviceWarmUpServiceComponent.isTrafficAllowed());
			Assertions.assertEquals(0, context.getBean(StartupProbe.class).getQuickCalls().get());
		});
	}

	/**
	 * Tests that a service stopping during a warm-up stops it as interrupted, even when its calls swallow the
	 * interruption, and opens the health check.
	 */
	@Test
	@DisplayName("A service stopping during a warm-up stops it as interrupted and opens the health check")
	public void testStopDuringWarmUp() {
		this.contextRunnerWithoutWarmUps.withPropertyValues(ServiceWarmUpProperties.ENABLED_PROPERTY + "=true").run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent = context.getBean(ServiceWarmUpServiceComponent.class);
			ServiceWarmUpStartupTest.publishApplicationReady(context);
			final ServiceWarmUpProperties properties = ServiceWarmUpServiceComponentTestSeam.copy(context.getBean(ServiceWarmUpProperties.class));
			properties.setParallelism(1);
			properties.setTimeLimit(Duration.ofSeconds(60));
			properties.setIdleCompilationMillisPerSecond(0L);
			final CountDownLatch callStarted = new CountDownLatch(1);
			final CompletableFuture<List<ServiceWarmUpResult>> warmUp = CompletableFuture.supplyAsync(() -> ServiceWarmUpServiceComponentTestSeam.warmUp(
					serviceWarmUpServiceComponent, List.of(new ServiceWarmUpTestRoutine<>(() -> IntStream.range(0, 1_000_000).boxed().toList(), input -> {
						callStarted.countDown();
						Thread.sleep(1);
					})), properties));
			Assertions.assertTrue(callStarted.await(TestHelper.VERY_LONG_WAIT, TimeUnit.MILLISECONDS));
			Assertions.assertFalse(serviceWarmUpServiceComponent.isTrafficAllowed());
			serviceWarmUpServiceComponent.destroy();
			final List<ServiceWarmUpResult> results = warmUp.get(TestHelper.VERY_LONG_WAIT, TimeUnit.MILLISECONDS);
			Assertions.assertEquals(ServiceWarmUpStopReason.INTERRUPTED, results.get(0).getStopReason());
			Assertions.assertTrue(serviceWarmUpServiceComponent.isTrafficAllowed());
		});
	}

}
