package org.coldis.library.test.service.jms;

import org.coldis.library.service.jms.ExtendedArtemisProperties;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jms.artemis.ExtendedArtemisConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.destination.DestinationResolver;
import org.springframework.stereotype.Component;

import jakarta.jms.ConnectionFactory;

/**
 * JMS configuration.
 */
@EnableJms
@Configuration
@Import(value = { ExtendedArtemisConfiguration.class })
public class JmsConfiguration {

	/**
	 * Message converter.
	 */
	@Autowired(required = false)
	private MessageConverter messageConverter;

	/**
	 * JMS destination resolver.
	 */
	@Autowired(required = false)
	private DestinationResolver destinationResolver;

	@Component
	public class TestArtemisProperties extends ExtendedArtemisProperties {

	}

	/**
	 * Creates the JMS connection factory.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  properties  JMS properties.
	 * @return             The JMS connection factory.
	 */
	@Bean
	public ConnectionFactory createJmsConnectionFactory(
			final ListableBeanFactory beanFactory,
			final ExtendedArtemisProperties properties) {
		return JmsConfigurationHelper.createJmsConnectionFactory(beanFactory, properties);
	}

	/**
	 * Creates the JMS container factory.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The JMS container factory.
	 */
	@Bean(name = "testJmsContainerFactory")
	public DefaultJmsListenerContainerFactory createJmsContainerFactory(
			final ConnectionFactory connectionFactory) {
		return JmsConfigurationHelper.createJmsContainerFactory(connectionFactory, this.destinationResolver, this.messageConverter, 3000L, 5D,
				1000L * 60L * 60L * 17L);
	}

	/**
	 * Creates the JMS template.
	 *
	 * @param  connectionFactory Connection factory.
	 * @return                   The JMS template.
	 */
	@Bean
	public JmsTemplate createJmsTemplate(
			final ConnectionFactory connectionFactory) {
		return JmsConfigurationHelper.createJmsTemplate(connectionFactory, this.destinationResolver, this.messageConverter);
	}

}
