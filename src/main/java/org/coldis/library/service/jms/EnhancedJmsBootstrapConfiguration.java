package org.coldis.library.service.jms;

import java.util.List;

import org.apache.commons.lang3.ClassUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor;
import org.springframework.jms.config.JmsListenerConfigUtils;
import org.springframework.jms.config.MethodJmsListenerEndpoint;
import org.springframework.jms.listener.AbstractMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.jms.listener.adapter.MessagingMessageListenerAdapter;


/**
 * JMS bootstrap enhanced configuration.
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class EnhancedJmsBootstrapConfiguration implements BeanDefinitionRegistryPostProcessor {

	/**
	 * @see org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor#postProcessBeanDefinitionRegistry(org.springframework.beans.factory.support.BeanDefinitionRegistry)
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(
			final BeanDefinitionRegistry registry) throws BeansException {
		registry.removeBeanDefinition(JmsListenerConfigUtils.JMS_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME);
		registry.registerBeanDefinition(JmsListenerConfigUtils.JMS_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME,
				registry.getBeanDefinition("enhancedJmsListenerAnnotationProcessor"));
		registry.removeBeanDefinition("enhancedJmsListenerAnnotationProcessor");
	}

	/**
	 * @see org.springframework.beans.factory.config.BeanFactoryPostProcessor#postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory)
	 */
	@Bean
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public JmsListenerAnnotationBeanPostProcessor enhancedJmsListenerAnnotationProcessor(
			final JmsConverterProperties jmsConverterProperties,
			final ObjectProvider<StaleMessageFilterService> staleMessageFilterServiceProvider,
			@Value(value = "#{'${org.coldis.library.service.jms.non-retriable-exceptions:}'.split(',')}")
			List<String> nonRetriableExceptionsNames) {
		return new JmsListenerAnnotationBeanPostProcessor() {

			/**
			 * @see org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor#createMethodJmsListenerEndpoint()
			 */
			@Override
			protected MethodJmsListenerEndpoint createMethodJmsListenerEndpoint() {
				return new MethodJmsListenerEndpoint() {


					/**
					 * @see org.springframework.jms.config.MethodJmsListenerEndpoint#createMessageListenerInstance()
					 */
					@Override
					protected MessagingMessageListenerAdapter createMessageListenerInstance() {
						List<Class<?>> nonRetriableExceptions = ClassUtils.convertClassNamesToClasses(nonRetriableExceptionsNames);
						return new ExtendedMessagingMessageListenerAdapter(jmsConverterProperties, nonRetriableExceptions,
								staleMessageFilterServiceProvider.getIfAvailable());
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
				};
			}
		};
	}

}
