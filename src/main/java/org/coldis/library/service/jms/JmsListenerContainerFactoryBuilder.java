package org.coldis.library.service.jms;

import java.util.concurrent.Executor;

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

	private DefaultJmsListenerContainerFactoryConfigurer configurer;
	private Executor taskExecutor;
	private ConnectionFactory connectionFactory;
	private DestinationResolver destinationResolver;
	private MessageConverter messageConverter;
	private ErrorHandler errorHandler;
	private Boolean autoStartup;
	private Boolean transacted;
	private Boolean topic;
	private Integer cacheLevel;
	private Integer maxMessagesPerTask;
	private Long backoffInitialInterval;
	private Double backoffMultiplier;
	private Long backoffMaxElapsedTime;

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

	public JmsListenerContainerFactoryBuilder taskExecutor(
			final Executor executor) {
		this.taskExecutor = executor;
		return this;
	}

	public JmsListenerContainerFactoryBuilder connectionFactory(
			final ConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
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

	public JmsListenerContainerFactoryBuilder autoStartup(
			final boolean autoStartup) {
		this.autoStartup = autoStartup;
		return this;
	}

	public JmsListenerContainerFactoryBuilder sessionTransacted(
			final boolean transacted) {
		this.transacted = transacted;
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

	/**
	 * Builds the DefaultJmsListenerContainerFactory with the provided settings.
	 */
	public DefaultJmsListenerContainerFactory build() {
		final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();

		if ((this.configurer != null) && (this.connectionFactory != null)) {
			this.configurer.configure(factory, ConnectionFactoryUnwrapper.unwrap(this.connectionFactory));
		}
		if (this.taskExecutor != null) {
			factory.setTaskExecutor(this.taskExecutor);
		}
		if (this.maxMessagesPerTask != null) {
			factory.setMaxMessagesPerTask(this.maxMessagesPerTask);
		}
		if (this.destinationResolver != null) {
			factory.setDestinationResolver(this.destinationResolver);
		}
		if (this.messageConverter != null) {
			factory.setMessageConverter(this.messageConverter);
		}
		if (this.errorHandler != null) {
			factory.setErrorHandler(this.errorHandler);
		}
		if (this.cacheLevel != null) {
			factory.setCacheLevel(this.cacheLevel);
		}
		if (this.topic != null) {
			factory.setSubscriptionDurable(this.topic);
			factory.setSubscriptionShared(this.topic);
		}
		if (this.connectionFactory != null) {
			factory.setConnectionFactory(this.connectionFactory);
		}
		if (this.transacted != null) {
			factory.setSessionTransacted(this.transacted);
		}
		if (this.autoStartup != null) {
			factory.setAutoStartup(this.autoStartup);
		}
		if ((this.backoffInitialInterval != null) && (this.backoffMultiplier != null) && (this.backoffMaxElapsedTime != null)) {
			final ExponentialBackOff backOff = new ExponentialBackOff(this.backoffInitialInterval, this.backoffMultiplier);
			backOff.setMaxElapsedTime(this.backoffMaxElapsedTime);
			factory.setBackOff(backOff);
		}

		return factory;
	}
}
