package org.coldis.library.service.jms;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.util.ErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import jakarta.jms.ConnectionFactory;

/**
 * Fluent builder for creating a Spring DefaultJmsListenerContainerFactory.
 */
public final class JmsListenerContainerFactoryBuilder {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JmsListenerContainerFactoryBuilder.class);

	private DefaultJmsListenerContainerFactoryConfigurer configurer;
	private Boolean autoStartup;
	private Integer sessionAcknowledgeMode;
	private Boolean sessionTransacted;
	private Boolean topic;
	private Integer cacheLevel;
	private Integer maxMessagesPerTask;
	private Long backoffInitialInterval;
	private Double backoffMultiplier;
	private Long backoffMaxElapsedTime;
	private ConnectionFactory connectionFactory;
	private Executor taskExecutor;
	private DestinationResolver destinationResolver;
	private MessageConverter messageConverter;
	private ErrorHandler errorHandler;

	public JmsListenerContainerFactoryBuilder() {
	}

	/**
	 * Entry point for the builder.
	 */
	public static JmsListenerContainerFactoryBuilder builder() {
		return new JmsListenerContainerFactoryBuilder();
	}

	public JmsListenerContainerFactoryBuilder configurer(
			final DefaultJmsListenerContainerFactoryConfigurer configurer) {
		this.configurer = configurer;
		return this;
	}

	public JmsListenerContainerFactoryBuilder autoStartup(
			final boolean autoStartup) {
		this.autoStartup = autoStartup;
		return this;
	}

	public JmsListenerContainerFactoryBuilder sessionAcknowledgeMode(
			final int sessionAcknowledgeMode) {
		this.sessionAcknowledgeMode = sessionAcknowledgeMode;
		return this;
	}

	public JmsListenerContainerFactoryBuilder sessionTransacted(
			final boolean transacted) {
		this.sessionTransacted = transacted;
		return this;
	}

	/**
	 * Controls both durable and shared subscriptions when listening to topics.
	 */
	public JmsListenerContainerFactoryBuilder topic(
			final boolean topic) {
		this.topic = topic;
		return this;
	}

	public JmsListenerContainerFactoryBuilder cacheLevel(
			final int cacheLevel) {
		this.cacheLevel = cacheLevel;
		return this;
	}

	public JmsListenerContainerFactoryBuilder maxMessagesPerTask(
			final int maxMessages) {
		this.maxMessagesPerTask = maxMessages;
		return this;
	}

	/**
	 * Configures exponential backoff for restarts.
	 */
	public JmsListenerContainerFactoryBuilder backoff(
			final long initialInterval,
			final double multiplier,
			final long maxElapsedTime) {
		this.backoffInitialInterval = initialInterval;
		this.backoffMultiplier = multiplier;
		this.backoffMaxElapsedTime = maxElapsedTime;
		return this;
	}

	public JmsListenerContainerFactoryBuilder properties(
			final ExtendedArtemisProperties properties) {
		this.backoff(properties.getBackoffInitialInterval(), properties.getBackoffMultiplier(), properties.getBackoffMaxElapsedTime());
		this.cacheLevel(properties.getCacheLevel());
		this.maxMessagesPerTask(properties.getMaxMessagesPerTask());
		return this;
	}

	public JmsListenerContainerFactoryBuilder connectionFactory(
			final ConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
		return this;
	}

	public JmsListenerContainerFactoryBuilder taskExecutor(
			final Executor executor) {
		this.taskExecutor = executor;
		return this;
	}

	public JmsListenerContainerFactoryBuilder destinationResolver(
			final DestinationResolver resolver) {
		this.destinationResolver = resolver;
		return this;
	}

	public JmsListenerContainerFactoryBuilder messageConverter(
			final MessageConverter converter) {
		this.messageConverter = converter;
		return this;
	}

	public JmsListenerContainerFactoryBuilder errorHandler(
			final ErrorHandler handler) {
		this.errorHandler = handler;
		return this;
	}

	/**
	 * Builds the DefaultJmsListenerContainerFactory with the provided settings.
	 */
	public DefaultJmsListenerContainerFactory build() {
		final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

		// configurator + unwrapped factory
		if ((this.configurer != null)) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Applying configurer {} with unwrapped connectionFactory {}", this.configurer,
					this.connectionFactory);
			this.configurer.configure(factory, ConnectionFactoryUnwrapper.unwrap(this.connectionFactory));
		}
		else {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Applying configurer {} with unwrapped connectionFactory {}", this.configurer,
					this.connectionFactory);
			factory.setConnectionFactory(ConnectionFactoryUnwrapper.unwrap(this.connectionFactory));
		}

		// taskExecutor
		if (this.taskExecutor != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting taskExecutor → {}", this.taskExecutor);
			factory.setTaskExecutor(this.taskExecutor);
		}
		
		// maxMessagesPerTask
		if (this.maxMessagesPerTask != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting maxMessagesPerTask → {}", this.maxMessagesPerTask);
			factory.setMaxMessagesPerTask(this.maxMessagesPerTask);
		}

		// destinationResolver
		if (this.destinationResolver != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting destinationResolver → {}", this.destinationResolver);
			factory.setDestinationResolver(this.destinationResolver);
		}

		// messageConverter
		if (this.messageConverter != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting messageConverter → {}", this.messageConverter);
			factory.setMessageConverter(this.messageConverter);
		}

		// errorHandler
		if (this.errorHandler != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting errorHandler → {}", this.errorHandler);
			factory.setErrorHandler(this.errorHandler);
		}

		// cacheLevel
		if (this.cacheLevel != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting cacheLevel → {}", this.cacheLevel);
			factory.setCacheLevel(this.cacheLevel);
		}

		// topic → durable + shared
		if (this.topic != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting subscriptionDurable/shared → {}", this.topic);
			factory.setSubscriptionDurable(this.topic);
			factory.setSubscriptionShared(this.topic);
		}

		// transacted
		if (this.sessionTransacted != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting sessionTransacted → {}", this.sessionTransacted);
			factory.setSessionTransacted(this.sessionTransacted);
		}
		// sessionAcknowledgeMode
		else if (this.sessionAcknowledgeMode != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting sessionAcknowledgeMode → {}", this.sessionAcknowledgeMode);
			factory.setSessionAcknowledgeMode(this.sessionAcknowledgeMode);
		}

		// autoStartup
		if (this.autoStartup != null) {
			JmsListenerContainerFactoryBuilder.LOGGER.debug("Setting autoStartup → {}", this.autoStartup);
			factory.setAutoStartup(this.autoStartup);
		}

		// backoff (only if all three present)
		if ((this.backoffInitialInterval != null) && (this.backoffMultiplier != null) && (this.backoffMaxElapsedTime != null)) {

			JmsListenerContainerFactoryBuilder.LOGGER.debug("Configuring ExponentialBackOff: initialInterval={}, multiplier={}, maxElapsedTime={}",
					this.backoffInitialInterval, this.backoffMultiplier, this.backoffMaxElapsedTime);

			final ExponentialBackOff backOff = new ExponentialBackOff(this.backoffInitialInterval, this.backoffMultiplier);
			backOff.setMaxElapsedTime(this.backoffMaxElapsedTime);
			factory.setBackOff(backOff);
		}

		// Returns the factory.
		return factory;
	}
}
