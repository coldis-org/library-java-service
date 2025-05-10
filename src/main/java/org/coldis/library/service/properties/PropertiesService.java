package org.coldis.library.service.properties;

import javax.sql.DataSource;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.model.SimpleMessage;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Properties service.
 */
@RestController
@RequestMapping("/properties")
public class PropertiesService implements ApplicationContextAware {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesService.class);

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
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	private void setProperty(
			final String beanName,
			final Boolean fieldAccess,
			final String name,
			final Object value,
			final Boolean ignoreEmptyValue) {

		if ((value == null) && ignoreEmptyValue) {
			PropertiesService.LOGGER.info("Property " + name + " is null and ignoreEmptyValue is set to true, so it will not be set.");
		}
		else {
			final String actualName = name;

			// Tries getting the bean.
			final Object bean = this.applicationContext.getBean(beanName);

			// Validates it has @ConfigurationProperties annotation.
			final ConfigurationProperties annotation = bean.getClass().getAnnotation(ConfigurationProperties.class);
			if ((annotation == null) && !DataSource.class.isAssignableFrom(bean.getClass()) && !JmsPoolConnectionFactory.class.isAssignableFrom(bean.getClass())
					&& !ActiveMQConnectionFactory.class.isAssignableFrom(bean.getClass())) {
				throw new IntegrationException(new SimpleMessage("Bean " + beanName + " does not have @ConfigurationProperties annotation."));
			}

			// Sets the property.
			LOGGER.info("Setting property " + actualName + " to " + value + " in bean " + beanName + ".");
			ReflectionHelper.setAttribute(bean, fieldAccess, actualName, value);
		}

	}

	/**
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	@RequestMapping(
			path = "string/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setStringProperty(
			@PathVariable
			final String beanName,
			@RequestParam(defaultValue = "false")
			final Boolean fieldAccess,
			@PathVariable
			final String name,
			@RequestBody(required = false)
			final String value,
			@RequestParam(defaultValue = "true")
			final Boolean ignoreEmptyValue) {
		this.setProperty(beanName, fieldAccess, name, value, ignoreEmptyValue);
	}

	/**
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	@RequestMapping(
			path = "integer/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setNumberProperty(
			@PathVariable
			final String beanName,
			@RequestParam(defaultValue = "false")
			final Boolean fieldAccess,
			@PathVariable
			final String name,
			@RequestBody(required = false)
			final Integer value,
			@RequestParam(defaultValue = "true")
			final Boolean ignoreEmptyValue) {
		this.setProperty(beanName, fieldAccess, name, value, ignoreEmptyValue);
	}

	/**
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	@RequestMapping(
			path = "long/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setNumberProperty(
			@PathVariable
			final String beanName,
			@RequestParam(defaultValue = "false")
			final Boolean fieldAccess,
			@PathVariable
			final String name,
			@RequestBody(required = false)
			final Long value,
			@RequestParam(defaultValue = "true")
			final Boolean ignoreEmptyValue) {
		this.setProperty(beanName, fieldAccess, name, value, ignoreEmptyValue);
	}

	/**
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	@RequestMapping(
			path = "float/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setNumberProperty(
			@PathVariable
			final String beanName,
			@RequestParam(defaultValue = "false")
			final Boolean fieldAccess,
			@PathVariable
			final String name,
			@RequestBody(required = false)
			final Float value,
			@RequestParam(defaultValue = "true")
			final Boolean ignoreEmptyValue) {
		this.setProperty(beanName, fieldAccess, name, value, ignoreEmptyValue);
	}

	/**
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	@RequestMapping(
			path = "double/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setNumberProperty(
			@PathVariable
			final String beanName,
			@RequestParam(defaultValue = "false")
			final Boolean fieldAccess,
			@PathVariable
			final String name,
			@RequestBody(required = false)
			final Double value,
			@RequestParam(defaultValue = "true")
			final Boolean ignoreEmptyValue) {
		this.setProperty(beanName, fieldAccess, name, value, ignoreEmptyValue);
	}

	/**
	 * Sets a bean configuration property.
	 *
	 * @param name  Property name.
	 * @param value Property value.
	 */
	@RequestMapping(
			path = "boolean/{beanName}/{name}",
			method = RequestMethod.PUT
	)
	public void setNumberProperty(
			@PathVariable
			final String beanName,
			@RequestParam(defaultValue = "false")
			final Boolean fieldAccess,
			@PathVariable
			final String name,
			@RequestBody(required = false)
			final Boolean value,
			@RequestParam(defaultValue = "true")
			final Boolean ignoreEmptyValue) {
		this.setProperty(beanName, fieldAccess, name, value, ignoreEmptyValue);
	}

}
