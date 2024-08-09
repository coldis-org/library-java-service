package org.coldis.library.service.jms;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.jms.Message;

/**
 * JMS converter auto configuration.
 */
@Configuration
public class JmsConverterAutoConfiguration {

	/**
	 * Enhanced JMS message converter.
	 *
	 * @return The enhanced JMS message converter.
	 */
	@Bean
	@Primary
	@Qualifier("enhancedJmsMessageConverter")
	@ConditionalOnClass(value = Message.class)
	@ConditionalOnProperty(
			name = "org.coldis.library.service.jms.message-converter-enhanced-enabled",
			havingValue = "true",
			matchIfMissing = true
	)
	public EnhancedJmsMessageConverter enhancedJmsMessageConverter() {
		return new EnhancedJmsMessageConverter(false);
	}

	/**
	 * JMS message converter.
	 *
	 * @return The JMS message converter.
	 */
	@Bean
	@Qualifier("internalEnhancedJmsMessageConverter")
	@ConditionalOnClass(value = Message.class)
	@ConditionalOnProperty(
			name = "org.coldis.library.service.jms.message-converter-enhanced-enabled",
			havingValue = "true",
			matchIfMissing = true
	)
	public EnhancedJmsMessageConverter internalEnhancedJmsMessageConverter() {
		return new EnhancedJmsMessageConverter(true);
	}

}
