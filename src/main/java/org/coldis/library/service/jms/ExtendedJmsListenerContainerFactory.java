package org.coldis.library.service.jms;

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * Listener container factory that tags the listeners it sets up
 * ({@link ExtendedMessagingMessageListenerAdapter}) with the factory bean name
 * and its JMS listener bulkhead group, used to resolve the listener's bulkhead
 * group and reservation (see {@link JmsListenerBulkheadRegistry}). Built by
 * {@link JmsListenerContainerFactoryBuilder}. The containers are plain
 * {@link DefaultMessageListenerContainer} instances (same class, same log
 * category); the factory behaves exactly as
 * {@link DefaultJmsListenerContainerFactory} otherwise.
 */
public class ExtendedJmsListenerContainerFactory extends DefaultJmsListenerContainerFactory implements BeanNameAware {

	/**
	 * Bean name of the factory (null when it is not a bean).
	 */
	private String beanName;

	/**
	 * Bulkhead group of the listeners (null to resolve it from the properties).
	 */
	private String bulkheadGroup;

	/**
	 * @see org.springframework.beans.factory.BeanNameAware#setBeanName(java.lang.String)
	 */
	@Override
	public void setBeanName(
			final String beanName) {
		this.beanName = beanName;
	}

	/**
	 * Gets the beanName.
	 *
	 * @return The beanName.
	 */
	public String getBeanName() {
		return this.beanName;
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
	 * Sets the bulkheadGroup.
	 *
	 * @param bulkheadGroup New bulkheadGroup.
	 */
	public void setBulkheadGroup(
			final String bulkheadGroup) {
		this.bulkheadGroup = bulkheadGroup;
	}

	/**
	 * Creates the container as the parent does, then tags its listener (set up
	 * by the endpoint) with the factory bean name and bulkhead group. The
	 * listener resolves its bulkhead on its first message, after this.
	 *
	 * @see org.springframework.jms.config.AbstractJmsListenerContainerFactory#createListenerContainer(org.springframework.jms.config.JmsListenerEndpoint)
	 */
	@Override
	public DefaultMessageListenerContainer createListenerContainer(
			final JmsListenerEndpoint endpoint) {
		final DefaultMessageListenerContainer listenerContainer = super.createListenerContainer(endpoint);
		if (listenerContainer.getMessageListener() instanceof final ExtendedMessagingMessageListenerAdapter messageListener) {
			messageListener.setFactoryBeanName(this.beanName);
			messageListener.setBulkheadGroup(this.bulkheadGroup);
		}
		return listenerContainer;
	}

}
