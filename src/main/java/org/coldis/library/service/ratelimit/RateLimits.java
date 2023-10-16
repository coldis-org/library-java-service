package org.coldis.library.service.ratelimit;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Rate limits.
 */
@Documented
@Retention(RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER })
public @interface RateLimits {

	/**
	 * Rate limit.
	 */
	RateLimit[] limits() default {};

}
