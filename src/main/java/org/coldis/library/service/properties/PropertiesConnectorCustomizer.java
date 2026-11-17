package org.coldis.library.service.properties;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Tomcat connector with request threads of its own, for the properties
 * endpoint ({@link PropertiesService}): the path by which a running service
 * takes a new address of a dependency (database, broker, another service). On
 * the main connector an update waits for the same request threads as the
 * service's traffic. When that traffic is stuck on the very dependency whose
 * address changed, every thread stays taken, the update never runs and the
 * service never recovers. This connector listens on the loopback address only,
 * so only processes of the service's own host or container (the endpoint
 * update scripts) reach it, and its threads are not taken by the service's
 * traffic. The main connector keeps serving the endpoint as before, and the
 * health check stays there, so it still reports when the service's own threads
 * run out.
 */
@Component
public class PropertiesConnectorCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesConnectorCustomizer.class);

	/**
	 * Property prefix.
	 */
	public static final String PROPERTY_PREFIX = "org.coldis.library.service.properties.connector";

	/**
	 * Whether the connector is added.
	 */
	private final Boolean enabled;

	/**
	 * Address the connector listens on.
	 */
	private final String address;

	/**
	 * Port the connector listens on (0 picks a free one).
	 */
	private final Integer port;

	/**
	 * Request threads of the connector.
	 */
	private final Integer maxThreads;

	/**
	 * Connector, once added.
	 */
	private Connector connector;

	/**
	 * Constructor.
	 *
	 * @param enabled    Whether the connector is added.
	 * @param address    Address the connector listens on.
	 * @param port       Port the connector listens on (0 picks a free one).
	 * @param maxThreads Request threads of the connector.
	 */
	public PropertiesConnectorCustomizer(
			@Value("${" + PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".enabled:true}") final Boolean enabled,
			@Value("${" + PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".address:127.0.0.1}") final String address,
			@Value("${" + PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".port:9091}") final Integer port,
			@Value("${" + PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".max-threads:2}") final Integer maxThreads) {
		this.enabled = enabled;
		this.address = address;
		this.port = port;
		this.maxThreads = maxThreads;
	}

	/**
	 * Creates a connector that listens on the given address and port, with the
	 * given request threads of its own.
	 *
	 * @param  address    Address the connector listens on.
	 * @param  port       Port the connector listens on (0 picks a free one).
	 * @param  maxThreads Request threads of the connector.
	 * @return            The connector.
	 */
	public static Connector createConnector(
			final String address,
			final Integer port,
			final Integer maxThreads) {
		final Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
		connector.setPort(port);
		final AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
		try {
			protocol.setAddress(InetAddress.getByName(address));
		}
		catch (final UnknownHostException exception) {
			throw new IllegalStateException("Invalid address for the properties connector (" + PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".address): "
					+ address + ".", exception);
		}
		protocol.setMaxThreads(maxThreads);
		protocol.setMinSpareThreads(1);
		return connector;
	}

	/**
	 * Gets the port the connector listens on, once the server started.
	 *
	 * @return The port, or null when the connector was not added.
	 */
	public Integer getLocalPort() {
		return (this.connector == null) ? null : this.connector.getLocalPort();
	}

	/**
	 * @see org.springframework.boot.web.server.WebServerFactoryCustomizer#customize(org.springframework.boot.web.server.WebServerFactory)
	 */
	@Override
	public void customize(
			final TomcatServletWebServerFactory factory) {
		if (Boolean.TRUE.equals(this.enabled) && (this.connector == null)) {
			this.connector = PropertiesConnectorCustomizer.createConnector(this.address, this.port, this.maxThreads);
			factory.addAdditionalTomcatConnectors(this.connector);
			PropertiesConnectorCustomizer.LOGGER.info("Serving the properties endpoint also on " + this.address + ":" + this.port + ", with " + this.maxThreads
					+ " request threads of its own (" + PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".enabled=false turns it off).");
		}
	}

}
