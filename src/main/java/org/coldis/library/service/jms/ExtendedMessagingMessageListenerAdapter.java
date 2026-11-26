package org.coldis.library.service.jms;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.activemq.artemis.jms.client.ActiveMQDestination;
import org.apache.activemq.artemis.utils.CompositeAddress;
import org.coldis.library.model.RetriableIn;
import org.coldis.library.thread.ThreadMapContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.adapter.ListenerExecutionFailedException;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

/**
 * Context-aware messaging message listener adapter: async hops limit (with the
 * exceeded queue), stale message filter, non-retriable error drops and the JMS
 * listener bulkhead (a bound on concurrent handler executions, see
 * {@link JmsListenerBulkheadRegistry}). Created by the listener endpoint of
 * {@link EnhancedJmsBootstrapConfiguration} ({@link ExtendedMethodJmsListenerEndpoint}),
 * which also sets the listener container ({@link #setListenerContainer}), the
 * meter registry supplier ({@link #setMeterRegistrySupplier}) and the bulkhead
 * registry supplier ({@link #setBulkheadRegistrySupplier}). An adapter built
 * another way without them works, but exceeded topic messages get no original
 * queue (not retryable by the broker), drops are not counted and handlers are
 * not limited by the bulkhead.
 */
public class ExtendedMessagingMessageListenerAdapter extends MessagingMessageListenerAdapter {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(ExtendedMessagingMessageListenerAdapter.class);

	/**
	 * Current async hop parameter.
	 */
	public static final String CURRENT_ASYNC_HOP_PARAMETER = "asyncHop";

	/**
	 * Messages dropped after a non-retriable error (tags: destination, exception).
	 */
	public static final String DROPPED_MESSAGES_METRIC = "jms.listener.messages.dropped";

	/**
	 * Messages dropped by the stale message filter (tags: destination, source:
	 * local when the newer processing was recorded by this instance, pulled when
	 * it came from the shared store).
	 */
	public static final String STALE_DROPPED_MESSAGES_METRIC = "jms.stale.filter.dropped";

	/**
	 * Keyed messages checked by the stale message filter, once per delivery that
	 * is dropped or handed to the listener (tags: destination, outcome: dropped
	 * or processed).
	 */
	public static final String STALE_CHECKED_MESSAGES_METRIC = "jms.stale.filter.checked";

	/**
	 * JMS converter properties.
	 */
	private final JmsConverterProperties jmsConverterProperties;

	/**
	 * Non-retryable exceptions.
	 */
	private final Collection<Class<?>> nonRetriableExceptions;

	/**
	 * Stale message filter service (may be null when unavailable).
	 */
	private final StaleMessageFilterService staleMessageFilterService;

	/**
	 * Listener container (may be null when unavailable).
	 */
	private AbstractMessageListenerContainer listenerContainer;

	/**
	 * Meter registry supplier (may be null, or supply null, when unavailable).
	 * Resolved on use, not when the adapter is created.
	 */
	private Supplier<MeterRegistry> meterRegistrySupplier;

	/**
	 * Bulkhead registry supplier (may be null, or supply null, when unavailable).
	 * Resolved on the first message, when the listener container is set.
	 */
	private Supplier<JmsListenerBulkheadRegistry> bulkheadRegistrySupplier;

	/**
	 * Bean name of the container factory that set the listener up (may be null).
	 */
	private String factoryBeanName;

	/**
	 * Bulkhead group set on the container factory (null to resolve it from the
	 * properties).
	 */
	private String bulkheadGroup;

	/**
	 * If the bulkhead binding was resolved (it may be resolved to none).
	 */
	private volatile boolean isBulkheadBindingResolved;

	/**
	 * Bulkhead binding (null when the listener is not limited).
	 */
	private volatile JmsListenerBulkheadBinding bulkheadBinding;

