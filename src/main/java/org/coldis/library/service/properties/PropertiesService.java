package org.coldis.library.service.properties;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ObjectHelper;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.model.SimpleMessage;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.parameters.RequestBody;

/**
 * Properties service.
 */
@RestController
@RequestMapping("/properties")
public class PropertiesService implements ApplicationContextAware {

	/**
	 * Application context.
	 */
	private ApplicationContext applicationContext;

	/**
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(
			final ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * Gets the properties.
	 *
	 * @param  name Property name.
	 * @return      Property value.
	 */
	@RequestMapping(
			path = "/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setProperties(
			@PathVariable
			final String beanName,
			@PathVariable
			final String name,
			@RequestBody
			final Object value) {
		// Tries getting the bean.
		final Object bean = this.applicationContext.getBean(beanName);
		
		// Validates it has @ConfigurationProperties annotation.
		ConfigurationProperties annotation = bean.getClass().getAnnotation(ConfigurationProperties.class);
		if (annotation == null) {
			throw new IntegrationException(new SimpleMessage("Bean " + beanName + " does not have @ConfigurationProperties annotation."));
		}
		
		// Sets the property.
		ReflectionHelper.setAttribute(bean, name, value);

	}

}
