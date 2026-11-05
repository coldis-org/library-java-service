package org.coldis.library.test.service.http;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.ServiceConfiguration;
import org.coldis.library.service.http.RequestThreadsFloorCustomizer;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.embedded.TomcatWebServerFactoryCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Request-thread floor test, on the running server.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("Request-thread floor on the running server")
public class RequestThreadsFloorTest extends ContainerTestHelper {

	/**
	 * Web server application context.
	 */
	@Autowired
	private WebServerApplicationContext webServerApplicationContext;

	/**
	 * Environment.
	 */
	@Autowired
	private ConfigurableEnvironment environment;

	/**
	 * Request-thread floor customizer.
	 */
	@Autowired
	private RequestThreadsFloorCustomizer requestThreadsFloorCustomizer;

	/**
	 * Spring Boot's Tomcat customizer, which applies the configured cap.
	 */
	@Autowired
	private TomcatWebServerFactoryCustomizer tomcatWebServerFactoryCustomizer;

	/**
	 * Tests that the floor runs after Spring Boot's own customizer, so it sees the
	 * cap the property resolved to, whatever that cap is.
	 */
	@Test
	@DisplayName("the floor runs after Spring Boot's Tomcat customizer")
	public void testOrder() {
		Assertions.assertTrue(this.requestThreadsFloorCustomizer.getOrder() > this.tomcatWebServerFactoryCustomizer.getOrder());
	}

	/**
	 * Tests that the running server's cap is the library default cap, raised to
	 * the floor when it is lower (as without the CPU variables) and kept when it
	 * is higher.
	 */
	@Test
	@DisplayName("the running server's cap is the default cap, raised to the floor when lower")
	public void testRunningServerCap() {
		final Integer configuredMaxThreads = this.environment.getProperty(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, Integer.class);
		final Integer maxThreadsFloor = this.environment.getProperty(RequestThreadsFloorCustomizer.MAX_THREADS_FLOOR_PROPERTY, Integer.class);
		final Connector connector = ((TomcatWebServer) this.webServerApplicationContext.getWebServer()).getTomcat().getConnector();
		Assertions.assertTrue(this.environment.getPropertySources().contains(ServiceConfiguration.LIBRARY_DEFAULTS_PROPERTY_SOURCE));
		Assertions.assertTrue(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(this.environment.getPropertySources()));
		Assertions.assertEquals(20, maxThreadsFloor);
		Assertions.assertEquals(Math.max(configuredMaxThreads, maxThreadsFloor), ((AbstractProtocol<?>) connector.getProtocolHandler()).getMaxThreads());
	}

}
