package org.coldis.library.test.service.jms;

import java.util.List;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.ExtendedArtemisProperties;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link JmsConfigurationHelper} connection factory configuration: the object
 * message deserialization allow list.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JmsConfigurationHelper — deserialization allow list")
public class JmsConfigurationHelperTest extends ContainerTestHelper {

	/** JMS configuration helper. */
	@Autowired
	private JmsConfigurationHelper jmsConfigurationHelper;

	/** Bean factory. */
	@Autowired
	private ListableBeanFactory beanFactory;

	/** Default Artemis properties. */
	@Autowired
	@Qualifier("defaultArtemisProperties")
	private ExtendedArtemisProperties defaultArtemisProperties;

	/**
	 * Creates a native connection factory and returns its allow list.
	 *
	 * @param  properties Artemis properties.
	 * @return            The effective deserialization allow list.
	 */
	private List<String> getEffectiveAllowList(
			final ExtendedArtemisProperties properties) {
		try (ActiveMQConnectionFactory connectionFactory = (ActiveMQConnectionFactory) this.jmsConfigurationHelper
				.createNativeJmsConnectionFactory(this.beanFactory, properties)) {
			return List.of(connectionFactory.getDeserializationAllowList().split(","));
		}
	}

	/**
	 * Tests the default allow list, including the application base packages.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Default: coldis, the configured base packages and JDK value packages")
	public void testDefaultIncludesBasePackages() throws Exception {
		final Object originalBasePackages = ReflectionTestUtils.getField(this.jmsConfigurationHelper, "basePackages");
		try {
			ReflectionTestUtils.setField(this.jmsConfigurationHelper, "basePackages", "com.example.payloads, org.other");
			final List<String> allowList = this.getEffectiveAllowList(new ExtendedArtemisProperties());
			Assertions.assertEquals(List.of("org.coldis", "com.example.payloads", "org.other", "java.lang", "java.util", "java.time", "java.math"),
					allowList);
		}
		finally {
			ReflectionTestUtils.setField(this.jmsConfigurationHelper, "basePackages", originalBasePackages);
		}
	}

	/**
	 * Tests that an explicit property wins over the default.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("An explicit deserialization-allow-list property wins, including '*'")
	public void testExplicitPropertyWins() throws Exception {
		final ExtendedArtemisProperties properties = new ExtendedArtemisProperties();
		properties.setDeserializationAllowList("*");
		Assertions.assertEquals(List.of("*"), this.getEffectiveAllowList(properties));
	}

	/**
	 * Tests that a list set through the broker URL is kept.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A list set through the broker URL is kept")
	public void testBrokerUrlListIsKept() throws Exception {
		final ExtendedArtemisProperties properties = new ExtendedArtemisProperties();
		final String brokerUrl = this.defaultArtemisProperties.getBrokerUrl();
		properties.setBrokerUrl(brokerUrl + (brokerUrl.contains("?") ? "&" : "?") + "deserializationAllowList=com.example");
		Assertions.assertEquals(List.of("com.example"), this.getEffectiveAllowList(properties));
	}

}
