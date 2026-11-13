package org.coldis.library.test.service.warmup;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.coldis.library.service.ServiceConfiguration;
import org.coldis.library.service.warmup.ServiceWarmUp;
import org.coldis.library.service.warmup.ServiceWarmUpAutoConfiguration;
import org.coldis.library.service.warmup.ServiceWarmUpProperties;
import org.coldis.library.service.warmup.ServiceWarmUpServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests the start-up path a service uses: the warm-up beans hold the health check from the start, run when the
 * application is ready and open it when they stop. No containers, and no transaction or cache configuration, which the
 * shared test contexts configure.
 */
public class ServiceWarmUpStartupTest {

	/**
	 * Longest a test waits.
	 */
	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

	/**
	 * Interval between checks while waiting.
	 */
	private static final long WAIT_INTERVAL_MILLIS = 20L;

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
				startupProbe.getBlockingCallReleased().await(ServiceWarmUpStartupTest.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			});
		}

	}

	/**
	 * Context with the library defaults, the warm-up configuration and two warm-up beans.
	 */
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class)).withUserConfiguration(ServiceConfiguration.class,
					ServiceWarmUpProperties.class, ServiceWarmUpServiceComponent.class, ServiceWarmUpAutoConfiguration.class, StartupWarmUps.class);

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
	 * Waits until the health check may pass.
	 *
	 * @param  serviceWarmUpServiceComponent Warm-up service.
	 * @return                               Whether the health check may pass before the timeout.
	 * @throws InterruptedException          If the wait is interrupted.
	 */
	private static boolean awaitTrafficAllowed(
			final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent) throws InterruptedException {
		final long deadlineNanos = System.nanoTime() + ServiceWarmUpStartupTest.TEST_TIMEOUT.toNanos();
		boolean isTrafficAllowed = serviceWarmUpServiceComponent.isTrafficAllowed();
		while (!isTrafficAllowed && (System.nanoTime() < deadlineNanos)) {
			Thread.sleep(ServiceWarmUpStartupTest.WAIT_INTERVAL_MILLIS);
			isTrafficAllowed = serviceWarmUpServiceComponent.isTrafficAllowed();
		}
		return isTrafficAllowed;
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
				Assertions.assertTrue(startupProbe.getBlockingCallStarted().await(ServiceWarmUpStartupTest.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
				Assertions.assertFalse(serviceWarmUpServiceComponent.isTrafficAllowed());
			}
			finally {
				startupProbe.getBlockingCallReleased().countDown();
			}
			Assertions.assertTrue(ServiceWarmUpStartupTest.awaitTrafficAllowed(serviceWarmUpServiceComponent));
			Assertions.assertEquals(3, startupProbe.getQuickCalls().get());
		});
	}

	/**
	 * Tests that, with the warm-up off, declared warm-up beans hold nothing, do not run, and get a warning.
	 */
	@Test
	@DisplayName("With the warm-up off, declared warm-up beans hold nothing, do not run and get a warning")
	public void testStartupWarmUpsOff() {
		this.contextRunner.run(context -> {
			Assertions.assertNull(context.getStartupFailure());
			Assertions.assertEquals(0, context.getBeanNamesForType(ServiceWarmUpServiceComponent.class).length);
			Assertions.assertEquals(1, context.getBeanNamesForType(ServiceWarmUpAutoConfiguration.ServiceWarmUpDisabledWarning.class).length);
			ServiceWarmUpStartupTest.publishApplicationReady(context);
			Assertions.assertEquals(0, context.getBean(StartupProbe.class).getQuickCalls().get());
		});
	}

}
