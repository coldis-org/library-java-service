package org.coldis.library.test.service.properties;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Properties;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.properties.PropertiesConnectorCustomizer;
import org.coldis.library.service.properties.PropertiesConnectorFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

/**
 * Covers the connector the properties endpoint gets on its own: what it adds to
 * the server, that it exists only when turned on, and which requests its filter
 * refuses.
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
		new PropertiesConnectorCustomizer("127.0.0.1", 9091, 2).customize(factory);
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
	 * The customizer adds nothing more when the factory is customized again.
	 */
	@Test
	@DisplayName("Adds a single connector when called twice")
	public void testConnectorAddedOnce() {
		final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
		final PropertiesConnectorCustomizer customizer = new PropertiesConnectorCustomizer("127.0.0.1", 9091, 2);
		customizer.customize(factory);
		customizer.customize(factory);
		Assertions.assertEquals(1, factory.getAdditionalTomcatConnectors().size());
	}

	/**
	 * Neither the customizer nor the filter exists while the connector is off,
	 * as it ships, so no request pays for the filter; both exist once it is on.
	 */
	@Test
	@DisplayName("Neither the connector nor its filter exists while off")
	public void testBeansOnlyWhenEnabled() {
		final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(PropertiesConnectorCustomizer.class,
				PropertiesConnectorFilter.class);
		contextRunner.run(context -> {
			Assertions.assertTrue(context.getBeansOfType(PropertiesConnectorCustomizer.class).isEmpty());
			Assertions.assertTrue(context.getBeansOfType(PropertiesConnectorFilter.class).isEmpty());
		});
		contextRunner.withPropertyValues(PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".enabled=false").run(context -> {
			Assertions.assertTrue(context.getBeansOfType(PropertiesConnectorCustomizer.class).isEmpty());
			Assertions.assertTrue(context.getBeansOfType(PropertiesConnectorFilter.class).isEmpty());
		});
		contextRunner.withPropertyValues(PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".enabled=true").run(context -> {
			Assertions.assertEquals(1, context.getBeansOfType(PropertiesConnectorCustomizer.class).size());
			Assertions.assertEquals(1, context.getBeansOfType(PropertiesConnectorFilter.class).size());
		});
	}

	/**
	 * Creates a request as Tomcat maps it.
	 *
	 * @param  localPort   Port the request arrived on.
	 * @param  requestUri  Request URI, as sent.
	 * @param  servletPath Servlet path, decoded and normalized.
	 * @return             The request.
	 */
	private static MockHttpServletRequest createRequest(
			final Integer localPort,
			final String requestUri,
			final String servletPath) {
		final MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
		request.setLocalPort(localPort);
		request.setServletPath(servletPath);
		return request;
	}

	/**
	 * On the properties connector, the filter refuses every path outside the
	 * properties endpoint, judged by the path Tomcat maps (so a ".." that leaves
	 * the endpoint is outside it), and lets the endpoint through. On another
	 * port, and while the connector is not bound, it lets everything through.
	 *
	 * @throws ServletException If the filter fails.
	 * @throws IOException      If the filter fails.
	 */
	@Test
	@DisplayName("The filter refuses paths outside /properties/ on the connector only")
	public void testFilter() throws ServletException, IOException {
		final PropertiesConnectorFilter filter = new PropertiesConnectorFilter(new PropertiesConnectorCustomizer("127.0.0.1", 9091, 2) {

			@Override
			public Integer getLocalPort() {
				return 9091;
			}

		});
		Assertions.assertFalse(filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/properties/string/bean/attribute",
				"/properties/string/bean/attribute")));
		Assertions.assertTrue(filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/health-check", "/health-check")));
		Assertions.assertTrue(filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/properties", "/properties")));
		Assertions.assertTrue(filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/properties-other", "/properties-other")));
		Assertions.assertTrue(filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/properties/../health-check", "/health-check")));
		Assertions.assertTrue(
				filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/properties/%2e%2e/health-check", "/health-check")));
		Assertions.assertFalse(filter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9090, "/health-check", "/health-check")));
		// A refused request gets 404 and never reaches the chain.
		final MockHttpServletResponse refusedResponse = new MockHttpServletResponse();
		final MockFilterChain refusedChain = new MockFilterChain();
		filter.doFilter(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/properties/../health-check", "/health-check"), refusedResponse,
				refusedChain);
		Assertions.assertEquals(404, refusedResponse.getStatus());
		Assertions.assertNull(refusedChain.getRequest());
		final MockFilterChain passedChain = new MockFilterChain();
		filter.doFilter(PropertiesConnectorCustomizerUnitTest.createRequest(9090, "/health-check", "/health-check"), new MockHttpServletResponse(),
				passedChain);
		Assertions.assertNotNull(passedChain.getRequest());
		// While the connector is not bound, nothing is refused.
		final PropertiesConnectorFilter unboundFilter = new PropertiesConnectorFilter(new PropertiesConnectorCustomizer("127.0.0.1", 9091, 2));
		Assertions.assertFalse(unboundFilter.isRefused(PropertiesConnectorCustomizerUnitTest.createRequest(9091, "/health-check", "/health-check")));
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

	/**
	 * The library ships the connector off, on the loopback address and the port
	 * the update scripts call: only a deployment that calls it turns it on.
	 *
	 * @throws IOException If the library defaults cannot be read.
	 */
	@Test
	@DisplayName("Ships the connector off, on the loopback address and port 9091")
	public void testShippedDefaults() throws IOException {
		final Properties libraryDefaults = PropertiesLoaderUtils.loadProperties(new ClassPathResource("service.properties"));
		Assertions.assertEquals("false", libraryDefaults.getProperty(PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".enabled"));
		Assertions.assertTrue(InetAddress.getByName(libraryDefaults.getProperty(PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".address")).isLoopbackAddress());
		Assertions.assertEquals("9091", libraryDefaults.getProperty(PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".port"));
	}

}
