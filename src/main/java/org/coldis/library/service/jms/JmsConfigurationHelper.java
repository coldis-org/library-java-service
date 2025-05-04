package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
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
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

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

	/**
	 * Gets the JMS listener executor.
	 *
	 * @return
	 *
	 * @return The JMS listener executor.
	 */
	@Autowired
	public void setJmsGlobalThreadPools(
			@Value("${org.coldis.library.service.jms.custom-pools:true}")
			final Boolean useCustomPools,
			@Value("${org.coldis.library.service.jms.global.executor.name:jms-global-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.global.executor.priority:5}")
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
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.priority:5}")
			final Integer scheduledPriority,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.virtual:false}")
			final Boolean scheduledVirtual,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.core-size:}")
			final Integer scheduledCorePoolSize,
			@Value("${org.coldis.library.service.jms.global.scheduled.executor.core-size-cpu-multiplier:1}")
			final Double scheduledCorePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.global.executor.name:jms-global-flow-control-thread}")
			final String flowControlName,
			@Value("${org.coldis.library.service.jms.global.flow-control.executor.priority:5}")
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
			this.globalThreadPool = (this.globalThreadPool == null
					? (ExecutorService) new DynamicThreadPoolFactory().withName(name).withPriority(priority).withVirtual(virtual).withParallelism(parallelism)
							.withParallelismCpuMultiplier(parallelismCpuMultiplier).withMinRunnable(minRunnable)
							.withMinRunnableCpuMultiplier(minRunnableCpuMultiplier).withCorePoolSize(corePoolSize)
							.withCorePoolSizeCpuMultiplier(corePoolSizeCpuMultiplier).withMaxPoolSize(maxPoolSize)
							.withMaxPoolSizeCpuMultiplier(maxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
					: this.globalThreadPool);
			this.globalScheduledThreadPool = (this.globalScheduledThreadPool == null
					? (ScheduledExecutorService) new DynamicThreadPoolFactory().withName(scheduledName).withScheduled(true).withPriority(scheduledPriority)
							.withVirtual(scheduledVirtual).withCorePoolSize(scheduledCorePoolSize)
							.withCorePoolSizeCpuMultiplier(scheduledCorePoolSizeCpuMultiplier).build()
					: this.globalScheduledThreadPool);
			this.globalFlowControlThreadPool = (this.globalFlowControlThreadPool == null ? (ExecutorService) new DynamicThreadPoolFactory()
					.withName(flowControlName).withPriority(flowControlPriority).withVirtual(flowControlVirtual).withParallelism(flowControlParallelism)
					.withParallelismCpuMultiplier(flowControlParallelismCpuMultiplier).withMinRunnable(flowControlMinRunnable)
					.withMinRunnableCpuMultiplier(flowControlMinRunnableCpuMultiplier).withCorePoolSize(flowControlCorePoolSize)
					.withCorePoolSizeCpuMultiplier(flowControlCorePoolSizeCpuMultiplier).withMaxPoolSize(flowControlMaxPoolSize)
					.withMaxPoolSizeCpuMultiplier(flowControlMaxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(flowControlKeepAliveSeconds)).build()
					: this.globalFlowControlThreadPool);
			ActiveMQClient.injectPools(this.globalThreadPool, this.globalScheduledThreadPool, this.globalFlowControlThreadPool);
		}
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
			@Value("${org.coldis.library.service.jms.custom-listener-executor:false}")
			final Boolean useCustomPool,
			@Value("${org.coldis.library.service.jms.listener.executor.name:jms-listener-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.listener.executor.priority:5}")
			final Integer priority,
			@Value("${org.coldis.library.service.jms.listener.executor.virtual:false}")
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
			@Value("${org.coldis.library.service.jms.listener.executor.core-size-cpu-multiplier:30}")
			final Double corePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.max-size:}")
			final Integer maxPoolSize,
			@Value("${org.coldis.library.service.jms.listener.executor.max-size-cpu-multiplier:30}")
			final Double maxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.keep-alive-seconds:60}")
			final Integer keepAliveSeconds) {
		if (useCustomPool) {
			this.jmsListenerExecutor = (this.jmsListenerExecutor == null
					? (ExecutorService) new DynamicThreadPoolFactory().withName(name).withPriority(priority).withVirtual(virtual).withParallelism(parallelism)
							.withParallelismCpuMultiplier(parallelismCpuMultiplier).withMinRunnable(minRunnable)
							.withMinRunnableCpuMultiplier(minRunnableCpuMultiplier).withCorePoolSize(corePoolSize)
							.withCorePoolSizeCpuMultiplier(corePoolSizeCpuMultiplier).withMaxPoolSize(maxPoolSize)
							.withMaxPoolSizeCpuMultiplier(maxPoolSizeCpuMultiplier).withKeepAlive(Duration.ofSeconds(keepAliveSeconds)).build()
					: this.jmsListenerExecutor);
		}
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
		return new JmsPoolConnectionFactoryFactory(actualProperties.getPool())
				.createPooledConnectionFactory(this.createNativeJmsConnectionFactory(beanFactory, actualProperties));
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
