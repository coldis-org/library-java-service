package org.coldis.library.service.properties;

import java.util.Locale;
import java.util.regex.Pattern;

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
	 * Last segment of a property name whose value is an address (jdbcUrl, host, serviceEndpoint): logged
	 * as scheme, host, port and path. Matched at the end only, so supportApiKey is not an address.
	 */
	private static final Pattern ADDRESS_PROPERTY_NAME = Pattern.compile(".*(hosts?|ports?|urls?|uris?|address(es)?|endpoints?)");

	/**
	 * Characters that end the hosts and ports of a URL: a path, or parameters (? # and the ; of some
	 * JDBC drivers).
	 */
	private static final Pattern URL_ADDRESS_END = Pattern.compile("[/?#;]");

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
	 * Gets the length placeholder logged instead of a value.
	 *
	 * @param  value Value.
	 * @return       The length placeholder.
	 */
	private static String getLengthPlaceholder(
			final String value) {
		return "<" + value.length() + " characters>";
	}

	/**
	 * Whether a property holds an address, judged by the last segment of its name (after the last
	 * dot). The name is a path variable: a query such as ?fieldAccess=true never reaches it.
	 *
	 * @param  name Property name.
	 * @return      Whether the property holds an address.
	 */
	private static boolean isAddressProperty(
			final String name) {
		boolean isAddress = false;
		if (name != null) {
			final String lastSegment = name.substring(name.lastIndexOf('.') + 1);
			isAddress = PropertiesService.ADDRESS_PROPERTY_NAME.matcher(lastSegment.toLowerCase(Locale.ROOT)).matches();
		}
		return isAddress;
	}

	/**
	 * Gets the address part of a URL: its scheme, hosts and ports. The rest (path and parameters) is
	 * only counted, since a path can carry a token as well (webhook and bot addresses, signed links).
	 *
	 * @param  url URL (contains "://", and no user information).
	 * @return     The address part of the URL.
	 */
	private static String getUrlAddress(
			final String url) {
		final int schemeEnd = url.indexOf("://") + 3;
		final String afterScheme = url.substring(schemeEnd);
		final String hostsAndPorts = PropertiesService.URL_ADDRESS_END.split(afterScheme, 2)[0];
		final int hiddenLength = afterScheme.length() - hostsAndPorts.length();
		return url.substring(0, schemeEnd) + hostsAndPorts + (hiddenLength > 0 ? " (+" + hiddenLength + " characters hidden)" : "");
	}

	/**
	 * Gets the value as it may be logged. Values can be credentials, or carry them (a database URL
	 * with a password), and the log is kept: booleans and numbers are logged as they are, addresses
	 * (a property named after a host, port, URL, URI, address or endpoint) as their scheme, hosts and
	 * ports, and anything else, or anything with an @, by its length only.
	 *
	 * @param  name  Property name.
	 * @param  value Property value.
	 * @return       The value as it may be logged.
	 */
	public static String getLoggedValue(
			final String name,
			final Object value) {
		String loggedValue = null;
		if ((value == null) || (value instanceof Boolean) || (value instanceof Number)) {
			loggedValue = String.valueOf(value);
		}
		else {
			final String textValue = value.toString();
			final boolean isAddress = PropertiesService.isAddressProperty(name);
			// An @ means user information, or an unusual value: by its length only, wherever it is,
			// so a password with an unencoded / ? # or ; cannot be cut in two.
			if (isAddress && !textValue.contains("@") && textValue.contains("://")) {
				loggedValue = PropertiesService.getUrlAddress(textValue);
			}
			else if (isAddress && !textValue.contains("@")) {
				loggedValue = textValue;
			}
			else {
				loggedValue = PropertiesService.getLengthPlaceholder(textValue);
			}
		}
		return loggedValue;
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
			PropertiesService.LOGGER.info("Not setting property " + name + " in bean " + beanName + ", because value is null.");
		}
		else {
			// Tries getting the bean.
			final Object bean = this.applicationContext.getBean(beanName);

			// Validates it has @ConfigurationProperties annotation.
			final ConfigurationProperties annotation = bean.getClass().getAnnotation(ConfigurationProperties.class);
			if ((annotation == null) && !DataSource.class.isAssignableFrom(bean.getClass()) && !JmsPoolConnectionFactory.class.isAssignableFrom(bean.getClass())
					&& !ActiveMQConnectionFactory.class.isAssignableFrom(bean.getClass())) {
				throw new IntegrationException(new SimpleMessage("Bean " + beanName + " does not have @ConfigurationProperties annotation."));
			}

			// Sets the property.
			PropertiesService.LOGGER.info("Setting property " + name + " to " + PropertiesService.getLoggedValue(name, value) + " in bean " + beanName + ".");
			ReflectionHelper.setAttribute(bean, fieldAccess, name, value);
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
