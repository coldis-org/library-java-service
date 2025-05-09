package org.coldis.library.test.service.jms;

import org.coldis.library.service.jms.ExtendedArtemisProperties;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.jms.artemis.ExtendedArtemisConfiguration;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;

/**
 * JMS configuration.
 */
@Configuration
@Import(value = { ExtendedArtemisConfiguration.class })
public class JmsConfiguration {

	/** JMS configuration helper. */
	@Autowired
	private JmsConfigurationHelper jmsConfigurationHelper;

	/**
	 * Creates the JMS connection factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  properties  JMS properties.
	 * @return             The JMS connection factory.
	 */
	@Bean
	@Primary
	@Qualifier("pooledJmsConnectionFactory")
	public ConnectionFactory pooledJmsConnectionFactory(
			final ListableBeanFactory beanFactory,
			final ExtendedArtemisProperties properties) {
		return this.jmsConfigurationHelper.createPooledJmsConnectionFactory(beanFactory, properties);
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The JMS container factory.
	 */
	@Bean
	@Primary
	public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
			final DefaultJmsListenerContainerFactoryConfigurer configurer,
			final ConnectionFactory connectionFactory) {
		return this.jmsConfigurationHelper.createJmsListenerContainerFactoryBuilder().configurer(configurer)
				.connectionFactory(ConnectionFactoryUnwrapper.unwrap(connectionFactory)).build();
	}

	/**
	 * Creates the JMS template.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The JMS template.
	 */
	@Bean
	@Primary
	public JmsTemplate jmsTemplate(
			final ConnectionFactory connectionFactory) {
		return this.jmsConfigurationHelper.createJmsTemplate(connectionFactory);
	}

}
