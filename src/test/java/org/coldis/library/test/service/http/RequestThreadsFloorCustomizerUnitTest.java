package org.coldis.library.test.service.http;

import java.util.Map;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.coldis.library.service.ServiceConfiguration;
import org.coldis.library.service.http.RequestThreadsFloorCustomizer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Covers the request-thread floor on its own: how it changes a connector's cap,
 * and how it tells the library default cap from a cap the service sets itself.
 */
@DisplayName("RequestThreadsFloorCustomizer — floor for the default request-thread cap")
public class RequestThreadsFloorCustomizerUnitTest {

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
	 * Creates a connector with the given request-thread cap.
	 *
	 * @param  maxThreads Request-thread cap.
	 * @return            The connector.
	 */
	private Connector createConnector(
			final Integer maxThreads) {
		final Connector connector = new Connector("HTTP/1.1");
		((AbstractProtocol<?>) connector.getProtocolHandler()).setMaxThreads(maxThreads);
		return connector;
	}

	/**
	 * Gets the request-thread cap of a connector.
	 *
	 * @param  connector Connector.
	 * @return           The request-thread cap.
	 */
	private Integer getMaxThreads(
			final Connector connector) {
		return ((AbstractProtocol<?>) connector.getProtocolHandler()).getMaxThreads();
	}

	/**
	 * Creates the library defaults property source, holding the cap.
	 *
	 * @return The library defaults property source.
	 */
	private MapPropertySource createLibraryDefaults() {
		return new MapPropertySource(ServiceConfiguration.LIBRARY_DEFAULTS_PROPERTY_SOURCE,
				Map.of(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, "${AVAILABLE_CPUS_5X:16}"));
	}

	/**
	 * Runs the customizer on a Tomcat factory, then applies the connector
	 * customizers it registered to a connector with the given cap.
	 *
	 * @param  environment Environment.
	 * @param  maxThreads  Request-thread cap the connector starts with.
	 * @return             The connector, after the customizers ran.
	 */
	private Connector customizeConnector(
			final ConfigurableEnvironment environment,
			final Integer maxThreads) {
		final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
		new RequestThreadsFloorCustomizer(environment, 20).customize(factory);
		final Connector connector = this.createConnector(maxThreads);
		factory.getTomcatConnectorCustomizers().forEach(customizer -> customizer.customize(connector));
		return connector;
	}

	/** Tests that a cap below the floor is raised to it. */
	@Test
	@DisplayName("a cap below the floor is raised to the floor")
	public void testRaisesLowerCap() {
		final Connector connector = this.createConnector(5);
		RequestThreadsFloorCustomizer.applyFloor(connector, 20);
		Assertions.assertEquals(20, this.getMaxThreads(connector));
	}

	/** Tests that a cap above the floor is kept. */
	@Test
	@DisplayName("a cap above the floor is kept")
	public void testKeepsHigherCap() {
		final Connector connector = this.createConnector(50);
		RequestThreadsFloorCustomizer.applyFloor(connector, 20);
		Assertions.assertEquals(50, this.getMaxThreads(connector));
	}

	/** Tests that an empty floor leaves the cap as is. */
	@Test
	@DisplayName("an empty floor leaves the cap as is")
	public void testEmptyFloor() {
		final Connector connector = this.createConnector(5);
		RequestThreadsFloorCustomizer.applyFloor(connector, null);
		Assertions.assertEquals(5, this.getMaxThreads(connector));
	}

	/** Tests that a floor of 0 leaves the cap as is. */
	@Test
	@DisplayName("a floor of 0 leaves the cap as is")
	public void testFloorOff() {
		final Connector connector = this.createConnector(5);
		RequestThreadsFloorCustomizer.applyFloor(connector, 0);
		Assertions.assertEquals(5, this.getMaxThreads(connector));
	}

	/** Tests that a connector on an external executor is left as is. */
	@Test
	@DisplayName("a connector on an external executor is left as is")
	public void testExternalExecutor() {
		final Connector connector = this.createConnector(5);
		((AbstractProtocol<?>) connector.getProtocolHandler()).setExecutor(Runnable::run);
		RequestThreadsFloorCustomizer.applyFloor(connector, 20);
		// Tomcat reports -1 while an external executor is set, so the connector's own cap is read after removing it.
		((AbstractProtocol<?>) connector.getProtocolHandler()).setExecutor(null);
		Assertions.assertEquals(5, this.getMaxThreads(connector));
	}

