package org.coldis.library.service.ratelimit;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.SimpleMessage;

/**
 * Rate limit interceptor.
 */
@Aspect
public class RateLimitInterceptor {

	/**
	 * Local executions.
	 */
	public static Map<String, RateLimitStats> EXECUTIONS = new HashMap<>();

	/**
	 * Method point-cut.
	 */
	@Pointcut("execution(* *(..))")
	public void methodPointcut() {
	}

	/**
	 * Rate limit method point-cut.
	 */
	@Pointcut("@annotation(org.coldis.library.service.ratelimit.RateLimit)")
	public void rateLimitMethodPointcut() {
	}

	/**
	 * Rate limit method point-cut.
	 */
	@Pointcut("@annotation(org.coldis.library.service.ratelimit.RateLimits)")
	public void rateLimitsMethodPointcut() {
	}

	/**
	 * Checks the local limit.
	 *
	 * @param  limit             Limit.
	 * @param  limitName         Limit name.
	 * @throws BusinessException If the limit was exceeded.
	 */
	private void checkLocalLimit(
			final RateLimit limit,
			final String limitName) throws BusinessException {
		// Gets the execution for the method,
		RateLimitStats executions = null;
		synchronized (RateLimitInterceptor.EXECUTIONS) {
			executions = RateLimitInterceptor.EXECUTIONS.get(limitName);
			if (executions == null) {
				executions = new RateLimitStats(Duration.ofSeconds(limit.period()));
				RateLimitInterceptor.EXECUTIONS.put(limitName, executions);
			}
		}
		// Adds the execution and check if the limit has been reached.
		synchronized (executions) {
			final Integer executionsCount = executions.getExecutions().size();
			if ((executionsCount + 1) > limit.limit()) {
				throw new BusinessException(new SimpleMessage("service.ratelimit.exceeded",
						"Limit (" + limit.limit() + ") has been reached for method '" + limitName + "': " + executionsCount,
						new Object[] { limit.limit(), executions.getExecutions().size() }));
			}
			executions.getExecutions().add(DateTimeHelper.getCurrentLocalDateTime());
		}
	}

	/**
	 * Checks the rate limit for a method.
	 *
	 * @param  proceedingJoinPoint Join point.
	 * @throws Throwable           If the method cannot be executed.
	 */
	@Around(
			value = "methodPointcut() && (rateLimitMethodPointcut() || rateLimitsMethodPointcut()) && target(targetObject)",
			argNames = "targetObject"

	)
	public Object checkRateLimit(
			final ProceedingJoinPoint proceedingJoinPoint,
			final Object targetObject) throws Throwable {
		// If it is really a method.
		final MethodSignature methodSignature = (MethodSignature) proceedingJoinPoint.getSignature();
		if ((methodSignature != null) && (methodSignature instanceof MethodSignature)) {
			final Method method = methodSignature.getMethod();

			// Gets the limits.
			final List<RateLimit> limits = new ArrayList<>();
			final RateLimits rateLimitsAnnotation = method.getAnnotation(RateLimits.class);
			if (rateLimitsAnnotation != null) {
				limits.addAll(Arrays.asList(rateLimitsAnnotation.limits()));
			}
			final RateLimit rateLimitAnnotation = method.getAnnotation(RateLimit.class);
			if (rateLimitAnnotation != null) {
				limits.add(rateLimitAnnotation);
			}

			// Checks for limits (if there are limits).
			if (CollectionUtils.isNotEmpty(limits)) {
				for (final RateLimit limit : limits) {
					// Gets the limit name.
					final String limitName = (StringUtils.isBlank(limit.name())
							? (targetObject.getClass().getSimpleName().toLowerCase() + "-" + method.getName().toLowerCase() + "-" + limit.period())
							: limit.name());
					// Checks the local limit.
					this.checkLocalLimit(limit, limitName);
				}
			}

		}

		// Executes the method normally.
		return proceedingJoinPoint.proceed();

	}

}
