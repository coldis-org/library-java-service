package org.coldis.library.service.jms;

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * Listener container factory that creates {@link ExtendedMessageListenerContainer}
 * instances tagged with the factory bean name and its JMS listener bulkhead
 * group. Built by {@link JmsListenerContainerFactoryBuilder}; behaves exactly
 * as {@link DefaultJmsListenerContainerFactory} otherwise.
 */
public class ExtendedJmsListenerContainerFactory extends DefaultJmsListenerContainerFactory implements BeanNameAware {

	/**
	 * Bean name of the factory (null when it is not a bean).
	 */
	private String beanName;

	/**
	 * Bulkhead group of the containers (null to resolve it from the properties).
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
	 * Creates the container already tagged: the listener adapter is created
	 * before the container is initialized.
	 *
	 * @see org.springframework.jms.config.DefaultJmsListenerContainerFactory#createContainerInstance()
	 */
	@Override
	protected DefaultMessageListenerContainer createContainerInstance() {
		final ExtendedMessageListenerContainer listenerContainer = new ExtendedMessageListenerContainer();
		listenerContainer.setFactoryBeanName(this.beanName);
		listenerContainer.setBulkheadGroup(this.bulkheadGroup);
		return listenerContainer;
	}

}
