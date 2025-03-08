package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.helper.ObjectHelper;
import org.coldis.library.thread.DynamicThreadPoolFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jms.AcknowledgeMode;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryFactory;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.boot.autoconfigure.jms.artemis.ExtensibleArtemisConnectionFactoryFactory;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import jakarta.jms.ConnectionFactory;

/**
 * JMS configuration helper.
 */
@Component
public class JmsConfigurationHelper {

	/** JMS global thread pool. */
	private ExecutorService globalThreadPool;

	/** JMS global scheduled thread pool. */
	private ScheduledExecutorService globalScheduledThreadPool;

	/** JMS global flow control thread pool. */
	private ExecutorService globalFlowControlThreadPool;

	/** JMS listener executor. */
	private ExecutorService jmsListenerExecutor;

	/** Back-off initial interval. */

	@Value("${org.coldis.library.service.jms.listener.max-messages-per-task:1}")
	private Integer maxMessagesPerTask;

	/** Back-off initial interval. */
	@Value("${org.coldis.library.service.jms.listener.backoff-initial-interval:5000}")
	private Long backoffInitialInterval;

	/** Back-off multiplier. */
	@Value("${org.coldis.library.service.jms.listener.backoff-multiplier:5}")
	private Double backoffMultiplier;

	/** Back-off max elapsed time. */
	@Value("${org.coldis.library.service.jms.listener.cache-level:}")
	private Integer cacheLevel;

	/** Back-off max elapsed time. */
	@Value("${org.coldis.library.service.jms.listener.backoff-max-elapsed-time:36000000}")
	private Long backoffMaxElapsedTime;

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

