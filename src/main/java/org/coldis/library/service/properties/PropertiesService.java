package org.coldis.library.service.properties;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.sql.DataSource;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.ReflectionHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.jms.ArtemisBrokerAddressChange;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
	 * Moves the live connections of an Artemis factory whose broker address
	 * changed, or null for none.
	 */
	private ArtemisBrokerAddressChange artemisBrokerAddressChange;

	/**
	 * Sets what moves the live connections of an Artemis factory whose broker
	 * address changed.
	 *
	 * @param artemisBrokerAddressChange Broker address change.
	 */
	@Autowired(required = false)
	public void setArtemisBrokerAddressChange(
			final ArtemisBrokerAddressChange artemisBrokerAddressChange) {
		this.artemisBrokerAddressChange = artemisBrokerAddressChange;
	}

	/**
	 * Whether the current update came from the service's own host or container:
	 * a request that arrived on a loopback address, judged by the local address
	 * of the connection, which no forwarded header changes. A call outside a
	 * request is not taken as local.
	 *
	 * @return Whether the update is local.
	 */
	private static Boolean isLocalUpdate() {
		Boolean isLocal = false;
		if (RequestContextHolder.getRequestAttributes() instanceof final ServletRequestAttributes requestAttributes) {
			try {
				final String localAddress = requestAttributes.getRequest().getLocalAddr();
				isLocal = (localAddress != null) && InetAddress.getByName(localAddress).isLoopbackAddress();
			}
			catch (final UnknownHostException exception) {
				isLocal = false;
			}
		}
		return isLocal;
	}

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

			// Notes the broker address of an Artemis connection factory before the change.
			final ArtemisBrokerAddressChange.Snapshot brokerAddress = (this.artemisBrokerAddressChange == null) ? null
					: this.artemisBrokerAddressChange.snapshot(bean);

			// Sets the property.
			PropertiesService.LOGGER.info("Setting property " + name + " to " + PropertiesLogHelper.getLoggedValue(name, value) + " in bean " + beanName + ".");
			ReflectionHelper.setAttribute(bean, fieldAccess, name, value);

			// Moves the live connections to a broker address that really changed, when the update
			// came from the service's own host. The same address sent again (as after a restart,
			// which clears the update scripts' state) leaves them alone.
			if (brokerAddress != null) {
				this.artemisBrokerAddressChange.moveConnectionsIfChanged(beanName, brokerAddress, PropertiesService.isLocalUpdate());
			}
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
