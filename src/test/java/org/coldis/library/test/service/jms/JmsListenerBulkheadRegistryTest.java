package org.coldis.library.test.service.jms;

import org.coldis.library.service.jms.ExtendedMessageListenerContainer;
import org.coldis.library.service.jms.JmsListenerBulkhead;
import org.coldis.library.service.jms.JmsListenerBulkheadBinding;
import org.coldis.library.service.jms.JmsListenerBulkheadProperties;
import org.coldis.library.service.jms.JmsListenerBulkheadRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link JmsListenerBulkheadRegistry}: properties, validation, group and
 * reservation resolution and gauges, in a small context of its own (no broker:
 * the containers are never started).
 */
@DisplayName("JMS listener bulkhead registry")
public class JmsListenerBulkheadRegistryTest {

	/**
	 * Property prefix.
	 */
	private static final String PREFIX = JmsListenerBulkheadProperties.PREFIX + ".";

	/**
	 * Context with the bulkhead properties, the registry and a meter registry.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JmsListenerBulkheadProperties.class)
	@Import(JmsListenerBulkheadRegistry.class)
	static class BulkheadContext {

		/**
		 * Meter registry.
		 *
		 * @return The meter registry.
		 */
		@Bean
		public MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

	}

	/**
	 * Context runner.
	 */
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(BulkheadContext.class);

	/**
	 * Creates a listener container (never started).
	 *
	 * @param  destination   Destination.
	 * @param  factory       Factory bean name.
	 * @param  bulkheadGroup Bulkhead group set on the factory.
	 * @return               The container.
	 */
	private static ExtendedMessageListenerContainer container(
			final String destination,
			final String factory,
			final String bulkheadGroup) {
		final ExtendedMessageListenerContainer listenerContainer = new ExtendedMessageListenerContainer();
		listenerContainer.setDestinationName(destination);
		listenerContainer.setFactoryBeanName(factory);
		listenerContainer.setBulkheadGroup(bulkheadGroup);
		return listenerContainer;
	}

	/**
	 * Gets a gauge value.
	 *
	 * @param  meterRegistry Meter registry.
	 * @param  name          Name.
	 * @param  group         Group tag.
	 * @return               The value.
	 */
	private static double gauge(
			final MeterRegistry meterRegistry,
			final String name,
			final String group) {
		final Gauge gauge = meterRegistry.find(name).tag("group", group).gauge();
		Assertions.assertNotNull(gauge, name + " of group " + group);
		return gauge.value();
	}

	/**
	 * Tests that nothing is limited by default.
	 */
	@Test
	@DisplayName("Without properties the bulkhead is disabled and nothing is bound")
	public void testDisabledByDefault() {
		this.contextRunner.run(context -> {
			final JmsListenerBulkheadRegistry registry = context.getBean(JmsListenerBulkheadRegistry.class);
			Assertions.assertFalse(registry.isEnabled());
			Assertions.assertNull(registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "factory", null)));
			Assertions.assertNull(registry.bind(new DefaultMessageListenerContainer()));
			Assertions.assertNull(registry.getBulkhead(JmsListenerBulkheadProperties.DEFAULT_GROUP));
		});
	}

	/**
	 * Tests that a disabled bulkhead ignores a limit.
	 */
	@Test
	@DisplayName("A limit without enabled=true limits nothing")
	public void testLimitWithoutEnabled() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=4").run(context -> {
			Assertions.assertNull(context.getBean(JmsListenerBulkheadRegistry.class).bind(JmsListenerBulkheadRegistryTest.container("queue", null, null)));
		});
	}

	/**
	 * Tests that an enabled bulkhead without a limit limits nothing.
	 */
	@Test
	@DisplayName("enabled=true alone leaves the default group unlimited")
	public void testEnabledWithoutLimit() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true").run(context -> {
			final JmsListenerBulkheadRegistry registry = context.getBean(JmsListenerBulkheadRegistry.class);
			Assertions.assertTrue(registry.isEnabled());
			Assertions.assertNull(registry.bind(JmsListenerBulkheadRegistryTest.container("queue", null, null)));
			Assertions.assertNull(registry.getBulkhead(JmsListenerBulkheadProperties.DEFAULT_GROUP));
		});
	}

	/**
	 * Tests the default group limit and its gauges.
	 */
	@Test
	@DisplayName("max-in-flight limits the default group and registers its gauges")
	public void testDefaultGroup() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=4")
				.run(context -> {
					final JmsListenerBulkheadRegistry registry = context.getBean(JmsListenerBulkheadRegistry.class);
					final MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
					final JmsListenerBulkheadBinding binding = registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "factory", null));
					Assertions.assertNotNull(binding);
					Assertions.assertEquals(JmsListenerBulkheadProperties.DEFAULT_GROUP, binding.getBulkhead().getGroup());
					Assertions.assertEquals("queue", binding.getDestination());
					Assertions.assertNull(binding.getReservation());
					Assertions.assertEquals(4, binding.getBulkhead().getMaxInFlight());
					Assertions.assertSame(binding.getBulkhead(), registry.bind(new DefaultMessageListenerContainer()).getBulkhead());
					Assertions.assertEquals("unknown", registry.bind(new DefaultMessageListenerContainer()).getDestination());
					Assertions.assertEquals(4D, JmsListenerBulkheadRegistryTest.gauge(meterRegistry, JmsListenerBulkheadRegistry.MAX_IN_FLIGHT_METRIC,
							JmsListenerBulkheadProperties.DEFAULT_GROUP));
					Assertions.assertEquals(0D, JmsListenerBulkheadRegistryTest.gauge(meterRegistry, JmsListenerBulkheadRegistry.IN_FLIGHT_METRIC,
							JmsListenerBulkheadProperties.DEFAULT_GROUP));
					Assertions.assertEquals(0D, JmsListenerBulkheadRegistryTest.gauge(meterRegistry, JmsListenerBulkheadRegistry.WAITING_METRIC,
							JmsListenerBulkheadProperties.DEFAULT_GROUP));
					Assertions.assertNotNull(meterRegistry.find(JmsListenerBulkheadRegistry.WAIT_METRIC).tag("group", JmsListenerBulkheadProperties.DEFAULT_GROUP)
							.tag("destination", "queue").timer());
				});
	}

	/**
	 * Tests that the CPU multiplier wins over the fixed limit.
	 */
	@Test
	@DisplayName("max-in-flight-cpu-multiplier wins over max-in-flight")
	public void testCpuMultiplier() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=1000",
				JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight-cpu-multiplier=0.5").run(context -> {
					final JmsListenerBulkhead bulkhead = context.getBean(JmsListenerBulkheadRegistry.class)
							.getBulkhead(JmsListenerBulkheadProperties.DEFAULT_GROUP);
					Assertions.assertEquals((int) Math.ceil(Runtime.getRuntime().availableProcessors() * 0.5), bulkhead.getMaxInFlight());
				});
	}

	/**
	 * Tests group resolution.
	 */
	@Test
	@DisplayName("Group: the factory's explicit group, then the group listing the factory, then default")
	public void testGroups() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=10",
				JmsListenerBulkheadRegistryTest.PREFIX + "groups.low.factories=lowFactory", JmsListenerBulkheadRegistryTest.PREFIX + "groups.low.max-in-flight=2",
				JmsListenerBulkheadRegistryTest.PREFIX + "groups.high.max-in-flight=5", JmsListenerBulkheadRegistryTest.PREFIX + "groups.unlimited.factories=freeFactory")
				.run(context -> {
					final JmsListenerBulkheadRegistry registry = context.getBean(JmsListenerBulkheadRegistry.class);
					Assertions.assertEquals("low", registry.resolveGroup(JmsListenerBulkheadRegistryTest.container("queue", "lowFactory", null)));
					Assertions.assertEquals(2, registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "lowFactory", null)).getBulkhead().getMaxInFlight());
					Assertions.assertEquals("high", registry.resolveGroup(JmsListenerBulkheadRegistryTest.container("queue", "lowFactory", "high")));
					Assertions.assertEquals(5, registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "lowFactory", "high")).getBulkhead().getMaxInFlight());
					Assertions.assertEquals(JmsListenerBulkheadProperties.DEFAULT_GROUP,
							registry.resolveGroup(JmsListenerBulkheadRegistryTest.container("queue", "otherFactory", null)));
					Assertions.assertEquals(JmsListenerBulkheadProperties.DEFAULT_GROUP, registry.resolveGroup(new DefaultMessageListenerContainer()));
					Assertions.assertEquals(10,
							registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "otherFactory", null)).getBulkhead().getMaxInFlight());
					Assertions.assertNull(registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "freeFactory", null)));
					Assertions.assertNull(registry.bind(JmsListenerBulkheadRegistryTest.container("queue", "lowFactory", "missing")));
				});
	}

	/**
	 * Tests reservation resolution.
	 */
	@Test
	@DisplayName("Reservation: by destination (also a fully qualified queue), else by factory")
	public void testReservations() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=4",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.by-destination.permits=1",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.by-destination.destinations=queue/a, queue/b",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.by-factory.permits=2", JmsListenerBulkheadRegistryTest.PREFIX + "reserved.by-factory.factories=factoryB")
				.run(context -> {
					final JmsListenerBulkheadRegistry registry = context.getBean(JmsListenerBulkheadRegistry.class);
					final MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
					Assertions.assertEquals("by-destination", registry.bind(JmsListenerBulkheadRegistryTest.container("queue/a", "factoryB", null)).getReservation());
					Assertions.assertEquals("by-destination", registry.bind(JmsListenerBulkheadRegistryTest.container("queue/b", null, null)).getReservation());
					Assertions.assertEquals("by-factory", registry.bind(JmsListenerBulkheadRegistryTest.container("queue/c", "factoryB", null)).getReservation());
					Assertions.assertEquals("by-destination",
							registry.bind(JmsListenerBulkheadRegistryTest.container("address/a::queue/a", null, null)).getReservation());
					Assertions.assertNull(registry.bind(JmsListenerBulkheadRegistryTest.container("queue/c", null, null)).getReservation());
					Assertions.assertEquals(2D, meterRegistry.find(JmsListenerBulkheadRegistry.RESERVED_PERMITS_METRIC)
							.tag("group", JmsListenerBulkheadProperties.DEFAULT_GROUP).tag("reservation", "by-factory").gauge().value());
					Assertions.assertEquals(0D, meterRegistry.find(JmsListenerBulkheadRegistry.RESERVED_IN_FLIGHT_METRIC)
							.tag("group", JmsListenerBulkheadProperties.DEFAULT_GROUP).tag("reservation", "by-destination").gauge().value());
				});
	}

	/**
	 * Asserts that the context fails to start with a message naming a property.
	 *
	 * @param property Property the message must name.
	 * @param values   Property values.
	 */
	private void assertInvalid(
			final String property,
			final String... values) {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true").withPropertyValues(values).run(context -> {
			Assertions.assertNotNull(context.getStartupFailure());
			Throwable cause = context.getStartupFailure();
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			Assertions.assertInstanceOf(IllegalArgumentException.class, cause);
			Assertions.assertTrue(cause.getMessage().contains(property), cause.getMessage());
		});
	}

	/**
	 * Tests the startup validation.
	 */
	@Test
	@DisplayName("Invalid settings fail the startup with a message naming the property")
	public void testValidation() {
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "reserved.*.permits", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=2",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.critical.permits=2", JmsListenerBulkheadRegistryTest.PREFIX + "reserved.critical.destinations=queue");
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "reserved.*.permits", JmsListenerBulkheadRegistryTest.PREFIX + "reserved.critical.permits=1",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.critical.destinations=queue");
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "reserved.second.destinations", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=5",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.first.permits=1", JmsListenerBulkheadRegistryTest.PREFIX + "reserved.first.destinations=queue",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.second.permits=1", JmsListenerBulkheadRegistryTest.PREFIX + "reserved.second.destinations=queue");
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "reserved.critical.permits", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=5",
				JmsListenerBulkheadRegistryTest.PREFIX + "reserved.critical.permits=0");
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "groups.second.factories",
				JmsListenerBulkheadRegistryTest.PREFIX + "groups.first.factories=factory", JmsListenerBulkheadRegistryTest.PREFIX + "groups.second.factories=factory");
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "groups.low.max-wait-deliveries",
				JmsListenerBulkheadRegistryTest.PREFIX + "groups.low.max-wait-deliveries=0");
		this.assertInvalid(JmsListenerBulkheadRegistryTest.PREFIX + "groups.default", JmsListenerBulkheadRegistryTest.PREFIX + "groups.default.max-in-flight=1");
	}

	/**
	 * Tests that the default group limit follows the property at runtime.
	 */
	@Test
	@DisplayName("A changed max-in-flight applies live to the default group")
	public void testLiveLimit() {
		this.contextRunner.withPropertyValues(JmsListenerBulkheadRegistryTest.PREFIX + "enabled=true", JmsListenerBulkheadRegistryTest.PREFIX + "max-in-flight=4")
				.run(context -> {
					final JmsListenerBulkheadProperties properties = context.getBean(JmsListenerBulkheadProperties.class);
					final JmsListenerBulkhead bulkhead = context.getBean(JmsListenerBulkheadRegistry.class)
							.getBulkhead(JmsListenerBulkheadProperties.DEFAULT_GROUP);
					properties.setMaxInFlight(7);
					Assertions.assertEquals(7, bulkhead.getMaxInFlight());
					Assertions.assertEquals(7D, JmsListenerBulkheadRegistryTest.gauge(context.getBean(MeterRegistry.class),
							JmsListenerBulkheadRegistry.MAX_IN_FLIGHT_METRIC, JmsListenerBulkheadProperties.DEFAULT_GROUP));
				});
	}

}
