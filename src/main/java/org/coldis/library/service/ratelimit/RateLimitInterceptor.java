package org.coldis.library.service.ratelimit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringValueResolver;

/**
 * Rate limit interceptor.
 */
@Aspect
public class RateLimitInterceptor implements ApplicationContextAware, EmbeddedValueResolverAware {

	/**
	 * Random.
	 */
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitInterceptor.class);

	/**
	 * Local executions.
	 */
	public static Map<String, Map<String, RateLimitStats>> EXECUTIONS = new HashMap<>();

	/**
	 * Value resolver.
	 */
	public static StringValueResolver VALUE_RESOLVER;

	/**
	 * @see org.springframework.context.EmbeddedValueResolverAware#setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(
			final StringValueResolver resolver) {
		RateLimitInterceptor.VALUE_RESOLVER = resolver;
	}

	/**
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(
			final ApplicationContext applicationContext) throws BeansException {
	}

	/**
	 * Resolves a long value.
	 *
	 * @param  String value
	 * @return        Long value.
	 */
	private long resolveLongValue(
			final String value) {
		return Long.parseLong(Objects.requireNonNull(RateLimitInterceptor.VALUE_RESOLVER.resolveStringValue(value)));
	}

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
			final RateLimit limit) {
		RateLimitStats executions = null;
		final Map<String, RateLimitStats> executionsMap = this.getLocalExecutionsMap(name);
		synchronized (executionsMap) {
			executions = executionsMap.get(key);
			if (executions == null) {
				executions = new RateLimitStats();
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
		final RateLimitStats executions = this.getLocalExecutions(name, key, limit);
		// Adds the execution and check if the limit has been reached.
		synchronized (executions) {
			// Updates the constraints.
			executions.setLimit(this.resolveLongValue(limit.limit()));
			executions.setPeriod(Duration.ofSeconds(this.resolveLongValue(limit.period())));
			executions.setBackoffPeriod(Duration.ofSeconds(this.resolveLongValue(limit.backoffPeriod())));

			// Checks the limit.
			try {
				executions.checkLimit(name + (StringUtils.isNotBlank(key) ? "-" + key : ""));
			}
			// Uses a delegate exception if needed.
			catch (final RateLimitException exception) {
				Exception actualException = exception;
				if (!Objects.equals(limit.errorType(), RateLimitException.class) || ArrayUtils.isNotEmpty(limit.randomErrorMessages())) {

					// Selects the message to be used.
					String message = exception.getLocalizedMessage();
					if (ArrayUtils.isNotEmpty(limit.randomErrorMessages())) {
						message = limit.randomErrorMessages()[RateLimitInterceptor.RANDOM.nextInt(limit.randomErrorMessages().length)];
					}

					// Gets the exception constructor.
					Constructor<? extends Exception> constructor = null;
					boolean useSimpleMessages = false;
					boolean useSimpleMessage = false;
					boolean useString = false;
					try {
						constructor = limit.errorType().getConstructor(Collection.class);
						useSimpleMessages = true;
					}
					catch (final Exception constructorException1) {
						try {
							constructor = limit.errorType().getConstructor(SimpleMessage.class);
							useSimpleMessage = true;
						}
						catch (final Exception constructorException2) {
							try {
								constructor = limit.errorType().getConstructor(String.class);
								useString = true;
							}
							catch (final Exception constructorException3) {
								try {
									constructor = limit.errorType().getConstructor();
								}
								catch (final Exception constructorException4) {
								}
							}
						}
					}

					// Initializes the exception.
					if (constructor != null) {
						if (useSimpleMessages) {
							actualException = constructor.newInstance(List.of(new SimpleMessage(message)));
						}
						else if (useSimpleMessage) {
							actualException = constructor.newInstance(new SimpleMessage(message));
						}
						else if (useString) {
							actualException = constructor.newInstance(message);
						}
						else {
							actualException = constructor.newInstance();
						}
					}

				}
				RateLimitInterceptor.LOGGER.debug("Rate limited: " + actualException.getLocalizedMessage());
				throw actualException;
			}
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
					if (Objects.equals(RateLimitInterceptor.VALUE_RESOLVER.resolveStringValue(limit.local()), "true")) {
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