	/**
	 * Default constructor.
	 */
	public ExtendedMessagingMessageListenerAdapter(final JmsConverterProperties jmsConverterProperties, final Collection<Class<?>> nonRetriableExceptions,
			final StaleMessageFilterService staleMessageFilterService) {
		super();
		this.jmsConverterProperties = jmsConverterProperties;
		this.nonRetriableExceptions = (nonRetriableExceptions == null ? Set.of()
				: nonRetriableExceptions.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet()));
		this.staleMessageFilterService = staleMessageFilterService;
	}

	/**
	 * Sets the listener container, used to resolve the queue the listener consumes
	 * from (the original queue of messages sent to the async hops exceeded queue).
	 * Set by the listener endpoint of {@link EnhancedJmsBootstrapConfiguration}
	 * after the adapter is created. When not set, queue listeners fall back to the
	 * message destination and topic subscriptions get no original queue (their
	 * exceeded messages cannot be retried by the broker).
	 *
	 * @param listenerContainer Listener container.
	 */
	public void setListenerContainer(
			final AbstractMessageListenerContainer listenerContainer) {
		this.listenerContainer = listenerContainer;
	}

	/**
	 * Sets the meter registry supplier, used to count dropped and checked messages
	 * ({@value #DROPPED_MESSAGES_METRIC}, {@value #STALE_DROPPED_MESSAGES_METRIC}
	 * and {@value #STALE_CHECKED_MESSAGES_METRIC}).
	 *
	 * @param meterRegistrySupplier Meter registry supplier.
	 */
	public void setMeterRegistrySupplier(
			final Supplier<MeterRegistry> meterRegistrySupplier) {
		this.meterRegistrySupplier = meterRegistrySupplier;
	}

	/**
	 * Sets the bulkhead registry supplier, used to bound concurrent handler
	 * executions (resolved on the first message).
	 *
	 * @param bulkheadRegistrySupplier Bulkhead registry supplier.
	 */
	public void setBulkheadRegistrySupplier(
			final Supplier<JmsListenerBulkheadRegistry> bulkheadRegistrySupplier) {
		this.bulkheadRegistrySupplier = bulkheadRegistrySupplier;
	}

	/**
	 * Gets the factoryBeanName.
	 *
	 * @return The factoryBeanName.
	 */
	public String getFactoryBeanName() {
		return this.factoryBeanName;
	}

	/**
	 * Sets the bean name of the container factory that set the listener up (set
	 * by {@link ExtendedJmsListenerContainerFactory}), used to resolve the
	 * listener's bulkhead group and reservation.
	 *
	 * @param factoryBeanName New factoryBeanName.
	 */
	public void setFactoryBeanName(
			final String factoryBeanName) {
		this.factoryBeanName = factoryBeanName;
	}

	/**
	 * Gets the bulkheadGroup.
	 *
	 * @return The bulkheadGroup.
	 */
	public String getBulkheadGroup() {
		return this.bulkheadGroup;
	}

	/**
	 * Sets the bulkhead group set on the container factory (set by
	 * {@link ExtendedJmsListenerContainerFactory}).
	 *
	 * @param bulkheadGroup New bulkheadGroup.
	 */
	public void setBulkheadGroup(
			final String bulkheadGroup) {
		this.bulkheadGroup = bulkheadGroup;
	}

	/**
	 * Gets the bulkhead binding of the listener, resolved once (on the first
	 * message, when the listener container is set) and cached, including when
	 * there is none. Two first messages at once may both resolve it, to the same
	 * bulkhead.
	 *
	 * @return The bulkhead binding, or null when the listener is not limited (no
	 *         registry or container, bulkhead disabled or group not limited).
	 */
	JmsListenerBulkheadBinding getBulkheadBinding() {
		if (!this.isBulkheadBindingResolved) {
			final JmsListenerBulkheadRegistry bulkheadRegistry = (this.bulkheadRegistrySupplier == null ? null : this.bulkheadRegistrySupplier.get());
			this.bulkheadBinding = ((bulkheadRegistry == null) || (this.listenerContainer == null) ? null : bulkheadRegistry.bind(this.listenerContainer, this.factoryBeanName,
					this.bulkheadGroup));
			this.isBulkheadBindingResolved = true;
			// Without the gate the listener still works, but its consumers wait for a
			// permit holding the message they received: make the fallback visible.
			if ((this.bulkheadBinding != null) && !(this.listenerContainer instanceof ExtendedMessageListenerContainer)) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.warn(
						"JMS listener of '{}' is limited by the bulkhead but its container ({}) has no gate: its consumers wait for a permit holding a received message. Build the container factory with JmsListenerContainerFactoryBuilder.",
						this.bulkheadBinding.getDestination(), this.listenerContainer.getClass().getName());
			}
		}
		return this.bulkheadBinding;
	}

	/**
	 * Gets the bulkhead admission the current message was received with (see
	 * {@link ExtendedMessageListenerContainer}).
	 *
	 * @return The admission, or null when the container has no bulkhead gate.
	 */
	private JmsListenerBulkheadAdmission getCurrentAdmission() {
		return (this.listenerContainer instanceof final ExtendedMessageListenerContainer extendedListenerContainer
				? extendedListenerContainer.getCurrentAdmission()
				: null);
	}

	/**
	 * If the listener container stopped (a bulkhead waiter then gives its message
	 * back to the broker).
	 *
	 * @return If the listener container stopped.
	 */
	private boolean isListenerContainerStopped() {
		return (this.listenerContainer != null) && !this.listenerContainer.isRunning();
	}

	/**
	 * Counts a message, when a meter registry is available.
	 *
	 * @param metricName Metric name.
	 * @param message    Message.
	 * @param tags       Extra tags (key, value pairs).
	 */
	private void countMessage(
			final String metricName,
			final Message message,
			final String... tags) {
		final MeterRegistry meterRegistry = (this.meterRegistrySupplier == null ? null : this.meterRegistrySupplier.get());
		if (meterRegistry != null) {
			String destination = "unknown";
			try {
				destination = Objects.toString(((ActiveMQDestination) message.getJMSDestination()).getAddress(), destination);
			}
			catch (final JMSException exception) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Counted message destination could not be read.", exception);
			}
			Counter.builder(metricName).tag("destination", destination).tags(tags).register(meterRegistry).increment();
		}
	}

	/**
	 * Resolves the broker queue the listener consumes from, from the container
	 * when available: the queue a point-to-point container listens to (also a
	 * fully qualified queue on a multicast address), or the subscription queue of
	 * a durable or shared subscription. Without a container (or its destination
	 * name), falls back to the message destination when it is a queue. The
	 * subscription queue is named with the container client id: a client id set
	 * only on the connection factory is not seen here, and the resolved name then
	 * does not match the broker queue.
	 *
	 * @param  destination Message destination.
	 * @return             The queue name, or null when it cannot be resolved (e.g.
	 *                     a non-durable non-shared subscription, whose queue is
	 *                     temporary).
	 */
	private String getConsumedQueueName(
			final ActiveMQDestination destination) {
		final boolean containerAvailable = (this.listenerContainer != null);
		String queueName = null;
		if (containerAvailable && !this.listenerContainer.isPubSubDomain() && (this.listenerContainer.getDestinationName() != null)) {
			queueName = CompositeAddress.extractQueueName(this.listenerContainer.getDestinationName());
		}
		else if (containerAvailable && this.listenerContainer.isPubSubDomain()) {
			if ((this.listenerContainer.getSubscriptionName() != null)
					&& (this.listenerContainer.isSubscriptionDurable() || this.listenerContainer.isSubscriptionShared())) {
				queueName = ActiveMQDestination.createQueueNameForSubscription(this.listenerContainer.isSubscriptionDurable(),
						this.listenerContainer.getClientId(), this.listenerContainer.getSubscriptionName()).toString();
			}
		}
		else if (destination.isQueue()) {
			queueName = CompositeAddress.extractQueueName(destination.getName());
		}
		return queueName;
	}

	/**
	 * Validates if the maximum number of async hops was exceeded.
	 *
	 * @throws JMSException If there is any problem accessing the message.
	 */
	private void validateAsyncHops(
			final Message message) throws JMSException {
		final Long maximumAsyncHops = this.jmsConverterProperties.getMaximumAsyncHops();
		final Long currentAsyncHop = JmsMessageHelper.getLongProperty(message, ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, 0L);
		if ((maximumAsyncHops > 0) && (currentAsyncHop > maximumAsyncHops)) {
			final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
			throw new JmsAsyncHopsExceededException(destination, currentAsyncHop);
		}
	}

	/**
	 * Resolves when the message was posted (wall-clock epoch millis): the
	 * explicit override property (which producers must also set in wall-clock
	 * epoch millis, e.g. {@code System.currentTimeMillis()}, never from the
	 * application clock), the scheduled delivery time (a delayed message only
	 * becomes current at its scheduled time) or the JMS timestamp.
	 *
	 * @return When the message was posted, or null when unknown (fail-open).
	 */
	private Long getStaleFilterPostedAt(
			final Message message) throws JMSException {
		Long postedAt = null;
		if (message.propertyExists(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY)) {
			postedAt = message.getLongProperty(JmsMessage.STALE_FILTER_POSTED_AT_PROPERTY);
		}
		else if (message.propertyExists(org.apache.activemq.artemis.api.core.Message.HDR_SCHEDULED_DELIVERY_TIME.toString())) {
			postedAt = message.getLongProperty(org.apache.activemq.artemis.api.core.Message.HDR_SCHEDULED_DELIVERY_TIME.toString());
		}
		else if (message.getJMSTimestamp() > 0) {
			postedAt = message.getJMSTimestamp();
		}
		return postedAt;
	}

	/**
	 * If the stale message filter is enabled and the message carries a stale
	 * filter key.
	 */
	private boolean isStaleFilterKeyed(
			final Message message) throws JMSException {
		return (this.staleMessageFilterService != null) && this.staleMessageFilterService.isEnabled()
				&& message.propertyExists(JmsMessage.STALE_FILTER_KEY_PROPERTY);
	}

	/**
	 * Checks if the message carries a stale filter key and a same-key message
	 * started successful processing after this one was posted (in which case the
	 * processing already reflected the state that originated this message and it
	 * can be dropped). Fails open on any error.
	 *
	 * @return Where the newer processing record came from, or null when the
	 *         message is not superseded.
	 */
	private StaleMessageFilterService.Source getSupersedingSource(
			final Message message) {
		StaleMessageFilterService.Source source = null;
		try {
			if (this.isStaleFilterKeyed(message)) {
				final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
				this.staleMessageFilterService.registerKeyedDestination(destination);
				final Long postedAt = this.getStaleFilterPostedAt(message);
				if (postedAt != null) {
					source = this.staleMessageFilterService.getNewerProcessingSource(destination,
							message.getStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY), postedAt);
				}
			}
		}
		catch (final JMSException exception) {
			ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Stale message filter check failed (processing the message).", exception);
		}
		return source;
	}

	/**
	 * Counts a keyed message the stale message filter let through to processing.
	 *
	 * @param message Message.
	 */
	private void countStaleFilterProcessed(
			final Message message) {
		try {
			if (this.isStaleFilterKeyed(message)) {
				this.countMessage(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC, message, "outcome", "processed");
			}
		}
		catch (final JMSException exception) {
			ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Stale message filter check could not be counted.", exception);
		}
	}

	/**
	 * Records that a message with a stale filter key started successful
	 * processing.
	 *
	 * @param processingStartTimestamp When the processing started (wall-clock
	 *                                     epoch millis).
	 */
	private void recordStaleFilterProcessing(
			final Message message,
			final long processingStartTimestamp) {
		if ((this.staleMessageFilterService != null) && this.staleMessageFilterService.isEnabled()) {
			try {
				if (message.propertyExists(JmsMessage.STALE_FILTER_KEY_PROPERTY)) {
					final String destination = ((ActiveMQDestination) message.getJMSDestination()).getAddress();
					this.staleMessageFilterService.recordProcessing(destination, message.getStringProperty(JmsMessage.STALE_FILTER_KEY_PROPERTY),
							processingStartTimestamp);
				}
			}
			catch (final JMSException exception) {
				ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Stale message filter processing record failed.", exception);
			}
		}
	}

	/**
	 * Drops (acknowledges without processing) a message superseded by a newer
	 * same-key processing, and counts it.
	 *
	 * @param message Message.
	 * @param source  Where the newer processing record came from.
	 */
	private void dropSupersededMessage(
			final Message message,
			final StaleMessageFilterService.Source source) {
		ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Dropping stale message superseded by a newer same-key processing.");
		this.countMessage(ExtendedMessagingMessageListenerAdapter.STALE_DROPPED_MESSAGES_METRIC, message, "source", source.getTag());
		this.countMessage(ExtendedMessagingMessageListenerAdapter.STALE_CHECKED_MESSAGES_METRIC, message, "outcome", "dropped");
	}

	/**
	 * @see org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter#onMessage(jakarta.jms.Message,
	 *      jakarta.jms.Session)
	 */
	@Override
	public void onMessage(
			final Message message,
			final Session session) throws JMSException {
		// Tries to process the message.
		try {

			// Drops (acknowledges without processing) messages superseded by a newer
			// same-key processing. Checked before the bulkhead: it takes no permit.
			final StaleMessageFilterService.Source supersedingSource = this.getSupersedingSource(message);
			if (supersedingSource != null) {
				this.dropSupersededMessage(message, supersedingSource);
			}
			else {

				// Validates async hops (takes no permit either).
				this.validateAsyncHops(message);

				// Takes a bulkhead permit (when the listener is limited): the one the
				// consumer took at the container's gate, or else one acquired now. Released
				// when the handler returns or throws, before the catch blocks below run and
				// before the container commits or rolls back the session. No transaction
				// exists yet: the listener method's transaction starts inside
				// super.onMessage.
				final JmsListenerBulkheadBinding actualBulkheadBinding = this.getBulkheadBinding();
				try (JmsListenerBulkheadPermit permit = (actualBulkheadBinding == null ? null
						: actualBulkheadBinding.acquire(message, this.getCurrentAdmission(), this::isListenerContainerStopped))) {

					// A message that waited for its permit may have been superseded meanwhile.
					final StaleMessageFilterService.Source waitedSupersedingSource = ((permit != null) && permit.hasWaited()
							? this.getSupersedingSource(message)
							: null);
					if (waitedSupersedingSource != null) {
						this.dropSupersededMessage(message, waitedSupersedingSource);
					}
					else {
						// Processes the message. The processing start is wall clock, like the
						// posted-at it is compared with (never the application clock): the
						// clock skew margin absorbs small divergences between hosts.
						this.countStaleFilterProcessed(message);
						final long processingStartTimestamp = StaleMessageFilterService.getCurrentTimestamp();
						super.onMessage(message, session);
						this.recordStaleFilterProcessing(message, processingStartTimestamp);
					}

				}

			}

		}
		// Sends messages with async hops exceeded to a new specific queue.
		// The original address and queue are what the broker retry operation (e.g. the
		// console "Retry") uses to move the message back; the hops restart on retry.
		catch (final JmsAsyncHopsExceededException exception) {
			final ActiveMQDestination destination = (ActiveMQDestination) message.getJMSDestination();
			final Message copiedMessage = JmsMessageHelper.copy(session, message);
			copiedMessage.setStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_ORIGINAL_ADDRESS.toString(), destination.getAddress());
			// Always overwritten: an original queue inherited from the consumed message must
			// not steer the retry (null makes the broker skip it).
			copiedMessage.setObjectProperty(org.apache.activemq.artemis.api.core.Message.HDR_ORIGINAL_QUEUE.toString(),
					this.getConsumedQueueName(destination));
			copiedMessage.setLongProperty(ExtendedMessagingMessageListenerAdapter.CURRENT_ASYNC_HOP_PARAMETER, 0L);
			try (MessageProducer producer = session.createProducer(session.createQueue("async-hops/exceeded"))) {
				producer.send(copiedMessage);
			}
			ExtendedMessagingMessageListenerAdapter.LOGGER.warn("{} Sending message '{}' to 'async-hops/exceeded' queue.", exception.getLocalizedMessage(),
					message.getJMSMessageID());
		}
		// Handles listener execution failed exceptions.
		catch (final ListenerExecutionFailedException exception) {
			final Throwable exceptionCause = exception.getCause();

			// If not a non-retriable exception.
			if (!this.nonRetriableExceptions.contains(exceptionCause.getClass())) {
				// Retries unless the cause is a retriable-aware exception without a retry-in
				// value (which is dropped below).
				if (!(exceptionCause instanceof final RetriableIn retriableException) || (retriableException.getRetryIn() != null)) {
					// Re-throws the exception to trigger a retry.
					throw exception;
				}
			}

			// Logs, counts and drops the message.
			this.countMessage(ExtendedMessagingMessageListenerAdapter.DROPPED_MESSAGES_METRIC, message, "exception",
					exceptionCause.getClass().getName());
			ExtendedMessagingMessageListenerAdapter.LOGGER
					.warn("Dropping message due to non-retriable error '" + exception.getClass() + ": " + exception.getLocalizedMessage()
							+ "' and nested error '" + exceptionCause.getClass() + ": " + exceptionCause.getLocalizedMessage() + "'.");
			ExtendedMessagingMessageListenerAdapter.LOGGER.debug("Dropped message due to non-retriable error '" + exception.getClass() + ": "
					+ exception.getLocalizedMessage() + "' and nested error '" + exceptionCause.getClass() + ": " + exceptionCause.getLocalizedMessage() + "'.",
					exception);
		}
		// Clears the thread context map.
		finally {
			ThreadMapContextHolder.clear();
		}
	}

}
