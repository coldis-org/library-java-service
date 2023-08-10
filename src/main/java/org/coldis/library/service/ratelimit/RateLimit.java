package org.coldis.library.service.ratelimit;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Rate limit.
 */
@Documented
@Retention(RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER })
public @interface RateLimit {

	/**
	 * Name for the limit.
	 */
	String name() default "";

	/**
	 * Period (in seconds) for the limit.
	 */
	int period() default 60;

	/**
	 * Backoff period is the period by default.
	 */
	int backoffPeriod() default -1;

	/**
	 * Limit for the period.
	 */
	long limit();

	/**
	 * If the limit is local to the JVM or centralized.
	 */
	boolean local() default true;

	/**
	 * Relative error margin. Only applies to central limit.
	 */
	long errorMargin() default 10;

	/**
	 * If the exception type should be changed.
	 */
	Class<? extends Exception> errorType() default RateLimitException.class;

	/**
	 * Error messages to be used randomly.
	 */
	String[] randomErrorMessages() default {};

}
