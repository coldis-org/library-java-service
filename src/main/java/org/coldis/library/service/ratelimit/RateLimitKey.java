package org.coldis.library.service.ratelimit;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Rate limit key.
 */
@Documented
@Retention(RUNTIME)
@Target({ ElementType.PARAMETER })
public @interface RateLimitKey {

}
