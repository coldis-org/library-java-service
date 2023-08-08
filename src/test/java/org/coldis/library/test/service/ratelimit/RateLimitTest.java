package org.coldis.library.test.service.ratelimit;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import org.coldis.library.service.ratelimit.RateLimit;
import org.coldis.library.service.ratelimit.RateLimitInterceptor;
import org.coldis.library.service.ratelimit.RateLimitKey;
import org.coldis.library.service.ratelimit.RateLimitStats;
import org.coldis.library.service.ratelimit.RateLimits;
import org.coldis.library.test.service.TestApplication;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Rate limit test.
 */
@SpringBootTest(
		webEnvironment = WebEnvironment.RANDOM_PORT,
		classes = TestApplication.class
)
public class RateLimitTest {

	/**
	 * Rate limit 1.
	 */
	@RateLimit(
			limit = 100,
			period = 5
	)
	private void localRateLimit1() {
	}

	/**
	 * Rate limit 2.
	 */
	@RateLimits(
			limits = { @RateLimit(
					limit = 100,
					period = 5
			), @RateLimit(limit = 200,
					period = 15
			) }
	)
	private void localRateLimit2() {
	}

	/**
	 * Rate limit 1.
	 */
	@RateLimit(
			limit = 100,
			period = 5
	)
	private void localRateLimitWithKey1(
			@RateLimitKey
			final String test) {
	}

	/**
	 * Rate limit 2.
	 */
	@RateLimits(
			limits = { @RateLimit(
					limit = 100,
					period = 5
			), @RateLimit(limit = 200,
					period = 15
			) }
	)
	private void localRateLimitWithKey2(
			@RateLimitKey
			final String test,
			final String arg) {
	}

	/**
	 * Tests the lock.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testLocalRateLimit() throws Exception {
		// Runs 100 calls for the limited methods.
		for (Integer count = 1; count <= 100; count++) {
			this.localRateLimit1();
			this.localRateLimit2();
			System.out.println(count);
			System.out.println(RateLimitInterceptor.EXECUTIONS.entrySet().stream()
					.map(entry -> entry.getValue().getOrDefault("", new RateLimitStats()).getExecutions().size()).collect(Collectors.toList()));
		}

		// The next call should pass the limits.
		Assertions.assertThrows(Exception.class, () -> this.localRateLimit1());
		Assertions.assertThrows(Exception.class, () -> this.localRateLimit2());

		// Waits the period and try again.
		Thread.sleep(5 * 1000);
		for (Integer count = 1; count <= 100; count++) {
			this.localRateLimit1();
			this.localRateLimit2();
		}

		// The next call should pass the limits.
		Assertions.assertThrows(Exception.class, () -> this.localRateLimit1());

		// Waits the period and try again.
		Thread.sleep(5 * 1000);
		for (Integer count = 1; count <= 100; count++) {
			this.localRateLimit1();
			Assertions.assertThrows(Exception.class, () -> this.localRateLimit2());
		}

		// Waits the period and try again.
		Thread.sleep(5 * 1000);
		for (Integer count = 1; count <= 100; count++) {
			this.localRateLimit1();
			this.localRateLimit2();
		}

	}

	/**
	 * Tests the lock.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testLocalRateLimitWithKey() throws Exception {
		final Random random = new Random();
		final List<String> keys = List.of("key1", "key2");

		// Runs 100 calls for the limited methods.
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.localRateLimitWithKey1(key);
				this.localRateLimitWithKey2(key, Objects.toString(random.nextInt()));
				System.out.println(count);
				System.out.println(RateLimitInterceptor.EXECUTIONS.entrySet().stream()
						.map(entry -> entry.getValue().getOrDefault("", new RateLimitStats()).getExecutions().size()).collect(Collectors.toList()));
			}
		}

		// The next call should pass the limits.
		for (final String key : keys) {
			Assertions.assertThrows(Exception.class, () -> this.localRateLimitWithKey1(key));
			Assertions.assertThrows(Exception.class, () -> this.localRateLimitWithKey2(key, Objects.toString(random.nextInt())));
		}

		// Waits the period and try again.
		Thread.sleep(5 * 1000);
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.localRateLimitWithKey1(key);
				this.localRateLimitWithKey2(key, Objects.toString(random.nextInt()));
			}
		}

		// The next call should pass the limits.
		for (final String key : keys) {
			Assertions.assertThrows(Exception.class, () -> this.localRateLimitWithKey1(key));
		}

		// Waits the period and try again.
		Thread.sleep(5 * 1000);
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.localRateLimitWithKey1(key);
				Assertions.assertThrows(Exception.class, () -> this.localRateLimitWithKey2(key, Objects.toString(random.nextInt())));
			}
		}

		// Waits the period and try again.
		Thread.sleep(5 * 1000);
		for (final String key : keys) {
			for (Integer count = 1; count <= 100; count++) {
				this.localRateLimitWithKey1(key);
				this.localRateLimitWithKey2(key, Objects.toString(random.nextInt()));
			}
		}

	}

}
