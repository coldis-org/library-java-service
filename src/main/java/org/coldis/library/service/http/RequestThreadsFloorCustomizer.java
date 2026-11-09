package org.coldis.library.service.http;

import java.util.Objects;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.ServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.stereotype.Component;

/**
 * Floor for the library's default Tomcat request-thread cap
 * ({@code server.tomcat.threads.max} in {@code service.properties}). When the
 * cap comes from that default, a value below the floor is raised to it. A cap
 * the service sets itself, in its own properties or in its environment, is used
 * as is. It runs after Spring Boot's own Tomcat customizer (order 0), which
 * applies the cap, so it sees the value the cap resolved to.
 */
@Component
public class RequestThreadsFloorCustomizer implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RequestThreadsFloorCustomizer.class);

	/**
	 * Request-thread cap property.
	 */
	public static final String MAX_THREADS_PROPERTY = "server.tomcat.threads.max";

	/**
	 * Request-thread cap floor property.
	 */
	public static final String MAX_THREADS_FLOOR_PROPERTY = "org.coldis.library.service.tomcat.threads.max-floor";

	/**
	 * Environment.
	 */
	private final ConfigurableEnvironment environment;

	/**
	 * Minimum request-thread cap. Empty, 0 or less leaves the cap as is.
	 */
	private final Integer maxThreadsFloor;

	/**
	 * Constructor.
	 *
	 * @param environment     Environment.
	 * @param maxThreadsFloor Minimum request-thread cap. Empty, 0 or less
	 *                            leaves the cap as is.
	 */
	public RequestThreadsFloorCustomizer(
			final ConfigurableEnvironment environment,
			@Value("${" + RequestThreadsFloorCustomizer.MAX_THREADS_FLOOR_PROPERTY + ":0}") final Integer maxThreadsFloor) {
		this.environment = environment;
		this.maxThreadsFloor = maxThreadsFloor;
	}

	/**
	 * Gets the source that holds the request-thread cap, as Spring reads it: the
	 * source itself or, when Spring merged several files under one name into a
	 * composite source, the first nested source that holds the cap.
	 *
	 * @param  propertySource Property source, possibly composite. May be null.
	 * @return                The source itself when it is not composite, even if
	 *                            it does not hold the cap; for a composite, the
	 *                            first nested source that holds the cap, or null.
	 */
	private static PropertySource<?> getMaxThreadsHolder(
			final PropertySource<?> propertySource) {
		PropertySource<?> holder = propertySource;
		while (holder instanceof final CompositePropertySource compositePropertySource) {
			holder = compositePropertySource.getPropertySources().stream()
					.filter(nestedPropertySource -> nestedPropertySource.containsProperty(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY)).findFirst()
					.orElse(null);
		}
		return holder;
	}

	/**
	 * Whether the request-thread cap comes from the library defaults. Spring uses
	 * the first property source, in precedence order, that holds the property;
	 * the cap comes from the library defaults when that source is the library
	 * defaults or a copy of them. A library a service depends on may load
	 * {@code service.properties} again under another name, and Spring gives that
	 * copy precedence over the library defaults; several such copies are merged
	 * into one composite source. A copy is recognised by holding exactly the
	 * library defaults' content, as {@code @PropertySource} loads it, before
	 * placeholders resolve. So a cap the service sets itself is used as is, even
	 * when it is the default's own expression or resolves to the same number.
	 *
	 * @param  propertySources Property sources, in precedence order.
	 * @return                 Whether the cap comes from the library defaults.
	 */
	public static Boolean isMaxThreadsFromLibraryDefaults(
			final PropertySources propertySources) {
		final PropertySource<?> libraryDefaults = RequestThreadsFloorCustomizer
				.getMaxThreadsHolder(propertySources.get(ServiceConfiguration.LIBRARY_DEFAULTS_PROPERTY_SOURCE));
		final PropertySource<?> capSource = RequestThreadsFloorCustomizer.getMaxThreadsHolder(propertySources.stream()
				.filter(propertySource -> !ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource))
				.filter(propertySource -> propertySource.containsProperty(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY)).findFirst().orElse(null));
		return (libraryDefaults != null) && (capSource != null) && Objects.equals(capSource.getSource(), libraryDefaults.getSource());
	}

	/**
	 * Raises the connector's request-thread cap to the floor, when it is lower.
	 * A connector that runs on an external executor (such as virtual threads)
	 * has no cap of its own, so it is left as is.
	 *
	 * @param connector       Connector.
	 * @param maxThreadsFloor Minimum request-thread cap. Empty, 0 or less
	 *                            leaves the cap as is.
	 */
	public static void applyFloor(
			final Connector connector,
			final Integer maxThreadsFloor) {
		if ((maxThreadsFloor != null) && (connector.getProtocolHandler() instanceof final AbstractProtocol<?> protocol) && (protocol.getExecutor() == null)
				&& (protocol.getMaxThreads() < maxThreadsFloor)) {
			RequestThreadsFloorCustomizer.LOGGER.info("Raising the default Tomcat request-thread cap from " + protocol.getMaxThreads() + " to the floor "
					+ maxThreadsFloor + " (" + RequestThreadsFloorCustomizer.MAX_THREADS_FLOOR_PROPERTY + ", 0 turns it off).");
			protocol.setMaxThreads(maxThreadsFloor);
		}
	}

	/**
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	/**
	 * @see org.springframework.boot.web.server.WebServerFactoryCustomizer#customize(org.springframework.boot.web.server.WebServerFactory)
	 */
	@Override
	public void customize(
			final ConfigurableTomcatWebServerFactory factory) {
		if (RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(this.environment.getPropertySources())) {
			factory.addConnectorCustomizers(connector -> RequestThreadsFloorCustomizer.applyFloor(connector, this.maxThreadsFloor));
		}
		else {
			RequestThreadsFloorCustomizer.LOGGER
					.info(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY + " does not come from the library defaults, so the floor does not apply.");
		}
	}

}
