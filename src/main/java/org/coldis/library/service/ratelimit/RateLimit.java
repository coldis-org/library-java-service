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
	 * Limit for the period.
	 */
	long limit();

}
