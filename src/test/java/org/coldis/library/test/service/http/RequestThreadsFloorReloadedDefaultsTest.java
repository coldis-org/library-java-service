package org.coldis.library.test.service.http;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.ServiceConfiguration;
import org.coldis.library.service.http.RequestThreadsFloorCustomizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Request-thread floor test, on a running server whose dependency loads the
 * library defaults again under another name. It starts a minimal Spring Boot
 * application of its own (Tomcat, the library configuration, the floor
 * customizer and the reload), outside the shared test context.
 */
@DisplayName("Request-thread floor on a running server with the library defaults loaded again")
public class RequestThreadsFloorReloadedDefaultsTest {

	/**
	 * Configuration that loads the library defaults again, without a name, as a
	 * library a service depends on may do. Spring processes it after the
	 * library's own configuration, so its copy takes precedence over the named
	 * library defaults.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	@PropertySource("classpath:service.properties")
	static class ReloadedLibraryDefaultsConfiguration {
	}

	/**
	 * Tests that the running server's cap, read from the copy, is raised to the
	 * floor.
	 */
	@Test
	@DisplayName("the running server's cap, read from the copy, is raised to the floor")
	public void testRunningServerCap() {
		final SpringApplication application = new SpringApplicationBuilder(PropertyPlaceholderAutoConfiguration.class,
				ServletWebServerFactoryAutoConfiguration.class, EmbeddedWebServerFactoryCustomizerAutoConfiguration.class, ServiceConfiguration.class,
				RequestThreadsFloorCustomizer.class, ReloadedLibraryDefaultsConfiguration.class).web(WebApplicationType.SERVLET).properties("server.port=0")
				.build();
		// Leaves the logging system, which the fork shares with the cached test contexts, as it is.
		application.setListeners(application.getListeners().stream().filter(listener -> !(listener instanceof LoggingApplicationListener)).toList());
		// Pins the default's input, whatever the machine exports, so the cap starts below the floor.
		try (ConfigurableApplicationContext context = application.run("--AVAILABLE_CPUS_5X=16")) {
			final ConfigurableEnvironment environment = context.getEnvironment();
			final Connector connector = ((TomcatWebServer) ((WebServerApplicationContext) context).getWebServer()).getTomcat().getConnector();
			Assertions.assertEquals("class path resource [service.properties]",
					environment.getPropertySources().stream()
							.filter(propertySource -> !ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource))
							.filter(propertySource -> propertySource.containsProperty(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY))
							.map(propertySource -> propertySource.getName()).findFirst().orElse(null));
			Assertions.assertEquals(16, environment.getProperty(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, Integer.class));
			Assertions.assertEquals(20, environment.getProperty(RequestThreadsFloorCustomizer.MAX_THREADS_FLOOR_PROPERTY, Integer.class));
			Assertions.assertEquals(20, ((AbstractProtocol<?>) connector.getProtocolHandler()).getMaxThreads());
		}
	}

}
