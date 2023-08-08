package org.coldis.library.service.ratelimit;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Rate limit interceptor.
 */
@Aspect
public class RateLimitInterceptor {

	/**
	 * Local executions.
	 */
	public static Map<String, Map<String, RateLimitStats>> EXECUTIONS = new HashMap<>();

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
	 * Gets the local executions map.
	 *
	 * @param  name Name.
	 * @param  key  Key.
	 * @return      Local executions map.
	 */
	private Map<String, RateLimitStats> getLocalExecutionsMap(
			final String name) {
		Map<String, RateLimitStats> executionsMap = null;
		synchronized (RateLimitInterceptor.EXECUTIONS) {
			executionsMap = RateLimitInterceptor.EXECUTIONS.get(name);
			if (executionsMap == null) {
				executionsMap = new TreeMap<>();
				RateLimitInterceptor.EXECUTIONS.put(name, executionsMap);
			}
		}
		return executionsMap;
	}

	/**
	 * Gets the local executions.
	 *
	 * @param  name Name.
	 * @param  key  Key.
	 * @return      Local executions.
	 */
	private RateLimitStats getLocalExecutions(
			final String name,
			final String key,
			final Duration defaultDuration) {
		RateLimitStats executions = null;
		final Map<String, RateLimitStats> executionsMap = this.getLocalExecutionsMap(name);
		synchronized (executionsMap) {
			executions = executionsMap.get(key);
			if (executions == null) {
				executions = new RateLimitStats(defaultDuration);
				executionsMap.put(key, executions);
			}
		}
		return executions;
	}

	/**
	 * Gets the local executions.
	 *
	 * @param name Name.
	 * @param key  Key.
	 */
	@Scheduled(cron = "0 */3 * * * *")
	public void cleanLocalExecutions() {
		for (final Map<String, RateLimitStats> executionsMap : RateLimitInterceptor.EXECUTIONS.values()) {
			final List<String> emptyExecutionsList = executionsMap.entrySet().stream()
					.filter(executions -> CollectionUtils.isEmpty(executions.getValue().getExecutions())).map(executions -> executions.getKey())
					.collect(Collectors.toList());
			for (final String emptyExecutions : emptyExecutionsList) {
				synchronized (executionsMap) {
					executionsMap.remove(emptyExecutions);
				}
			}
		}
	}

	/**
	 * Checks the local limit.
	 *
	 * @param  name              Limit name.
	 * @param  limit             Limit.
	 *
	 * @throws BusinessException If the limit was exceeded.
	 */
	private void checkLocalLimit(
			final String name,
			final String key,
			final RateLimit limit) throws Exception {
		// Gets the execution for the method.
		final RateLimitStats executions = this.getLocalExecutions(name, key, Duration.ofSeconds(limit.period()));
		// Adds the execution and check if the limit has been reached.
		synchronized (executions) {
			final Integer executionsCount = executions.getExecutions().size();
			if ((executionsCount + 1) > limit.limit()) {
				final SimpleMessage errorMessage = new SimpleMessage("service.ratelimit.exceeded",
						"Limit (" + limit.limit() + ") has been reached for method '" + name + "': " + executionsCount,
						new Object[] { limit.limit(), executions.getExecutions().size() });
				throw (limit.businessError() ? new BusinessException(errorMessage) : new IntegrationException(errorMessage));
			}
			executions.getExecutions().add(System.nanoTime());
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

			// Gets the limit key.
			final List<String> keys = new ArrayList<>();
			final List<Parameter> parameters = (method.getParameters() == null ? null : Arrays.asList(method.getParameters()));
			for (Integer argumentIdx = 0; argumentIdx < CollectionUtils.size(parameters); argumentIdx++) {
				final Parameter parameter = parameters.get(argumentIdx);
				if (parameter.getAnnotation(RateLimitKey.class) != null) {
					keys.add(Objects.toString(proceedingJoinPoint.getArgs()[argumentIdx]));
				}
			}
			final String key = Strings.join(keys, '-');

			// Checks for limits (if there are limits).
			if (CollectionUtils.isNotEmpty(limits)) {
				for (final RateLimit limit : limits) {
					// Gets the limit name.
					final String name = (StringUtils.isBlank(limit.name())
							? (targetObject.getClass().getSimpleName().toLowerCase() + "-" + method.getName().toLowerCase() + "-" + limit.period())
							: limit.name());
					// Checks the local limit.
					if (limit.local()) {
						this.checkLocalLimit(name, key, limit);
					}
					// Checks the central limit. FIXME
					else {
						this.checkLocalLimit(name, key, limit);
					}
				}
			}
		}

		// Executes the method normally.
		return proceedingJoinPoint.proceed();

	}

}