	/**
	 * Gets the JMS listener executor.
	 *
	 * @return
	 *
	 * @return The JMS listener executor.
	 */
	@Autowired
	public void setJmsGlobalThreadPools(
			@Value("${org.coldis.library.service.jms.global.executor.name:jms-global-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.global.executor.priority:5}")
			final Integer priority,
			@Value("${org.coldis.library.service.jms.global.executor.use-virtual-threads:true}")
			final Boolean virtual,
			@Value("${org.coldis.library.service.jms.global.executor.parallelism:}")
			final Integer parallelism,
			@Value("${org.coldis.library.service.jms.global.executor.parallelism-cpu-multiplier:}")
			final Double parallelismCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.min-runnable:}")
			final Integer minRunnable,
			@Value("${org.coldis.library.service.jms.global.executor.min-runnable-cpu-multiplier:}")
			final Double minRunnableCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.core-size:}")
			final Integer corePoolSize,
			@Value("${org.coldis.library.service.jms.global.executor.core-size-cpu-multiplier:5}")
			final Double corePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.max-size:}")
			final Integer maxPoolSize,
			@Value("${org.coldis.library.service.jms.global.executor.max-size-cpu-multiplier:}")
			final Double maxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.keep-alive-seconds:60}")
			final Integer keepAliveSeconds,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.name:jms-global-scheduled-thread}")
			final String scheduledName,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.priority:5}")
			final Integer scheduledPriority,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.use-virtual-threads:true}")
			final Boolean scheduledVirtual,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.core-size:}")
			final Integer scheduledCorePoolSize,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.core-size-cpu-multiplier:2}")
			final Double scheduledCorePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.name:jms-global-flow-control-thread}")
			final String flowControlName,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.priority:5}")
			final Integer flowControlPriority,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.use-virtual-threads:true}")
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
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.core-size-cpu-multiplier:2}")
			final Double flowControlCorePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.max-size:}")
			final Integer flowControlMaxPoolSize,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.max-size-cpu-multiplier:}")
			final Double flowControlMaxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.keep-alive-seconds:60}")
			final Integer flowControlKeepAliveSeconds

	) {
		this.globalThreadPool = (this.globalThreadPool == null ? (ExecutorService) new DynamicThreadPoolFactory().withName(name).withPriority(priority)
				.withVirtual(virtual).withParallelism(parallelism).withParallelismCpuMultiplier(parallelismCpuMultiplier).withMinRunnable(minRunnable)
				.withMinRunnableCpuMultiplier(minRunnableCpuMultiplier).withCorePoolSize(corePoolSize).withCorePoolSizeCpuMultiplier(corePoolSizeCpuMultiplier)
				.withMaxPoolSize(maxPoolSize).withMaxPoolSizeCpuMultiplier(maxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
				: this.globalThreadPool);
		this.globalScheduledThreadPool = (this.globalScheduledThreadPool == null
				? (ScheduledExecutorService) new DynamicThreadPoolFactory().withName(scheduledName).withScheduled(true).withPriority(scheduledPriority)
						.withVirtual(scheduledVirtual).withCorePoolSize(scheduledCorePoolSize).withCorePoolSizeCpuMultiplier(scheduledCorePoolSizeCpuMultiplier)
						.build()
				: this.globalScheduledThreadPool);
		this.globalFlowControlThreadPool = (this.globalFlowControlThreadPool == null
				? (ExecutorService) new DynamicThreadPoolFactory().withName(flowControlName).withPriority(flowControlPriority).withVirtual(flowControlVirtual)
						.withParallelism(flowControlParallelism).withParallelismCpuMultiplier(flowControlParallelismCpuMultiplier)
						.withMinRunnable(flowControlMinRunnable).withMinRunnableCpuMultiplier(flowControlMinRunnableCpuMultiplier)
						.withCorePoolSize(flowControlCorePoolSize).withCorePoolSizeCpuMultiplier(flowControlCorePoolSizeCpuMultiplier)
						.withMaxPoolSize(flowControlMaxPoolSize).withMaxPoolSizeCpuMultiplier(flowControlMaxPoolSizeCpuMultiplier)
						.withKeepAlive(Duration.ofSeconds(flowControlKeepAliveSeconds)).build()
				: this.globalFlowControlThreadPool);
		ActiveMQClient.injectPools(this.globalThreadPool, this.globalScheduledThreadPool, this.globalFlowControlThreadPool);
	}

	/**
	 * Gets the JMS listener executor.
	 *
	 * @return
	 *
	 * @return The JMS listener executor.
	 */
	@Autowired
	public void setJmsListenerExecutor(
			@Value("${org.coldis.library.service.jms.listener.executor.name:jms-listener-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.listener.executor.priority:4}")
			final Integer priority,
			@Value("${org.coldis.library.service.jms.listener.executor.virtual:true}")
			final Boolean virtual,
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
			@Value("${org.coldis.library.service.jms.listener.executor.core-size-cpu-multiplier:5}")
			final Double corePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.max-size:}")
			final Integer maxPoolSize,
			@Value("${org.coldis.library.service.jms.listener.executor.max-size-cpu-multiplier:}")
			final Double maxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.keep-alive-seconds:60}")
			final Integer keepAliveSeconds) {
		this.jmsListenerExecutor = (this.jmsListenerExecutor == null ? (ExecutorService) new DynamicThreadPoolFactory().withName(name).withPriority(priority)
				.withVirtual(virtual).withParallelism(parallelism).withParallelismCpuMultiplier(parallelismCpuMultiplier).withMinRunnable(minRunnable)
				.withMinRunnableCpuMultiplier(minRunnableCpuMultiplier).withCorePoolSize(corePoolSize).withCorePoolSizeCpuMultiplier(corePoolSizeCpuMultiplier)
				.withMaxPoolSize(maxPoolSize).withMaxPoolSizeCpuMultiplier(maxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
				: this.jmsListenerExecutor);
	}

	/**
	 * Merges the default properties with the actual properties.
	 *
	 * @param  properties Properties.
	 * @return            The merged properties.
	 */
	private ExtendedArtemisProperties mergeProperties(
			final ArtemisProperties properties) {
		final ExtendedArtemisProperties actualProperties = new ExtendedArtemisProperties();
		ObjectHelper.copyAttributes(this.defaultProperties, actualProperties, true, true, null, (
				getter,
				sourceValue,
				targetValue) -> sourceValue != null);
		ObjectHelper.copyAttributes(properties, actualProperties, true, true, null, (
				getter,
				sourceValue,
				targetValue) -> sourceValue != null);
		return actualProperties;
	}

	/**
	 * Sets the connection extended properties.
	 */
	private void setConnectionExtendedProperties(
			final ExtendedArtemisProperties actualProperties,
			final ActiveMQConnectionFactory connectionFactory) {
		connectionFactory.setConsumerWindowSize(
				actualProperties.getConsumerWindowSize() == null ? connectionFactory.getConsumerWindowSize() : actualProperties.getConsumerWindowSize());
		connectionFactory.setConsumerMaxRate(
				actualProperties.getConsumerMaxRate() == null ? connectionFactory.getConsumerMaxRate() : actualProperties.getConsumerMaxRate());
		connectionFactory.setProducerWindowSize(
				actualProperties.getProducerWindowSize() == null ? connectionFactory.getProducerWindowSize() : actualProperties.getProducerWindowSize());
		connectionFactory.setProducerMaxRate(
				actualProperties.getProducerMaxRate() == null ? connectionFactory.getProducerMaxRate() : actualProperties.getProducerMaxRate());
		connectionFactory.setConfirmationWindowSize(actualProperties.getConfirmationWindowSize() == null ? connectionFactory.getConfirmationWindowSize()
				: actualProperties.getConfirmationWindowSize());
		connectionFactory
				.setUseGlobalPools(actualProperties.getUseGlobalPools() == null ? connectionFactory.isUseGlobalPools() : actualProperties.getUseGlobalPools());
		connectionFactory.setCacheDestinations(
				actualProperties.getCacheDestinations() == null ? connectionFactory.isCacheDestinations() : actualProperties.getCacheDestinations());
		connectionFactory.setCacheLargeMessagesClient(actualProperties.getCacheLargeMessagesClient() == null ? connectionFactory.isCacheLargeMessagesClient()
				: actualProperties.getCacheLargeMessagesClient());
		connectionFactory.setCompressLargeMessage(
				actualProperties.getCompressLargeMessage() == null ? connectionFactory.isCompressLargeMessage() : actualProperties.getCompressLargeMessage());
		connectionFactory.setCompressionLevel(
				actualProperties.getCompressionLevel() == null ? connectionFactory.getCompressionLevel() : actualProperties.getCompressionLevel());
		connectionFactory.setBlockOnDurableSend(
				actualProperties.getBlockOnDurableSend() == null ? connectionFactory.isBlockOnDurableSend() : actualProperties.getBlockOnDurableSend());
		connectionFactory.setBlockOnNonDurableSend(actualProperties.getBlockOnNonDurableSend() == null ? connectionFactory.isBlockOnNonDurableSend()
				: actualProperties.getBlockOnNonDurableSend());
		connectionFactory.setClientFailureCheckPeriod(actualProperties.getClientFailureCheckPeriod() == null ? connectionFactory.getClientFailureCheckPeriod()
				: actualProperties.getClientFailureCheckPeriod());
		connectionFactory
				.setConnectionTTL(actualProperties.getConnectionTTL() == null ? connectionFactory.getConnectionTTL() : actualProperties.getConnectionTTL());
		connectionFactory.setCallTimeout(actualProperties.getCallTimeout() == null ? connectionFactory.getCallTimeout() : actualProperties.getCallTimeout());
		connectionFactory.setCallFailoverTimeout(
				actualProperties.getCallFailoverTimeout() == null ? connectionFactory.getCallFailoverTimeout() : actualProperties.getCallFailoverTimeout());
		connectionFactory.setReconnectAttempts(
				actualProperties.getReconnectAttempts() == null ? connectionFactory.getReconnectAttempts() : actualProperties.getReconnectAttempts());
	}

	/**
	 * Creates the JMS connection factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  properties  JMS properties.
	 * @return             The JMS connection factory.
	 */
	public ConnectionFactory createJmsConnectionFactory(
			final ListableBeanFactory beanFactory,
			final ArtemisProperties properties) {
		final ExtendedArtemisProperties actualProperties = this.mergeProperties(properties);
		final ActiveMQConnectionFactory connectionFactory = new ExtensibleArtemisConnectionFactoryFactory(beanFactory, actualProperties)
				.createConnectionFactory(ActiveMQConnectionFactory::new, ActiveMQConnectionFactory::new);
		this.setConnectionExtendedProperties(actualProperties, connectionFactory);
		// Returns the pooled connection factory;
		return new JmsPoolConnectionFactoryFactory(actualProperties.getPool()).createPooledConnectionFactory(connectionFactory);
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
		// Creates a new container factory.
		final DefaultJmsListenerContainerFactory jmsContainerFactory = new DefaultJmsListenerContainerFactory();
		// Sets the default configuration.
		if (taskExecutor != null) {
			jmsContainerFactory.setTaskExecutor(taskExecutor);
		}
		if (maxMessagesPerTask != null) {
			jmsContainerFactory.setMaxMessagesPerTask(maxMessagesPerTask);
		}
		if (destinationResolver != null) {
			jmsContainerFactory.setDestinationResolver(destinationResolver);
		}
		if (messageConverter != null) {
			jmsContainerFactory.setMessageConverter(messageConverter);
		}
		if (errorHandler != null) {
			jmsContainerFactory.setErrorHandler(errorHandler);
		}
		if (cacheLevel != null) {
			jmsContainerFactory.setCacheLevel(cacheLevel);
		}
		jmsContainerFactory.setConnectionFactory(connectionFactory);
		jmsContainerFactory.setSessionTransacted(true);
		jmsContainerFactory.setAutoStartup(true);
		jmsContainerFactory.setSessionAcknowledgeMode(AcknowledgeMode.AUTO.getMode());
		if ((backoffInitialInterval != null) && (backoffMultiplier != null) && (backoffMaxElapsedTime != null)) {
			final ExponentialBackOff backOff = new ExponentialBackOff(backoffInitialInterval, backoffMultiplier);
			backOff.setMaxElapsedTime(backoffMaxElapsedTime);
			jmsContainerFactory.setBackOff(backOff);
		}
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
	 * @param  backoffMaxInterval     Back-off max interval.
	 * @return                        The JMS container factory.
	 */
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
	public DefaultJmsListenerContainerFactory createJmsContainerFactory(
			final ConnectionFactory connectionFactory) {
		return this.createJmsContainerFactory(this.jmsListenerExecutor, connectionFactory, this.destinationResolver, this.messageConverter, this.errorHandler,
				this.cacheLevel, this.maxMessagesPerTask, this.backoffInitialInterval, this.backoffMultiplier, this.backoffMaxElapsedTime);
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
				messageConverter, cacheLevel, maxMessagesPerTask, backoffInitialInterval, backoffMultiplier, backoffMaxElapsedTime);
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
	public DefaultJmsListenerContainerFactory createJmsTopicContainerFactory(
			final ConnectionFactory connectionFactory) {
		return this.createJmsTopicContainerFactory(this.jmsListenerExecutor, connectionFactory, this.destinationResolver, this.messageConverter,
				this.errorHandler, this.cacheLevel, this.maxMessagesPerTask, this.backoffInitialInterval, this.backoffMultiplier, this.backoffMaxElapsedTime);
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
