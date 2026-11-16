package org.coldis.library.service.warmup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestOperations;

/**
 * Refuses, on warm-up threads, the bean methods that have side effects warm-up calls must not have. Every guarded
 * method is in {@link #GUARDS}, the single place where a new one is added: the features themselves carry no warm-up
 * code. The beans are proxied by {@link ServiceWarmUpBeanPostProcessor} only when the warm-up is on.
 */
public class ServiceWarmUpSideEffectAdvisor extends StaticMethodMatcherPointcutAdvisor {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -4021357869471843375L;

	/**
	 * Name of the side effect refused for HTTP requests.
	 */
	static final String HTTP_REQUEST = "HTTP request";

	/**
	 * Methods guarded on warm-up threads. Types are named rather than referenced, since some belong to optional
	 * dependencies (pooled JMS, and persistence for statistics); a guard whose type is absent is skipped.
	 */
	static final List<Guard> GUARDS = List.of(
			// Every request method of a REST template, before its interceptors and request factory.
			new Guard(RestOperations.class.getName(),
					Arrays.stream(RestOperations.class.getMethods()).map(Method::getName).collect(Collectors.toUnmodifiableSet()),
					ServiceWarmUpSideEffectAdvisor.HTTP_REQUEST),
			// Connections and contexts of pooled JMS connection factories (JmsConfigurationHelper.createPooledJmsConnectionFactory).
			new Guard("org.messaginghub.pooled.jms.JmsPoolConnectionFactory",
					Set.of("createConnection", "createQueueConnection", "createTopicConnection", "createContext"), "JMS connection"),
			// Statistics events and summary deltas are buffered and written later on other threads.
			new Guard("org.coldis.library.service.statistics.StatisticsEventServiceComponent", Set.of("upsertStatisticsEvent", "upsertAllStatisticsEvents"),
					"statistics event"),
			new Guard("org.coldis.library.service.statistics.StatisticsEventSummaryBufferServiceComponent", Set.of("bufferDelta"), "statistics summary"),
			// Clears every local and central cache through the cache managers themselves.
			new Guard("org.coldis.library.service.cache.CacheHelper", Set.of("clearCaches"), "cache clear"));

	/**
	 * Guarded methods of a type.
	 *
	 * @param typeName    Name of the type (a class or an interface) whose beans are guarded, subtypes included.
	 * @param methodNames Names of the guarded public methods.
	 * @param sideEffect  Name of the side effect, used in the error message.
	 */
	record Guard(String typeName, Set<String> methodNames, String sideEffect) {

		/**
		 * Gets the type, when present.
		 *
		 * @return The type, or null when it is not present.
		 */
		Class<?> getType() {
			final ClassLoader classLoader = ServiceWarmUpSideEffectAdvisor.class.getClassLoader();
			return (ClassUtils.isPresent(this.typeName, classLoader) ? ClassUtils.resolveClassName(this.typeName, classLoader) : null);
		}

	}

	/**
	 * Guards whose types are present.
	 */
	private final transient List<PresentGuard> presentGuards;

	/**
	 * Guard whose type is present.
	 *
	 * @param type        Type.
	 * @param methodNames Names of the guarded public methods.
	 * @param sideEffect  Name of the side effect.
	 */
	private record PresentGuard(Class<?> type, Set<String> methodNames, String sideEffect) {

		/**
		 * Gets whether a class has the guarded type.
		 *
		 * @param  targetClass Class.
		 * @return             Whether the class has the guarded type.
		 */
		boolean appliesTo(
				final Class<?> targetClass) {
			return this.type.isAssignableFrom(targetClass);
		}

		/**
		 * Gets whether a method of a class is guarded.
		 *
		 * @param  method      Method.
		 * @param  targetClass Class.
		 * @return             Whether the method is guarded.
		 */
		boolean appliesTo(
				final Method method,
				final Class<?> targetClass) {
			return this.appliesTo(targetClass) && Modifier.isPublic(method.getModifiers()) && this.methodNames.contains(method.getName());
		}

	}

	/**
	 * Gets the side effect of a guarded method.
	 *
	 * @param  method      Method.
	 * @param  targetClass Class.
	 * @return             The side effect.
	 */
	private String getSideEffect(
			final Method method,
			final Class<?> targetClass) {
		return this.presentGuards.stream().filter(guard -> guard.appliesTo(method, targetClass)).map(PresentGuard::sideEffect).findFirst()
				.orElse(method.getName());
	}

	/**
	 * Constructor.
	 */
	public ServiceWarmUpSideEffectAdvisor() {
		super();
		this.presentGuards = ServiceWarmUpSideEffectAdvisor.GUARDS.stream().filter(guard -> guard.getType() != null)
				.map(guard -> new PresentGuard(guard.getType(), guard.methodNames(), guard.sideEffect())).toList();
		this.setAdvice((MethodInterceptor) invocation -> {
			if (ServiceWarmUpContext.isWarmingUp()) {
				throw new ServiceWarmUpSideEffectException(
						this.getSideEffect(invocation.getMethod(), ClassUtils.getUserClass(invocation.getThis().getClass())));
			}
			return invocation.proceed();
		});
	}

	/**
	 * @see org.springframework.aop.support.StaticMethodMatcherPointcut#getClassFilter()
	 */
	@Override
	public ClassFilter getClassFilter() {
		return targetClass -> this.presentGuards.stream().anyMatch(guard -> guard.appliesTo(targetClass));
	}

	/**
	 * @see org.springframework.aop.MethodMatcher#matches(java.lang.reflect.Method, java.lang.Class)
	 */
	@Override
	public boolean matches(
			final Method method,
			final Class<?> targetClass) {
		return this.presentGuards.stream().anyMatch(guard -> guard.appliesTo(method, targetClass));
	}

}
