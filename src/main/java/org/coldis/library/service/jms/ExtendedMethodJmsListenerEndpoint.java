package org.coldis.library.service.jms;

import java.util.Collection;
import java.util.function.Supplier;

import org.springframework.jms.config.MethodJmsListenerEndpoint;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Method listener endpoint that creates {@link ExtendedMessagingMessageListenerAdapter}
 * listeners, wired with the listener container, the meter registry and the
 * JMS listener bulkhead registry. Created for every {@code @JmsListener} by
 * {@link EnhancedJmsBootstrapConfiguration}.
 */
public class ExtendedMethodJmsListenerEndpoint extends MethodJmsListenerEndpoint {

	/**
	 * JMS converter properties.
	 */
	private final JmsConverterProperties jmsConverterProperties;

	/**
	 * Non-retriable exceptions.
	 */
	private final Collection<Class<?>> nonRetriableExceptions;

	/**
	 * Stale message filter service supplier (may supply null).
	 */
	private final Supplier<StaleMessageFilterService> staleMessageFilterServiceSupplier;

	/**
	 * Meter registry supplier (may supply null). Resolved on use.
	 */
	private final Supplier<MeterRegistry> meterRegistrySupplier;

	/**
	 * Bulkhead registry supplier (may supply null). Resolved on use.
	 */
	private final Supplier<JmsListenerBulkheadRegistry> bulkheadRegistrySupplier;

	/**
	 * Constructor.
	 *
	 * @param jmsConverterProperties            JMS converter properties.
	 * @param nonRetriableExceptions            Non-retriable exceptions.
	 * @param staleMessageFilterServiceSupplier Stale message filter service
	 *                                              supplier.
	 * @param meterRegistrySupplier             Meter registry supplier.
	 * @param bulkheadRegistrySupplier          Bulkhead registry supplier.
	 */
	public ExtendedMethodJmsListenerEndpoint(
			final JmsConverterProperties jmsConverterProperties,
			final Collection<Class<?>> nonRetriableExceptions,
			final Supplier<StaleMessageFilterService> staleMessageFilterServiceSupplier,
			final Supplier<MeterRegistry> meterRegistrySupplier,
			final Supplier<JmsListenerBulkheadRegistry> bulkheadRegistrySupplier) {
		super();
		this.jmsConverterProperties = jmsConverterProperties;
		this.nonRetriableExceptions = nonRetriableExceptions;
		this.staleMessageFilterServiceSupplier = staleMessageFilterServiceSupplier;
		this.meterRegistrySupplier = meterRegistrySupplier;
		this.bulkheadRegistrySupplier = bulkheadRegistrySupplier;
	}

	/**
	 * @see org.springframework.jms.config.MethodJmsListenerEndpoint#createMessageListenerInstance()
	 */
	@Override
	protected MessagingMessageListenerAdapter createMessageListenerInstance() {
		final ExtendedMessagingMessageListenerAdapter messageListener = new ExtendedMessagingMessageListenerAdapter(this.jmsConverterProperties,
				this.nonRetriableExceptions, (this.staleMessageFilterServiceSupplier == null ? null : this.staleMessageFilterServiceSupplier.get()));
		// Resolved on use: the registries must not be created this early.
		messageListener.setMeterRegistrySupplier(this.meterRegistrySupplier);
		messageListener.setBulkheadRegistrySupplier(this.bulkheadRegistrySupplier);
		return messageListener;
	}

	/**
	 * @see org.springframework.jms.config.MethodJmsListenerEndpoint#createMessageListener(org.springframework.jms.listener.MessageListenerContainer)
	 */
	@Override
	protected MessagingMessageListenerAdapter createMessageListener(
			final MessageListenerContainer container) {
		final MessagingMessageListenerAdapter messageListener = super.createMessageListener(container);
		if ((messageListener instanceof final ExtendedMessagingMessageListenerAdapter extendedMessageListener)
				&& (container instanceof final AbstractMessageListenerContainer listenerContainer)) {
			extendedMessageListener.setListenerContainer(listenerContainer);
		}
		return messageListener;
	}

}