	/** Tests that a cap only the library defaults hold counts as the default. */
	@Test
	@DisplayName("a cap from the library defaults is the default")
	public void testCapFromLibraryDefaults() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new MapPropertySource("applicationConfig", Map.of("other.property", "value")));
		propertySources.addLast(this.createLibraryDefaults());
		Assertions.assertTrue(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/**
	 * Tests that the library defaults loaded again under another name still count
	 * as the default. The copy takes precedence over the library's named source,
	 * so the cap Spring uses is read from the copy.
	 */
	@Test
	@DisplayName("a cap from the library defaults loaded again under another name is the default")
	public void testCapFromReloadedLibraryDefaults() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ServiceConfiguration.class,
				ReloadedLibraryDefaultsConfiguration.class)) {
			final MutablePropertySources propertySources = context.getEnvironment().getPropertySources();
			Assertions.assertNotEquals(ServiceConfiguration.LIBRARY_DEFAULTS_PROPERTY_SOURCE,
					propertySources.stream().filter(propertySource -> propertySource.containsProperty(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY))
							.map(propertySource -> propertySource.getName()).findFirst().orElse(null));
			Assertions.assertTrue(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
			Assertions.assertEquals(20, this.getMaxThreads(this.customizeConnector(context.getEnvironment(), 16)));
		}
	}

	/** Tests that the default's value, without the library defaults, is not the default. */
	@Test
	@DisplayName("the default's value without the library defaults is not the default")
	public void testDefaultValueWithoutLibraryDefaults() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new MapPropertySource("applicationConfig", Map.of(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, "${AVAILABLE_CPUS_5X:16}")));
		Assertions.assertFalse(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/** Tests that a cap the service sets to the default's own expression is still the service's. */
	@Test
	@DisplayName("a cap the service sets to the default's own expression is not the default")
	public void testCapSetToDefaultExpression() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new MapPropertySource("applicationConfig",
				Map.of(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, "${AVAILABLE_CPUS_5X:16}", "other.property", "value")));
		propertySources.addLast(this.createLibraryDefaults());
		Assertions.assertFalse(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/** Tests that a cap the service sets to the number the default resolves to is still the service's. */
	@Test
	@DisplayName("a cap the service sets to the number the default resolves to is not the default")
	public void testCapSetToDefaultResolvedValue() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new MapPropertySource("applicationConfig", Map.of(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, "16")));
		propertySources.addLast(this.createLibraryDefaults());
		Assertions.assertFalse(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/** Tests that a cap the service sets in its own properties is not the default. */
	@Test
	@DisplayName("a cap the service sets in its properties is not the default")
	public void testCapFromServiceProperties() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new MapPropertySource("applicationConfig", Map.of(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, "8")));
		propertySources.addLast(this.createLibraryDefaults());
		Assertions.assertFalse(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/** Tests that a cap the service sets as an environment variable is not the default. */
	@Test
	@DisplayName("a cap the service sets as SERVER_TOMCAT_THREADS_MAX is not the default")
	public void testCapFromEnvironmentVariable() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", Map.of("SERVER_TOMCAT_THREADS_MAX", "8")));
		propertySources.addLast(this.createLibraryDefaults());
		Assertions.assertFalse(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/** Tests that no source holding the cap is not the default. */
	@Test
	@DisplayName("no source holding the cap is not the default")
	public void testNoCap() {
		final MutablePropertySources propertySources = new MutablePropertySources();
		propertySources.addLast(new MapPropertySource("applicationConfig", Map.of("other.property", "value")));
		Assertions.assertFalse(RequestThreadsFloorCustomizer.isMaxThreadsFromLibraryDefaults(propertySources));
	}

	/** Tests that the customizer raises a default cap below the floor. */
	@Test
	@DisplayName("customize raises a default cap below the floor")
	public void testCustomizeRaisesDefaultCap() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addLast(this.createLibraryDefaults());
		Assertions.assertEquals(20, this.getMaxThreads(this.customizeConnector(environment, 16)));
	}

	/** Tests that the customizer keeps a cap the service sets itself, even below the floor. */
	@Test
	@DisplayName("customize keeps a cap the service sets itself, even below the floor")
	public void testCustomizeKeepsServiceCap() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("applicationConfig", Map.of(RequestThreadsFloorCustomizer.MAX_THREADS_PROPERTY, "8")));
		environment.getPropertySources().addLast(this.createLibraryDefaults());
		Assertions.assertEquals(8, this.getMaxThreads(this.customizeConnector(environment, 8)));
	}

}
