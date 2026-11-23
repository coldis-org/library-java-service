package org.coldis.library.service.jms;

import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * Message listener container that knows the factory that created it and its
 * JMS listener bulkhead group (see {@link JmsListenerBulkheadRegistry}).
 * Created by {@link ExtendedJmsListenerContainerFactory}; behaves exactly as
 * {@link DefaultMessageListenerContainer} otherwise.
 */
public class ExtendedMessageListenerContainer extends DefaultMessageListenerContainer {

	/**
	 * Bean name of the container factory that created the container.
	 */
	private String factoryBeanName;

	/**
	 * Bulkhead group set on the container factory (null to resolve it from the
	 * properties).
	 */
	private String bulkheadGroup;

	/**
	 * Gets the factoryBeanName.
	 *
	 * @return The factoryBeanName.
	 */
	public String getFactoryBeanName() {
		return this.factoryBeanName;
	}

	/**
	 * Sets the factoryBeanName.
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
	 * Sets the bulkheadGroup.
	 *
	 * @param bulkheadGroup New bulkheadGroup.
	 */
	public void setBulkheadGroup(
			final String bulkheadGroup) {
		this.bulkheadGroup = bulkheadGroup;
	}

}
