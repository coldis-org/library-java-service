package org.coldis.library.service.jms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.support.converter.SimpleMessageConverter;
import org.springframework.stereotype.Component;

import jakarta.jms.Message;

/**
 * Default JMS message converter.
 */
@Primary
@Component
@ConditionalOnClass(value = Message.class)
@ConditionalOnProperty(
		name = "org.coldis.configuration.jms-message-converter-default-enabled",
		havingValue = "true",
		matchIfMissing = false
)
public class DefaultJmsMessageConverter extends SimpleMessageConverter {

}
