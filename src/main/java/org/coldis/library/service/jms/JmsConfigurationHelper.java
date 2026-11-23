package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.coldis.library.helper.ObjectHelper;
import org.coldis.library.thread.DynamicThreadPoolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jms.AcknowledgeMode;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryFactory;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.boot.autoconfigure.jms.artemis.ExtensibleArtemisConnectionFactoryFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import jakarta.jms.ConnectionFactory;

/**
 * JMS configuration helper.
 */
@Component
public class JmsConfigurationHelper {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JmsConfigurationHelper.class);

	/**
	 * JDK packages allowed by default for object message deserialization.
	 */
	private static final List<String> DEFAULT_DESERIALIZATION_ALLOWED_JAVA_PACKAGES = List.of("java.lang", "java.util", "java.time", "java.math");

	/**
	 * Core size per processor of a bounded listener executor
	 * ({@code unbounded=false}) when neither {@code core-size} nor
	 * {@code core-size-cpu-multiplier} is set (the previous default).
	 */
	public static final double DEFAULT_BOUNDED_CORE_SIZE_CPU_MULTIPLIER = 20D;

	/** Metrics name of the listener executor. */
	public static final String LISTENER_EXECUTOR_METRICS_NAME = "jms.listener";

	/** Metrics name of the Artemis global executor. */
	public static final String GLOBAL_EXECUTOR_METRICS_NAME = "jms.global";

	/** Metrics name of the Artemis global scheduled executor. */
	public static final String GLOBAL_SCHEDULED_EXECUTOR_METRICS_NAME = "jms.global.scheduled";

	/** Metrics name of the Artemis global flow control executor. */
	public static final String GLOBAL_FLOW_CONTROL_EXECUTOR_METRICS_NAME = "jms.global.flow-control";

	/** JMS global thread pool. */
	private ExecutorService globalThreadPool;

	/** JMS global scheduled thread pool. */
	private ScheduledExecutorService globalScheduledThreadPool;

	/** JMS global flow control thread pool. */
	private ExecutorService globalFlowControlThreadPool;

	/** JMS listener executor. */
	private Executor jmsListenerExecutor;

	/**
	 * Default Artemis properties.
	 */
	@Autowired
	@Qualifier("defaultArtemisProperties")
	private ExtendedArtemisProperties defaultProperties;

	/** DTO message converter. */
	@Autowired(required = false)
	private MessageConverter messageConverter;

	/** JMS destination resolver. */
	@Autowired(required = false)
	private DestinationResolver destinationResolver;

	/** Error handler. */
	@Autowired(required = false)
	@Qualifier("enhancedJmsErrorHandler")
	private ErrorHandler errorHandler;

	/** Meter registry. */
	@Autowired(required = false)
	private MeterRegistry meterRegistry;

	/**
	 * If the JMS listener bulkhead is enabled (only used to warn about the legacy
	 * executor concurrency limit; injected before the executor setter).
	 */
	@Value("${org.coldis.library.service.jms.listener.bulkhead.enabled:false}")
	private Boolean isBulkheadEnabled;

	/** Application base packages (comma separated). */
	@Value("${org.coldis.configuration.base-package:}")
	private String basePackages;

	/**
	 * Logs a WARN when a deprecated setting is set.
	 *
	 * @param key    Property key.
	 * @param value  Value (null when not set).
	 * @param reason What happens to it and what to use instead.
	 */
	private static void warnDeprecated(
			final String key,
			final Object value,
			final String reason) {
		if (value != null) {
			JmsConfigurationHelper.LOGGER.warn("{} is deprecated and set ({}): {}", key, value, reason);
		}
	}

	/**
	 * Registers the metrics of a JMS executor (Micrometer
	 * {@code executor.*}, tag {@code name}), when a meter registry exists.
	 *
	 * @param executor Executor.
	 * @param name     Stable executor name.
	 */
	private void bindExecutorMetrics(
			final Executor executor,
			final String name) {
		if ((this.meterRegistry != null) && (executor instanceof final ExecutorService executorService)) {
			new ExecutorServiceMetrics(executorService, name, List.of()).bindTo(this.meterRegistry);
		}
	}

	/**
	 * Sets the Artemis client global thread pools (general, scheduled and flow
	 * control), when custom pools are enabled. Sizes are unchanged;
	 * {@code priority}, {@code parallelism*} and {@code min-runnable*} are
	 * deprecated (still honoured, WARN when set).
	 */
	@Autowired
	public void setJmsGlobalThreadPools(
			@Value("${org.coldis.library.service.jms.custom-pools:true}")
			final Boolean useCustomPools,
			@Value("${org.coldis.library.service.jms.global.executor.name:jms-global-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.global.executor.priority:}")
			final Integer priority,
			@Value("${org.coldis.library.service.jms.global.executor.virtual:false}")
			final Boolean virtual,
			@Value("${org.coldis.library.service.jms.global.executor.parallelism:}")
			final Integer parallelism,
			@Value("${org.coldis.library.service.jms.global.executor.parallelism-cpu-multiplier:}")
			final Double parallelismCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.min-runnable:}")
			final Integer minRunnable,
			@Value("${org.coldis.library.service.jms.global.executor.min-runnable-cpu-multiplier:}")
			final Double minRunnableCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.core-size:1}")
			final Integer corePoolSize,
			@Value("${org.coldis.library.service.jms.global.executor.core-size-cpu-multiplier:}")
			final Double corePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.max-size:}")
			final Integer maxPoolSize,
			@Value("${org.coldis.library.service.jms.global.executor.max-size-cpu-multiplier:}")
			final Double maxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.keep-alive-seconds:60}")
			final Integer keepAliveSeconds,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.name:jms-global-scheduled-thread}")
			final String scheduledName,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.priority:}")
			final Integer scheduledPriority,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.virtual:false}")
			final Boolean scheduledVirtual,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.core-size:}")
			final Integer scheduledCorePoolSize,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.core-size-cpu-multiplier:1}")
			final Double scheduledCorePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.name:${org.coldis.library.service.jms.global.executor.name:jms-global-flow-control-thread}}")
			final String flowControlName,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.priority:}")
			final Integer flowControlPriority,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.virtual:false}")
			final Boolean flowControlVirtual,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.parallelism:}")
			final Integer flowControlParallelism,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.parallelism-cpu-multiplier:}")
			final Double flowControlParallelismCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.min-runnable:}")
			final Integer flowControlMinRunnable,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.min-runnable-cpu-multiplier:}")
			final Double flowControlMinRunnableCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.core-size:}")
			final Integer flowControlCorePoolSize,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.core-size-cpu-multiplier:1}")
			final Double flowControlCorePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.max-size:}")
			final Integer flowControlMaxPoolSize,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.max-size-cpu-multiplier:}")
			final Double flowControlMaxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.keep-alive-seconds:60}")
			final Integer flowControlKeepAliveSeconds
			
	) {
		if (useCustomPools) {
			final String priorityReason = "thread priority has no effect on Linux without -XX:ThreadPriorityPolicy=1 (doc/jms-threads.md).";
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.executor.priority", priority, priorityReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.scheduled.executor.priority", scheduledPriority, priorityReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.flow-control.executor.priority", flowControlPriority, priorityReason);
			final String forkJoinReason = "it turns the pool into a fork-join pool that does not compensate for blocking calls (doc/jms-threads.md).";
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.executor.parallelism(-cpu-multiplier)",
					ObjectUtils.firstNonNull(parallelism, parallelismCpuMultiplier), forkJoinReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.executor.min-runnable(-cpu-multiplier)",
					ObjectUtils.firstNonNull(minRunnable, minRunnableCpuMultiplier), forkJoinReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.flow-control.executor.parallelism(-cpu-multiplier)",
					ObjectUtils.firstNonNull(flowControlParallelism, flowControlParallelismCpuMultiplier), forkJoinReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.global.flow-control.executor.min-runnable(-cpu-multiplier)",
					ObjectUtils.firstNonNull(flowControlMinRunnable, flowControlMinRunnableCpuMultiplier), forkJoinReason);
			final boolean isCreated = (this.globalThreadPool != null);
			this.globalThreadPool = (this.globalThreadPool == null
					? (ExecutorService) new DynamicThreadPoolFactory().withName(name)
							.withPriority(Objects.requireNonNullElse(priority, Thread.NORM_PRIORITY)).withVirtual(virtual).withParallelism(parallelism)
							.withParallelismCpuMultiplier(parallelismCpuMultiplier).withMinRunnable(minRunnable)
							.withMinRunnableCpuMultiplier(minRunnableCpuMultiplier).withCorePoolSize(corePoolSize)
							.withCorePoolSizeCpuMultiplier(corePoolSizeCpuMultiplier).withMaxPoolSize(maxPoolSize)
							.withMaxPoolSizeCpuMultiplier(maxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
					: this.globalThreadPool);
			this.globalScheduledThreadPool = (this.globalScheduledThreadPool == null
					? (ScheduledExecutorService) new DynamicThreadPoolFactory().withName(scheduledName).withScheduled(true)
							.withPriority(Objects.requireNonNullElse(scheduledPriority, Thread.NORM_PRIORITY)).withVirtual(scheduledVirtual)
							.withCorePoolSize(scheduledCorePoolSize).withCorePoolSizeCpuMultiplier(scheduledCorePoolSizeCpuMultiplier).build()
					: this.globalScheduledThreadPool);
			this.globalFlowControlThreadPool = (this.globalFlowControlThreadPool == null ? (ExecutorService) new DynamicThreadPoolFactory()
					.withName(flowControlName).withPriority(Objects.requireNonNullElse(flowControlPriority, Thread.NORM_PRIORITY))
					.withVirtual(flowControlVirtual).withParallelism(flowControlParallelism)
					.withParallelismCpuMultiplier(flowControlParallelismCpuMultiplier).withMinRunnable(flowControlMinRunnable)
					.withMinRunnableCpuMultiplier(flowControlMinRunnableCpuMultiplier).withCorePoolSize(flowControlCorePoolSize)
					.withCorePoolSizeCpuMultiplier(flowControlCorePoolSizeCpuMultiplier).withMaxPoolSize(flowControlMaxPoolSize)
					.withMaxPoolSizeCpuMultiplier(flowControlMaxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(flowControlKeepAliveSeconds)).build()
					: this.globalFlowControlThreadPool);
			if (!isCreated) {
				this.bindExecutorMetrics(this.globalThreadPool, JmsConfigurationHelper.GLOBAL_EXECUTOR_METRICS_NAME);
				this.bindExecutorMetrics(this.globalScheduledThreadPool, JmsConfigurationHelper.GLOBAL_SCHEDULED_EXECUTOR_METRICS_NAME);
				this.bindExecutorMetrics(this.globalFlowControlThreadPool, JmsConfigurationHelper.GLOBAL_FLOW_CONTROL_EXECUTOR_METRICS_NAME);
			}
			ActiveMQClient.injectPools(this.globalThreadPool, this.globalScheduledThreadPool, this.globalFlowControlThreadPool);
		}
	}

	/**
	 * Creates the legacy listener executor: a {@link SimpleAsyncTaskExecutor}
	 * whose concurrency limit counts polling consumers. Deprecated (see the WARN
	 * it logs).
	 */
	private void setLegacyThrottledJmsListenerExecutor(
			final String name,
			final Integer priority,
			final Boolean virtual,
			final Integer concurrency,
			final Double concurrencyCpuMultiplier) {
		final int actualConcurrency = (concurrencyCpuMultiplier == null ? concurrency
				: (int) Math.ceil(Runtime.getRuntime().availableProcessors() * concurrencyCpuMultiplier));
		this.jmsListenerExecutor = new SimpleAsyncTaskExecutor(name);
		((SimpleAsyncTaskExecutor) this.jmsListenerExecutor).setConcurrencyLimit(actualConcurrency <= 0 ? -1 : actualConcurrency);
		((SimpleAsyncTaskExecutor) this.jmsListenerExecutor).setThreadNamePrefix(name);
		((SimpleAsyncTaskExecutor) this.jmsListenerExecutor).setVirtualThreads(virtual);
		((SimpleAsyncTaskExecutor) this.jmsListenerExecutor).setThreadPriority(priority);
		JmsConfigurationHelper.LOGGER.info("JMS listener executor created with concurrency limit of {} and thread name prefix of {}", actualConcurrency,
				name);
		if (actualConcurrency > 0) {
			JmsConfigurationHelper.LOGGER.warn("org.coldis.library.service.jms.listener.executor.concurrency(-cpu-multiplier) is deprecated and set ({}). "
					+ "It limits polling consumers, not handlers: a consumer that has to start or resubmit another consumer waits for a slot while holding "
					+ "its own, so listeners can starve or stop processing until a restart. Remove it and bound concurrent handlers with "
					+ "org.coldis.library.service.jms.listener.bulkhead.* instead (doc/jms-listener-bulkhead.md).", actualConcurrency);
			if (Boolean.TRUE.equals(this.isBulkheadEnabled)) {
				JmsConfigurationHelper.LOGGER.warn("The JMS listener bulkhead is enabled together with the legacy listener executor concurrency limit, "
						+ "which can still stop listeners whatever the bulkhead does: remove the legacy limit.");
			}
		}
	}

	/**
	 * Sets the JMS listener executor: the threads of the listener containers'
	 * polling consumers, which also run the handlers. Concurrent handlers are
	 * bounded by the listener bulkhead ({@code listener.bulkhead.*}), not by this
	 * executor (doc/jms-threads.md).
	 * <ul>
	 * <li>Default ({@code unbounded=true}): a cached pool, no core threads, no
	 * maximum, idle threads released after {@code keep-alive-seconds},
	 * {@code virtual} respected. {@code core-size*}, {@code parallelism*} and
	 * {@code min-runnable*} are ignored, with a WARN.</li>
	 * <li>{@code unbounded=false}: the previous bounded pool, unchanged
	 * ({@code core-size*} threads, {@value #DEFAULT_BOUNDED_CORE_SIZE_CPU_MULTIPLIER}
	 * per processor when not set; a fork-join pool with {@code parallelism*}),
	 * with a WARN that a thread cap can starve listener containers.</li>
	 * <li>{@code concurrency*} set (deprecated): a {@link SimpleAsyncTaskExecutor}
	 * limited to that many consumers, with a WARN.</li>
	 * </ul>
	 * Deprecated, still honoured where they had an effect, WARN when set:
	 * {@code concurrency*}, {@code core-size*}, {@code parallelism*},
	 * {@code min-runnable*}, {@code priority} (no effect on Linux).
	 */
	@Autowired
	public void setJmsListenerExecutor(
			@Value("${org.coldis.library.service.jms.listener.executor.name:jms-listener-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.listener.executor.priority:}")
			final Integer priority,
			@Value("${org.coldis.library.service.jms.listener.executor.virtual:false}")
			final Boolean virtual,
			@Value("${org.coldis.library.service.jms.listener.executor.concurrency:}")
			final Integer concurrency,
			@Value("${org.coldis.library.service.jms.listener.executor.concurrency-cpu-multiplier:}")
			final Double concurrencyCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.parallelism:}")
			final Integer parallelism,
			@Value("${org.coldis.library.service.jms.listener.executor.parallelism-cpu-multiplier:}")
			final Double parallelismCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.min-runnable:}")
			final Integer minRunnable,
			@Value("${org.coldis.library.service.jms.listener.executor.min-runnable-cpu-multiplier:}")
			final Double minRunnableCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.core-size:}")
			final Integer corePoolSize,
			@Value("${org.coldis.library.service.jms.listener.executor.core-size-cpu-multiplier:}")
			final Double corePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.keep-alive-seconds:60}")
			final Integer keepAliveSeconds,
			@Value("${org.coldis.library.service.jms.listener.executor.unbounded:true}")
			final Boolean isUnbounded) {
		JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.priority", priority,
				"thread priority has no effect on Linux without -XX:ThreadPriorityPolicy=1, nor on virtual threads (doc/jms-threads.md).");
		final Integer actualPriority = Objects.requireNonNullElse(priority, Thread.NORM_PRIORITY);
		final boolean isBounded = Boolean.FALSE.equals(isUnbounded);
		if ((concurrency != null) || (concurrencyCpuMultiplier != null)) {
			this.setLegacyThrottledJmsListenerExecutor(name, actualPriority, virtual, concurrency, concurrencyCpuMultiplier);
		}
		else if (isBounded) {
			final String boundedReason = "honoured only because unbounded=false; bound concurrent handlers with listener.bulkhead.* instead.";
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.core-size(-cpu-multiplier)",
					ObjectUtils.firstNonNull(corePoolSize, corePoolSizeCpuMultiplier), boundedReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.parallelism(-cpu-multiplier)",
					ObjectUtils.firstNonNull(parallelism, parallelismCpuMultiplier), boundedReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.min-runnable(-cpu-multiplier)",
					ObjectUtils.firstNonNull(minRunnable, minRunnableCpuMultiplier), boundedReason);
			final Double actualCorePoolSizeCpuMultiplier = ((corePoolSize == null) && (corePoolSizeCpuMultiplier == null)
					? Double.valueOf(JmsConfigurationHelper.DEFAULT_BOUNDED_CORE_SIZE_CPU_MULTIPLIER)
					: corePoolSizeCpuMultiplier);
			JmsConfigurationHelper.LOGGER.warn("The JMS listener executor is bounded (org.coldis.library.service.jms.listener.executor.unbounded=false). "
					+ "A thread cap can starve listener containers: idle pollers hold threads busy queues need. Bound concurrent handlers with "
					+ "org.coldis.library.service.jms.listener.bulkhead.* instead (doc/jms-threads.md).");
			this.jmsListenerExecutor = (this.jmsListenerExecutor == null
					? (ExecutorService) new DynamicThreadPoolFactory().withName(name).withPriority(actualPriority).withVirtual(virtual)
							.withParallelism(parallelism).withParallelismCpuMultiplier(parallelismCpuMultiplier).withMinRunnable(minRunnable)
							.withMinRunnableCpuMultiplier(minRunnableCpuMultiplier).withCorePoolSize(corePoolSize)
							.withCorePoolSizeCpuMultiplier(actualCorePoolSizeCpuMultiplier).withMaxPoolSize(corePoolSize)
							.withMaxPoolSizeCpuMultiplier(actualCorePoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
					: this.jmsListenerExecutor);
		}
		else {
			final String ignoredReason = "ignored: the listener executor is unbounded (a cached pool with no core threads); bound concurrent handlers "
					+ "with listener.bulkhead.* instead.";
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.core-size(-cpu-multiplier)",
					ObjectUtils.firstNonNull(corePoolSize, corePoolSizeCpuMultiplier), ignoredReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.parallelism(-cpu-multiplier)",
					ObjectUtils.firstNonNull(parallelism, parallelismCpuMultiplier), ignoredReason);
			JmsConfigurationHelper.warnDeprecated("org.coldis.library.service.jms.listener.executor.min-runnable(-cpu-multiplier)",
					ObjectUtils.firstNonNull(minRunnable, minRunnableCpuMultiplier), ignoredReason);
			this.jmsListenerExecutor = (this.jmsListenerExecutor == null
					? (ExecutorService) new DynamicThreadPoolFactory().withName(name).withPriority(actualPriority).withVirtual(virtual).withCorePoolSize(0)
							.withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
					: this.jmsListenerExecutor);
		}
		this.bindExecutorMetrics(this.jmsListenerExecutor, JmsConfigurationHelper.LISTENER_EXECUTOR_METRICS_NAME);
	}

	/**
	 * Sets the JMS listener executor (the signature before the {@code unbounded}
	 * setting). Behaves as the current setter with {@code unbounded=true}, the
	 * default: the pool is unbounded and the sizes passed here are ignored, with
	 * a WARN. To keep a bounded pool, call the current setter with
	 * {@code unbounded=false}.
	 *
	 * @deprecated Use
	 *                 {@link #setJmsListenerExecutor(String, Integer, Boolean, Integer, Double, Integer, Double, Integer, Double, Integer, Double, Integer, Boolean)};
	 *                 bound concurrent handlers with the listener bulkhead.
	 */
	@Deprecated
	public void setJmsListenerExecutor(
			final String name,
			final Integer priority,
			final Boolean virtual,
			final Integer concurrency,
			final Double concurrencyCpuMultiplier,
			final Integer parallelism,
			final Double parallelismCpuMultiplier,
			final Integer minRunnable,
			final Double minRunnableCpuMultiplier,
			final Integer corePoolSize,
			final Double corePoolSizeCpuMultiplier,
			final Integer keepAliveSeconds) {
		this.setJmsListenerExecutor(name, priority, virtual, concurrency, concurrencyCpuMultiplier, parallelism, parallelismCpuMultiplier, minRunnable,
				minRunnableCpuMultiplier, corePoolSize, corePoolSizeCpuMultiplier, keepAliveSeconds, true);
	}

	/**
	 * Merges the default properties with the actual properties.
	 *
	 * @param  properties Properties.
	 * @return            The merged properties.
	 */
	private ExtendedArtemisProperties mergeProperties(
			final ArtemisProperties properties) {
		final ExtendedArtemisProperties mergedProperties = new ExtendedArtemisProperties();
		final JmsPoolConnectionFactoryProperties defaultJmsPoolConnectionFactoryProperties = new JmsPoolConnectionFactoryProperties();
		ObjectHelper.copyAttributes(this.defaultProperties, mergedProperties, true, true, null, (
				getter,
				sourceValue,
				targetValue) -> sourceValue != null);
		ObjectHelper.copyAttributes(this.defaultProperties.getPool(), mergedProperties.getPool(), true, true, null, (
				getter,
				sourceValue,
				targetValue) -> sourceValue != null);
		ObjectHelper.copyAttributes(properties, mergedProperties, true, true, null, (
				getter,
				sourceValue,
				targetValue) -> sourceValue != null);
		// Only if the pool is not equal to the properties config.
		if (!EqualsBuilder.reflectionEquals(properties.getPool(), defaultJmsPoolConnectionFactoryProperties, false)) {
			ObjectHelper.copyAttributes(properties.getPool(), mergedProperties.getPool(), true, true, null, (
					getter,
					sourceValue,
					targetValue) -> sourceValue != null);
		}

		// Copies everything back to the properties.
		ObjectHelper.copyAttributes(mergedProperties, properties, true, true, null, (
				getter,
				sourceValue,
				targetValue) -> true);
		ObjectHelper.copyAttributes(mergedProperties.getPool(), properties.getPool(), true, true, null, (
				getter,
				sourceValue,
				targetValue) -> true);

		return mergedProperties;
	}

	/**
	 * Gets the default allow list for object message deserialization: the coldis
	 * and application base packages, plus common JDK value packages.
	 *
	 * @return The default allow list (comma separated).
	 */
	private String getDefaultDeserializationAllowList() {
		final StringJoiner allowList = new StringJoiner(",");
		allowList.add("org.coldis");
		Arrays.stream(StringUtils.defaultString(this.basePackages).split(",")).map(String::trim).filter(StringUtils::isNotBlank).forEach(allowList::add);
		JmsConfigurationHelper.DEFAULT_DESERIALIZATION_ALLOWED_JAVA_PACKAGES.forEach(allowList::add);
		return allowList.toString();
	}

	/**
	 * Sets the connection extended properties.
	 */
	private void setConnectionExtendedProperties(
			final ExtendedArtemisProperties actualProperties,
			final ActiveMQConnectionFactory connectionFactory) {

		// consumerWindowSize
		final Integer consumerWindowSize = actualProperties.getConsumerWindowSize();
		if (consumerWindowSize != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding consumerWindowSize → {}", consumerWindowSize);
			connectionFactory.setConsumerWindowSize(consumerWindowSize);
		}

		// consumerMaxRate
		final Integer consumerMaxRate = actualProperties.getConsumerMaxRate();
		if (consumerMaxRate != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding consumerMaxRate → {}", consumerMaxRate);
			connectionFactory.setConsumerMaxRate(consumerMaxRate);
		}

		// producerWindowSize
		final Integer producerWindowSize = actualProperties.getProducerWindowSize();
		if (producerWindowSize != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding producerWindowSize → {}", producerWindowSize);
			connectionFactory.setProducerWindowSize(producerWindowSize);
		}

		// producerMaxRate
		final Integer producerMaxRate = actualProperties.getProducerMaxRate();
		if (producerMaxRate != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding producerMaxRate → {}", producerMaxRate);
			connectionFactory.setProducerMaxRate(producerMaxRate);
		}

		// confirmationWindowSize
		final Integer confirmationWindowSize = actualProperties.getConfirmationWindowSize();
		if (confirmationWindowSize != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding confirmationWindowSize → {}", confirmationWindowSize);
			connectionFactory.setConfirmationWindowSize(confirmationWindowSize);
		}

		// transactionBatchSize (ackBatchSize)
		final Integer ackBatchSize = actualProperties.getAckBatchSize();
		if (ackBatchSize != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding transactionBatchSize → {}", ackBatchSize);
			connectionFactory.setTransactionBatchSize(ackBatchSize);
		}

		// dupsOKBatchSize
		final Integer dupsAckBatchSize = actualProperties.getDupsAckBatchSize();
		if (dupsAckBatchSize != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding dupsOKBatchSize → {}", dupsAckBatchSize);
			connectionFactory.setDupsOKBatchSize(dupsAckBatchSize);
		}

		// blockOnAcknowledge
		final Boolean blockOnAcknowledge = actualProperties.getBlockOnAcknowledge();
		if (blockOnAcknowledge != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding blockOnAcknowledge → {}", blockOnAcknowledge);
			connectionFactory.setBlockOnAcknowledge(blockOnAcknowledge);
		}

		// useGlobalPools
		final Boolean useGlobalPools = actualProperties.getUseGlobalPools();
		if (useGlobalPools != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding useGlobalPools → {}", useGlobalPools);
			connectionFactory.setUseGlobalPools(useGlobalPools);
		}

		// cacheDestinations
		final Boolean cacheDestinations = actualProperties.getCacheDestinations();
		if (cacheDestinations != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding cacheDestinations → {}", cacheDestinations);
			connectionFactory.setCacheDestinations(cacheDestinations);
		}

		// cacheLargeMessagesClient
		final Boolean cacheLargeMessagesClient = actualProperties.getCacheLargeMessagesClient();
		if (cacheLargeMessagesClient != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding cacheLargeMessagesClient → {}", cacheLargeMessagesClient);
			connectionFactory.setCacheLargeMessagesClient(cacheLargeMessagesClient);
		}

		// compressLargeMessage
		final Boolean compressLargeMessage = actualProperties.getCompressLargeMessage();
		if (compressLargeMessage != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding compressLargeMessage → {}", compressLargeMessage);
			connectionFactory.setCompressLargeMessage(compressLargeMessage);
		}

		// compressionLevel
		final Integer compressionLevel = actualProperties.getCompressionLevel();
		if (compressionLevel != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding compressionLevel → {}", compressionLevel);
			connectionFactory.setCompressionLevel(compressionLevel);
		}

		// blockOnDurableSend
		final Boolean blockOnDurableSend = actualProperties.getBlockOnDurableSend();
		if (blockOnDurableSend != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding blockOnDurableSend → {}", blockOnDurableSend);
			connectionFactory.setBlockOnDurableSend(blockOnDurableSend);
		}

		// blockOnNonDurableSend
		final Boolean blockOnNonDurableSend = actualProperties.getBlockOnNonDurableSend();
		if (blockOnNonDurableSend != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding blockOnNonDurableSend → {}", blockOnNonDurableSend);
			connectionFactory.setBlockOnNonDurableSend(blockOnNonDurableSend);
		}

		// clientFailureCheckPeriod
		final Long clientFailureCheckPeriod = actualProperties.getClientFailureCheckPeriod();
		if (clientFailureCheckPeriod != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding clientFailureCheckPeriod → {}", clientFailureCheckPeriod);
			connectionFactory.setClientFailureCheckPeriod(clientFailureCheckPeriod);
		}

		// connectionTTL
		final Long connectionTTL = actualProperties.getConnectionTTL();
		if (connectionTTL != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding connectionTTL → {}", connectionTTL);
			connectionFactory.setConnectionTTL(connectionTTL);
		}

		// callTimeout
		final Long callTimeout = actualProperties.getCallTimeout();
		if (callTimeout != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding callTimeout → {}", callTimeout);
			connectionFactory.setCallTimeout(callTimeout);
		}

		// callFailoverTimeout
		final Long callFailoverTimeout = actualProperties.getCallFailoverTimeout();
		if (callFailoverTimeout != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding callFailoverTimeout → {}", callFailoverTimeout);
			connectionFactory.setCallFailoverTimeout(callFailoverTimeout);
		}

		// reconnectAttempts
		final Integer reconnectAttempts = actualProperties.getReconnectAttempts();
		if (reconnectAttempts != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding reconnectAttempts → {}", reconnectAttempts);
			connectionFactory.setReconnectAttempts(reconnectAttempts);
		}

		// retryInterval
		final Long retryInterval = actualProperties.getRetryInterval();
		if (retryInterval != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding retryInterval → {}", retryInterval);
			connectionFactory.setRetryInterval(retryInterval);
		}

		// retryIntervalMultiplier
		final Double retryIntervalMultiplier = actualProperties.getRetryIntervalMultiplier();
		if (retryIntervalMultiplier != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding retryIntervalMultiplier → {}", retryIntervalMultiplier);
			connectionFactory.setRetryIntervalMultiplier(retryIntervalMultiplier);
		}

		// deserializationAllowList: an explicit property wins; otherwise the library
		// default applies unless the broker URL already set one.
		final String deserializationAllowList = actualProperties.getDeserializationAllowList();
		if (deserializationAllowList != null) {
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - overriding deserializationAllowList → {}", deserializationAllowList);
			connectionFactory.setDeserializationAllowList(deserializationAllowList);
		}
		else if (connectionFactory.getDeserializationAllowList() == null) {
			final String defaultDeserializationAllowList = this.getDefaultDeserializationAllowList();
			JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory - default deserializationAllowList → {}", defaultDeserializationAllowList);
			connectionFactory.setDeserializationAllowList(defaultDeserializationAllowList);
		}
		if (Arrays.stream(StringUtils.defaultString(connectionFactory.getDeserializationAllowList()).split(",")).map(String::trim)
				.anyMatch("*"::equals)) {
			JmsConfigurationHelper.LOGGER.warn("Configuring JMS ConnectionFactory - deserializationAllowList allows every class ('*'): "
					+ "object message bodies of any class are deserialized.");
		}

		// dynamicCredits — activate when depthThreshold is set
		final Long dynamicCreditsDepthThreshold = actualProperties.getDynamicCreditsDepthThreshold();
		if (dynamicCreditsDepthThreshold != null) {
			// Note: consumerWindowSize is not forced here. The interceptor scales credits
			// relative to whatever value the consumer sends. Operators wanting strict
			// 1-message-at-a-time fairness below the threshold should set
			// spring.artemis.consumer-window-size=0 explicitly.
			// Sensible defaults applied at the wiring site so that a null here is visible
			// in code (and via WARN log) rather than hidden inside the property getters.
			// If a value falls back to its default, that indicates the property pipeline
			// failed to resolve it from service.properties / overrides — diagnose accordingly.
			final Double multiplierProp = actualProperties.getDynamicCreditsMultiplier();
			final Integer maxCreditsProp = actualProperties.getDynamicCreditsMaxCredits();
			final Long cacheTtlProp = actualProperties.getDynamicCreditsCacheTtl();
			if ((multiplierProp == null) || (maxCreditsProp == null) || (cacheTtlProp == null)) {
				JmsConfigurationHelper.LOGGER.warn(
						"Configuring JMS ConnectionFactory - dynamic credits property fallback in effect: multiplier={} maxCredits={} cacheTtlMs={}. "
								+ "Check spring.artemis.dynamic-credits-* property resolution.",
						multiplierProp, maxCreditsProp, cacheTtlProp);
			}
			final double multiplier = (multiplierProp != null ? multiplierProp : 1.0);
			final int maxCredits = (maxCreditsProp != null ? maxCreditsProp : 10 * 1024 * 1024);
			final long cacheTtl = (cacheTtlProp != null ? cacheTtlProp : 5000L);
			final DynamicCreditClientInterceptor interceptor = new DynamicCreditClientInterceptor(
					connectionFactory,
					dynamicCreditsDepthThreshold,
					multiplier,
					maxCredits,
					cacheTtl);
			connectionFactory.getServerLocator().addOutgoingInterceptor(interceptor);
			JmsConfigurationHelper.LOGGER.info(
					"Configuring JMS ConnectionFactory - dynamic credits enabled: depthThreshold={} multiplier={} maxCredits={} cacheTtlMs={}",
					dynamicCreditsDepthThreshold, multiplier, maxCredits, cacheTtl);
		}

	}

	/**
	 * Creates the JMS connection factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  properties  JMS properties.
	 * @return             The JMS connection factory.
	 */
	public ConnectionFactory createNativeJmsConnectionFactory(
			final ListableBeanFactory beanFactory,
			final ArtemisProperties properties) {
		JmsConfigurationHelper.LOGGER.debug("Configuring JMS ConnectionFactory for properties → {}",
				properties.getClass().getAnnotation(ConfigurationProperties.class) == null ? null
						: properties.getClass().getAnnotation(ConfigurationProperties.class).prefix());
		final ExtendedArtemisProperties actualProperties = this.mergeProperties(properties);
		final ActiveMQConnectionFactory connectionFactory = new ExtensibleArtemisConnectionFactoryFactory(beanFactory, actualProperties)
				.createConnectionFactory(ActiveMQConnectionFactory::new, ActiveMQConnectionFactory::new);
		this.setConnectionExtendedProperties(actualProperties, connectionFactory);
		return connectionFactory;
	}

	/**
	 * Creates the JMS connection factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  properties  JMS properties.
	 * @return             The JMS connection factory.
	 */
	public ConnectionFactory createPooledJmsConnectionFactory(
			final ListableBeanFactory beanFactory,
			final ArtemisProperties properties) {
		JmsConfigurationHelper.LOGGER.debug("Configuring pooled JMS ConnectionFactory for properties → {}",
				properties.getClass().getAnnotation(ConfigurationProperties.class) == null ? null
						: properties.getClass().getAnnotation(ConfigurationProperties.class).prefix());
		final ExtendedArtemisProperties actualProperties = this.mergeProperties(properties);
		return MeteredJmsPoolConnectionFactory.create(this.meterRegistry, actualProperties.getPool(),
				this.createNativeJmsConnectionFactory(beanFactory, actualProperties));
	}

	/**
	 * Creates the JMS connection factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  properties  JMS properties.
	 * @return             The JMS connection factory.
	 */
	@Deprecated
	public ConnectionFactory createJmsConnectionFactory(
			final ListableBeanFactory beanFactory,
			final ArtemisProperties properties) {
		return this.createPooledJmsConnectionFactory(beanFactory, properties);
	}

	/**
	 * Creates the JMS container factory builder.
	 *
	 * @return The JMS container factory builder.
	 */
	public JmsListenerContainerFactoryBuilder createJmsListenerContainerFactoryBuilder() {
		return new JmsListenerContainerFactoryBuilder().taskExecutor(this.jmsListenerExecutor).destinationResolver(this.destinationResolver)
				.messageConverter(this.messageConverter).errorHandler(this.errorHandler);
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory      Connection factory.
	 * @param  destinationResolver    Destination resolver.
	 * @param  messageConverter       Message converter.
	 * @param  exceptionListener      Error handler.
	 * @param  backoffInitialInterval Back-off initial interval
	 * @param  backoffMultiplier      Back-off multiplier.
	 * @param  backoffMaxInterval     Back-off max interval.
	 * @return                        The JMS container factory.
	 */
	@Deprecated
	public DefaultJmsListenerContainerFactory createJmsContainerFactory(
			final Executor taskExecutor,
			final ConnectionFactory connectionFactory,
			final DestinationResolver destinationResolver,
			final MessageConverter messageConverter,
			final ErrorHandler errorHandler,
			final Integer cacheLevel,
			final Integer maxMessagesPerTask,
			final Long backoffInitialInterval,
			final Double backoffMultiplier,
			final Long backoffMaxElapsedTime) {
		return new JmsListenerContainerFactoryBuilder().taskExecutor(taskExecutor).destinationResolver(destinationResolver).messageConverter(messageConverter)
				.errorHandler(errorHandler).connectionFactory(connectionFactory).sessionTransacted(true).autoStartup(true)
				.maxMessagesPerTask(maxMessagesPerTask == null ? this.defaultProperties.getMaxMessagesPerTask() : maxMessagesPerTask)
				.cacheLevel(cacheLevel == null ? this.defaultProperties.getCacheLevel() : cacheLevel)
				.backoff(backoffInitialInterval == null ? this.defaultProperties.getBackoffInitialInterval() : backoffInitialInterval,
						backoffMultiplier == null ? this.defaultProperties.getBackoffMultiplier() : backoffMultiplier,
						backoffMaxElapsedTime == null ? this.defaultProperties.getBackoffMaxElapsedTime() : backoffMaxElapsedTime)
				.build();
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory      Connection factory.
	 * @param  destinationResolver    Destination resolver.
	 * @param  messageConverter       Message converter.
	 * @param  exceptionListener      Error handler.
	 * @param  backoffInitialInterval Back-off initial interval
	 * @param  backoffMultiplier      Back-off multiplier.
	 * @param  backoffMaxInterval     Back-off max interval.
	 * @return                        The JMS container factory.
	 */
	@Deprecated
	public DefaultJmsListenerContainerFactory createJmsContainerFactory(
			final Executor taskExecutor,
			final ConnectionFactory connectionFactory,
			final DestinationResolver destinationResolver,
			final MessageConverter messageConverter,
			final Integer cacheLevel,
			final Integer maxMessagesPerTask,
			final Long backoffInitialInterval,
			final Double backoffMultiplier,
			final Long backoffMaxElapsedTime) {
		return this.createJmsContainerFactory(taskExecutor, connectionFactory, destinationResolver, messageConverter, null, cacheLevel, maxMessagesPerTask,
				backoffInitialInterval, backoffMultiplier, backoffMaxElapsedTime);
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory      Connection factory.
	 * @param  destinationResolver    Destination resolver.
	 * @param  messageConverter       Message converter.
	 * @param  exceptionListener      Error handler.
	 * @param  backoffInitialInterval Back-off initial interval
	 * @param  backoffMultiplier      Back-off multiplier.
	 * @param  backoffMaxInterval     Back-off max interval.
	 * @return                        The JMS container factory.
	 */
	@Deprecated
	public DefaultJmsListenerContainerFactory createJmsContainerFactory(
			final ConnectionFactory connectionFactory) {
		return this.createJmsContainerFactory(this.jmsListenerExecutor, connectionFactory, this.destinationResolver, this.messageConverter, this.errorHandler,
				null, null, null, null, null);
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory      Connection factory.
	 * @param  destinationResolver    Destination resolver.
	 * @param  messageConverter       Message converter.
	 * @param  exceptionListener      Error handler.
	 * @param  backoffInitialInterval Back-off initial interval
	 * @param  backoffMultiplier      Back-off multiplier.
	 * @param  backoffMaxElapsedTime  Back-off max interval.
	 * @return                        The JMS container factory.
	 */
	@Deprecated
	public DefaultJmsListenerContainerFactory createJmsTopicContainerFactory(
			final Executor taskExecutor,
			final ConnectionFactory connectionFactory,
			final DestinationResolver destinationResolver,
			final MessageConverter messageConverter,
			final ErrorHandler errorHandler,
			final Integer cacheLevel,
			final Integer maxMessagesPerTask,
			final Long backoffInitialInterval,
			final Double backoffMultiplier,
			final Long backoffMaxElapsedTime) {
		// Creates a new container factory.
		final DefaultJmsListenerContainerFactory jmsContainerFactory = this.createJmsContainerFactory(taskExecutor, connectionFactory, destinationResolver,
				messageConverter, errorHandler, cacheLevel, maxMessagesPerTask, backoffInitialInterval, backoffMultiplier, backoffMaxElapsedTime);
		jmsContainerFactory.setSubscriptionDurable(true);
		jmsContainerFactory.setSubscriptionShared(true);
		// Returns the container factory.
		return jmsContainerFactory;
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory      Connection factory.
	 * @param  destinationResolver    Destination resolver.
	 * @param  messageConverter       Message converter.
	 * @param  exceptionListener      Error handler.
	 * @param  backoffInitialInterval Back-off initial interval
	 * @param  backoffMultiplier      Back-off multiplier.
	 * @param  backoffMaxElapsedTime  Back-off max interval.
	 * @return                        The JMS container factory.
	 */
	@Deprecated
	public DefaultJmsListenerContainerFactory createJmsTopicContainerFactory(
			final Executor taskExecutor,
			final ConnectionFactory connectionFactory,
			final DestinationResolver destinationResolver,
			final MessageConverter messageConverter,
			final Integer cacheLevel,
			final Integer maxMessagesPerTask,
			final Long backoffInitialInterval,
			final Double backoffMultiplier,
			final Long backoffMaxElapsedTime) {
		return this.createJmsTopicContainerFactory(taskExecutor, connectionFactory, destinationResolver, messageConverter, null, cacheLevel, maxMessagesPerTask,
				backoffInitialInterval, backoffMultiplier, backoffMaxElapsedTime);
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory      Connection factory.
	 * @param  destinationResolver    Destination resolver.
	 * @param  messageConverter       Message converter.
	 * @param  exceptionListener      Error handler.
	 * @param  backoffInitialInterval Back-off initial interval
	 * @param  backoffMultiplier      Back-off multiplier.
	 * @param  backoffMaxElapsedTime  Back-off max interval.
	 * @return                        The JMS container factory.
	 */
	@Deprecated
	public DefaultJmsListenerContainerFactory createJmsTopicContainerFactory(
			final ConnectionFactory connectionFactory) {
		return this.createJmsTopicContainerFactory(this.jmsListenerExecutor, connectionFactory, this.destinationResolver, this.messageConverter,
				this.errorHandler, null, null, null, null, null);
	}

	/**
	 * Creates the JMS template.
	 *
	 * @param  connectionFactory   Connection factory.
	 * @param  destinationResolver Destination resolver.
	 * @param  messageConverter    Message converter.
	 * @return                     The JMS template.
	 */
	public JmsTemplate createJmsTemplate(
			final ConnectionFactory connectionFactory,
			final DestinationResolver destinationResolver,
			final MessageConverter messageConverter) {
		// Creates the JMS template.
		final JmsTemplate jmsTemplate = new JmsTemplate();
		// Sets the default configuration.
		if (destinationResolver != null) {
			jmsTemplate.setDestinationResolver(destinationResolver);
		}
		if (messageConverter != null) {
			jmsTemplate.setMessageConverter(messageConverter);
		}
		jmsTemplate.setConnectionFactory(connectionFactory);
		jmsTemplate.setSessionTransacted(true);
		jmsTemplate.setSessionAcknowledgeMode(AcknowledgeMode.AUTO.getMode());
		// Returns the configured JMS template.
		return jmsTemplate;
	}

	/**
	 * Creates the JMS template.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The JMS template.
	 */
	public JmsTemplate createJmsTemplate(
			final ConnectionFactory connectionFactory) {
		return this.createJmsTemplate(connectionFactory, this.destinationResolver, this.messageConverter);
	}

	/**
	 * Creates the JMS template.
	 *
	 * @param  connectionFactory   Connection factory.
	 * @param  destinationResolver Destination resolver.
	 * @param  messageConverter    Message converter.
	 * @return                     The JMS template.
	 */
	public JmsTemplate createJmsTopicTemplate(
			final ConnectionFactory connectionFactory,
			final DestinationResolver destinationResolver,
			final MessageConverter messageConverter) {
		// Creates the JMS template.
		final JmsTemplate jmsTemplate = this.createJmsTemplate(connectionFactory, destinationResolver, messageConverter);
		jmsTemplate.setPubSubDomain(true);
		// Returns the configured JMS template.
		return jmsTemplate;
	}

	/**
	 * Creates the JMS template.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The JMS template.
	 */
	public JmsTemplate createJmsTopicTemplate(
			final ConnectionFactory connectionFactory) {
		return this.createJmsTopicTemplate(connectionFactory, this.destinationResolver, this.messageConverter);
	}

}
