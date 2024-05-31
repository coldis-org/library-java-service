package org.springframework.boot.autoconfigure.jms.artemis;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.ExtendedArtemisProperties;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * Extendible Artemis connection factory.
 */
public class ExtensibleArtemisConnectionFactoryFactory extends ArtemisConnectionFactoryFactory {

	/**
	 * Default constructor.
	 *
	 * @param beanFactory Bean factory.
	 * @param properties  Properties.
	 */
	public ExtensibleArtemisConnectionFactoryFactory(final ListableBeanFactory beanFactory, final ExtendedArtemisProperties properties) {
		super(beanFactory, properties, properties);
	}

	/**
	 * @see org.springframework.boot.autoconfigure.jms.artemis.ArtemisConnectionFactoryFactory#createConnectionFactory(java.lang.Class)
	 */
	@Override
	public <T extends ActiveMQConnectionFactory> T createConnectionFactory(
			final Class<T> factoryClass) {
		return super.createConnectionFactory(factoryClass);
	}
}
