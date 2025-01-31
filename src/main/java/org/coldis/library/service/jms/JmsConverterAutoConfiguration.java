package org.coldis.library.service.jms;

import java.util.Set;

import org.apache.fury.BaseFury;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.Message;

/**
 * JMS converter auto configuration.
 */
@Configuration
public class JmsConverterAutoConfiguration {

	/**
	 * JMS converter properties.
	 */
	@Autowired
	private JmsConverterProperties jmsConverterProperties;

	/**
	 * Object mapper.
	 */
	@Autowired
	@Qualifier("thinJsonMapper")
	private ObjectMapper objectMapper;

	/**
	 * Optimized serializer.
	 */
	@Autowired
	@Qualifier(value = "javaOptimizedSerializer")
	private BaseFury optimizedSerializer;

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
	public EnhancedJmsMessageConverter enhancedJmsMessageConverter(
			@Value("#{'${org.coldis.library.service.jms.session-attributes:}'.split(',')}")
			final Set<String> sessionAttributes) {
		return new EnhancedJmsMessageConverter(this.jmsConverterProperties, this.objectMapper, null, sessionAttributes);
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
	public EnhancedJmsMessageConverter internalEnhancedJmsMessageConverter(
			@Value("#{'${org.coldis.library.service.jms.session-attributes:}'.split(',')}")
			final Set<String> sessionAttributes) {
		return new EnhancedJmsMessageConverter(this.jmsConverterProperties, this.objectMapper, this.optimizedSerializer, sessionAttributes);
	}

}
