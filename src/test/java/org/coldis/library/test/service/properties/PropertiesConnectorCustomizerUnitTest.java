package org.coldis.library.test.service.properties;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.properties.PropertiesConnectorCustomizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

/**
 * Covers the connector the properties endpoint gets on its own: what it adds to
 * the server, and that it leaves the main connector as is.
 */
@DisplayName("PropertiesConnectorCustomizer — own connector for the properties endpoint")
public class PropertiesConnectorCustomizerUnitTest {

	/**
	 * The customizer adds one loopback connector with its own few threads.
	 */
	@Test
	@DisplayName("Adds a loopback connector with request threads of its own")
	public void testConnectorAdded() {
		final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
		new PropertiesConnectorCustomizer(true, "127.0.0.1", 9091, 2).customize(factory);
		Assertions.assertEquals(1, factory.getAdditionalTomcatConnectors().size());
		final Connector connector = factory.getAdditionalTomcatConnectors().get(0);
		final AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
		Assertions.assertEquals(9091, connector.getPort());
		Assertions.assertEquals("127.0.0.1", protocol.getAddress().getHostAddress());
		Assertions.assertEquals(2, protocol.getMaxThreads());
		Assertions.assertEquals(1, protocol.getMinSpareThreads());
		Assertions.assertNull(protocol.getExecutor());
	}

	/**
	 * The customizer adds nothing when it is off, and nothing more when the
	 * factory is customized again.
	 */
	@Test
	@DisplayName("Adds nothing when off, and a single connector when called twice")
	public void testConnectorAddedOnlyWhenEnabledAndOnce() {
		final TomcatServletWebServerFactory disabledFactory = new TomcatServletWebServerFactory();
		new PropertiesConnectorCustomizer(false, "127.0.0.1", 9091, 2).customize(disabledFactory);
		Assertions.assertTrue(disabledFactory.getAdditionalTomcatConnectors().isEmpty());
		final TomcatServletWebServerFactory enabledFactory = new TomcatServletWebServerFactory();
		final PropertiesConnectorCustomizer customizer = new PropertiesConnectorCustomizer(true, "127.0.0.1", 9091, 2);
		customizer.customize(enabledFactory);
		customizer.customize(enabledFactory);
		Assertions.assertEquals(1, enabledFactory.getAdditionalTomcatConnectors().size());
	}

	/**
	 * An address that is not one fails the start up, naming the property. A
	 * malformed IPv6 literal is refused without any name lookup.
	 */
	@Test
	@DisplayName("An invalid address fails naming the property")
	public void testInvalidAddressFails() {
		final IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class,
				() -> PropertiesConnectorCustomizer.createConnector("[invalid", 9091, 2));
		Assertions.assertTrue(exception.getMessage().contains(PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".address"));
	}

}
