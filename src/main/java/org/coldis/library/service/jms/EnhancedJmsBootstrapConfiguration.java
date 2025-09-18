package org.coldis.library.service.jms;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.jms.annotation.JmsListenerAnnotationBeanPostProcessor;
import org.springframework.jms.config.JmsListenerConfigUtils;
import org.springframework.jms.config.MethodJmsListenerEndpoint;
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
			final JmsConverterProperties jmsConverterProperties) {
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
						return new ExtendedMessagingMessageListenerAdapter(jmsConverterProperties);
					}
				};
			}
		};
	}

}
