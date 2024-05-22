package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.concurrent.Executor;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.thread.PooledThreadExecutor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.util.backoff.ExponentialBackOff;

import jakarta.jms.ConnectionFactory;

/**
 * JMS configuration helper.
 */
@Component
public class JmsConfigurationHelper {

	/** JMS listener executor. */
	private PooledThreadExecutor jmsListenerExecutor;

	/** Back-off initial interval. */
	@Value("${org.coldis.library.service.jms.listener.max-messages-per-task:500}")
	private Integer maxMessagesPerTask;

	/** Back-off initial interval. */
	@Value("${org.coldis.library.service.jms.listener.backoff-initial-interval:5000}")
	private Long backoffInitialInterval;

	/** Back-off multiplier. */
	@Value("${org.coldis.library.service.jms.listener.backoff-multiplier:5}")
	private Double backoffMultiplier;

	/** Back-off max elapsed time. */
	@Value("${org.coldis.library.service.jms.listener.cache-level:3}")
	private Integer cacheLevel;

	/** Back-off max elapsed time. */
	@Value("${org.coldis.library.service.jms.listener.backoff-max-elapsed-time:36000000}")
	private Long backoffMaxElapsedTime;

	/** DTO message converter. */
	@Autowired(required = false)
	private MessageConverter messageConverter;

	/** JMS destination resolver. */
	@Autowired(required = false)
	private DestinationResolver destinationResolver;

	/**
	 * Gets the JMS listener executor.
	 *
	 * @return
	 *
	 * @return The JMS listener executor.
	 */
	@Autowired
	public PooledThreadExecutor jmsListenerExecutor(
			@Value("${org.coldis.library.service.jms.listener.executor.name:jms-listener-thread}")
			final String name,
			@Value("${org.coldis.library.service.jms.listener.executor.priority:4}")
			final Integer priority,
			@Value("${org.coldis.library.service.jms.listener.executor.use-virtual-threads:true}")
			final Boolean useVirtualThreads,
			@Value("${org.coldis.library.service.jms.listener.executor.core-size:}")
			final Integer corePoolSize,
			@Value("${org.coldis.library.service.jms.listener.executor.core-size-cpu-multiplier:10}")
			final Double corePoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.max-size:}")
			final Integer maxPoolSize,
			@Value("${org.coldis.library.service.jms.listener.executor.max-size-cpu-multiplier:50}")
			final Double maxPoolSizeCpuMultiplier,
			@Value("${org.coldis.library.service.jms.listener.executor.queue-size:100000}")
			final Integer queueSize,
			@Value("${org.coldis.library.service.jms.listener.executor.keep-alive-seconds:30}")
			final Integer keepAliveSeconds) {
		this.jmsListenerExecutor = (this.jmsListenerExecutor == null
				? new PooledThreadExecutor(name, priority, false, useVirtualThreads, corePoolSize, corePoolSizeCpuMultiplier, maxPoolSize,
						maxPoolSizeCpuMultiplier, queueSize, Duration.ofSeconds(keepAliveSeconds))
				: this.jmsListenerExecutor);
		return this.jmsListenerExecutor;
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
		final ActiveMQConnectionFactory connectionFactory = new ExtensibleArtemisConnectionFactoryFactory(beanFactory, properties)
				.createConnectionFactory(ActiveMQConnectionFactory.class);
		// If extended properties are used, also sets extended parameters.
		if (properties instanceof ExtendedArtemisProperties) {
			final ExtendedArtemisProperties extendedProperties = (ExtendedArtemisProperties) properties;
			connectionFactory
					.setClientFailureCheckPeriod(extendedProperties.getClientFailureCheckPeriod() == null ? connectionFactory.getClientFailureCheckPeriod()
							: extendedProperties.getClientFailureCheckPeriod());
			connectionFactory.setConnectionTTL(
					extendedProperties.getConnectionTTL() == null ? connectionFactory.getConnectionTTL() : extendedProperties.getConnectionTTL());
			connectionFactory
					.setCallTimeout(extendedProperties.getCallTimeout() == null ? connectionFactory.getCallTimeout() : extendedProperties.getCallTimeout());
			connectionFactory.setCallFailoverTimeout(extendedProperties.getCallFailoverTimeout() == null ? connectionFactory.getCallFailoverTimeout()
					: extendedProperties.getCallFailoverTimeout());
		}
		// Returns the pooled connection factory;
		return new JmsPoolConnectionFactoryFactory(properties.getPool()).createPooledConnectionFactory(connectionFactory);
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
		// Creates a new container factory.
		final DefaultJmsListenerContainerFactory jmsContainerFactory = new DefaultJmsListenerContainerFactory();
		// Sets the default configuration.
		if (taskExecutor != null) {
			jmsContainerFactory.setTaskExecutor(taskExecutor);
			jmsContainerFactory.setMaxMessagesPerTask(maxMessagesPerTask);
		}
		if (destinationResolver != null) {
			jmsContainerFactory.setDestinationResolver(destinationResolver);
		}
		if (messageConverter != null) {
			jmsContainerFactory.setMessageConverter(messageConverter);
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
			final ConnectionFactory connectionFactory) {
		return this.createJmsContainerFactory(this.jmsListenerExecutor, connectionFactory, this.destinationResolver, this.messageConverter, this.cacheLevel,
				this.maxMessagesPerTask, this.backoffInitialInterval, this.backoffMultiplier, this.backoffMaxElapsedTime);
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
			final ConnectionFactory connectionFactory) {
		return this.createJmsTopicContainerFactory(this.jmsListenerExecutor, connectionFactory, this.destinationResolver, this.messageConverter,
				this.cacheLevel, this.maxMessagesPerTask, this.backoffInitialInterval, this.backoffMultiplier, this.backoffMaxElapsedTime);
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
