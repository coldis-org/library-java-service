package org.coldis.library.test.service.jms;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.coldis.library.service.jms.MeteredJmsPoolConnectionFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryFactory;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;

import jakarta.jms.ConnectionFactory;

/**
 * Makes sure {@link MeteredJmsPoolConnectionFactory#create} configures the pool exactly like Spring
 * Boot's {@link JmsPoolConnectionFactoryFactory}.
 *
 * The metered factory has to apply the pool settings itself, because Boot's factory instantiates
 * {@link JmsPoolConnectionFactory} directly and leaves no way to substitute the subclass. That
 * duplication is the fragile part of the change: a Spring Boot upgrade that starts honouring a new
 * pool property would be silently dropped on the metered path, quietly changing pool behaviour in
 * every service.
 *
 * The comparison is reflective on purpose. Asserting the eight known attributes would only prove
 * today's code copies today's attributes; walking every public getter means an attribute Boot starts
 * setting in the future also has to be handled here, or this test fails.
 */
@DisplayName("Metered JMS pool connection factory")
public class MeteredJmsPoolConnectionFactoryUnitTest {

	/**
	 * Getters that cannot participate in the comparison: the pooled connection factory itself is a
	 * distinct instance per factory, and the connection count touches the live pool.
	 */
	private static final Set<String> IGNORED_GETTERS = Set.of("getConnectionFactory", "getNumConnections", "getClass");

	/**
	 * Pool properties with every value set away from its default, so an attribute that only one of the
	 * two paths applies shows up as a difference instead of matching by coincidence.
	 *
	 * @return Pool properties.
	 */
	private JmsPoolConnectionFactoryProperties distinctiveProperties() {
		final JmsPoolConnectionFactoryProperties poolProperties = new JmsPoolConnectionFactoryProperties();
		poolProperties.setEnabled(true);
		poolProperties.setBlockIfFull(false);
		poolProperties.setBlockIfFullTimeout(Duration.ofMillis(7919));
		poolProperties.setIdleTimeout(Duration.ofMillis(9377));
		poolProperties.setMaxConnections(13);
		poolProperties.setMaxSessionsPerConnection(457);
		poolProperties.setTimeBetweenExpirationCheck(Duration.ofMillis(4133));
		poolProperties.setUseAnonymousProducers(false);
		return poolProperties;
	}

	/**
	 * Public no-argument getters declared by the pooled connection factory.
	 *
	 * @return Getters to compare.
	 */
	private List<Method> comparableGetters() {
		return Arrays.stream(JmsPoolConnectionFactory.class.getMethods())
				.filter(method -> (method.getParameterCount() == 0) && !method.getReturnType().equals(void.class))
				.filter(method -> method.getName().startsWith("get") || method.getName().startsWith("is"))
				.filter(method -> !MeteredJmsPoolConnectionFactoryUnitTest.IGNORED_GETTERS.contains(method.getName())).toList();
	}

	/**
	 * Every pool attribute must match what Spring Boot's factory produces from the same properties.
	 */
	@Test
	@DisplayName("Every pool attribute matches Spring Boot's factory for the same properties")
	public void testPoolConfigurationMatchesSpringBoot() throws Exception {

		// Builds both factories from the same properties and the same underlying connection factory.
		final JmsPoolConnectionFactoryProperties poolProperties = this.distinctiveProperties();
		final ConnectionFactory targetConnectionFactory = new JmsPoolConnectionFactory();
		final JmsPoolConnectionFactory expected = new JmsPoolConnectionFactoryFactory(poolProperties)
				.createPooledConnectionFactory(targetConnectionFactory);
		final JmsPoolConnectionFactory actual = MeteredJmsPoolConnectionFactory.create(null, poolProperties, targetConnectionFactory);

		// Makes sure the comparison is actually looking at something.
		final List<Method> getters = this.comparableGetters();
		Assertions.assertFalse(getters.isEmpty(), "No getters found to compare.");

		// Every attribute must be identical on both paths.
		for (final Method getter : getters) {
			Assertions.assertEquals(getter.invoke(expected), getter.invoke(actual),
					"Pool attribute '" + getter.getName() + "' differs from what JmsPoolConnectionFactoryFactory produces. "
							+ "If Spring Boot started applying a new pool property, apply it in MeteredJmsPoolConnectionFactory.create too.");
		}

		// Guards against the properties above silently drifting back to the defaults, which would make
		// the comparison pass without exercising anything.
		Assertions.assertEquals(13, actual.getMaxConnections());
		Assertions.assertEquals(457, actual.getMaxSessionsPerConnection());
	}

	/**
	 * A null meter registry must be tolerated, since metrics are optional.
	 */
	@Test
	@DisplayName("A missing meter registry is tolerated")
	public void testWithoutMeterRegistry() {
		final MeteredJmsPoolConnectionFactory pooledConnectionFactory = MeteredJmsPoolConnectionFactory.create(null, this.distinctiveProperties(),
				new JmsPoolConnectionFactory());
		pooledConnectionFactory.setBeanName("testJmsConnectionFactory");
		Assertions.assertDoesNotThrow(pooledConnectionFactory::afterPropertiesSet);
	}

	/**
	 * With no connection ever created, the busiest-connection session count must be zero rather than
	 * blowing up on an empty stream.
	 */
	@Test
	@DisplayName("With no connection created, the busiest-connection session count is zero")
	public void testMaxActiveSessionsWithoutConnections() {
		final MeteredJmsPoolConnectionFactory pooledConnectionFactory = MeteredJmsPoolConnectionFactory.create(null, this.distinctiveProperties(),
				new JmsPoolConnectionFactory());
		Assertions.assertEquals(0, pooledConnectionFactory.getMaxActiveSessions());
	}

}
