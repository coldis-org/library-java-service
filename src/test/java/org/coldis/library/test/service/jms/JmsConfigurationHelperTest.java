package org.coldis.library.test.service.jms;

import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.ExtendedArtemisProperties;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link JmsConfigurationHelper}: the object message deserialization allow list
 * of the connection factory, the listener executor and the listener container
 * factory.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JmsConfigurationHelper — deserialization allow list, listener executor and container factory")
public class JmsConfigurationHelperTest extends ContainerTestHelper {

	/** JMS configuration helper. */
	@Autowired
	private JmsConfigurationHelper jmsConfigurationHelper;

	/** JMS converter properties. */
	@Autowired
	private JmsConverterProperties jmsConverterProperties;

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

	/**
	 * Gets the listener executor of a helper.
	 *
	 * @param  helper Helper.
	 * @return        The listener executor.
	 */
	private static Object getListenerExecutor(
			final JmsConfigurationHelper helper) {
		return ReflectionTestUtils.getField(helper, "jmsListenerExecutor");
	}

	/**
	 * Tests the listener executor variants.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Listener executor: fixed pool by default, cached pool when unbounded, throttled executor with the legacy concurrency")
	@SuppressWarnings("deprecation")
	public void testListenerExecutor() throws Exception {
		final JmsConfigurationHelper defaultHelper = new JmsConfigurationHelper();
		defaultHelper.setJmsListenerExecutor("executor-default", 5, false, null, null, null, null, null, null, 2, null, 60);
		final ThreadPoolExecutor fixedPool = Assertions.assertInstanceOf(ThreadPoolExecutor.class, JmsConfigurationHelperTest.getListenerExecutor(defaultHelper));
		try {
			Assertions.assertEquals(2, fixedPool.getCorePoolSize());
			Assertions.assertEquals(fixedPool.getCorePoolSize(), fixedPool.getMaximumPoolSize());
		}
		finally {
			fixedPool.shutdownNow();
		}

		final JmsConfigurationHelper unboundedHelper = new JmsConfigurationHelper();
		unboundedHelper.setJmsListenerExecutor("executor-unbounded", 5, false, null, null, 4, null, null, null, 2, null, 60, true);
		final ThreadPoolExecutor cachedPool = Assertions.assertInstanceOf(ThreadPoolExecutor.class,
				JmsConfigurationHelperTest.getListenerExecutor(unboundedHelper));
		try {
			Assertions.assertEquals(2, cachedPool.getCorePoolSize());
			Assertions.assertEquals(Integer.MAX_VALUE, cachedPool.getMaximumPoolSize());
			Assertions.assertInstanceOf(SynchronousQueue.class, cachedPool.getQueue());
		}
		finally {
			cachedPool.shutdownNow();
		}

		final JmsConfigurationHelper legacyHelper = new JmsConfigurationHelper();
		legacyHelper.setJmsListenerExecutor("executor-legacy", 5, false, 3, null, null, null, null, null, null, 20D, 60, true);
		Assertions.assertEquals(3, Assertions.assertInstanceOf(SimpleAsyncTaskExecutor.class, JmsConfigurationHelperTest.getListenerExecutor(legacyHelper))
				.getConcurrencyLimit());
	}

	/**
	 * Tests that the builder's factory tags its listeners.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The built factory tags its listeners with the factory bean name and bulkhead group; containers stay DefaultMessageListenerContainer")
	public void testFactoryTagsListeners() throws Exception {
		final DefaultJmsListenerContainerFactory factory = this.jmsConfigurationHelper.createJmsListenerContainerFactoryBuilder().bulkheadGroup("low").build();
		Assertions.assertInstanceOf(BeanNameAware.class, factory).setBeanName("lowFactory");
		final DefaultMessageListenerContainer listenerContainer = factory.createListenerContainer(JmsListenerBulkheadTestSupport.createEndpoint("tagged",
				"bulkhead/tagged", "1", message -> {}, this.jmsConverterProperties, null, null));
		Assertions.assertEquals(DefaultMessageListenerContainer.class, listenerContainer.getClass());
		final ExtendedMessagingMessageListenerAdapter messageListener = Assertions.assertInstanceOf(ExtendedMessagingMessageListenerAdapter.class,
				listenerContainer.getMessageListener());
		Assertions.assertEquals("lowFactory", messageListener.getFactoryBeanName());
		Assertions.assertEquals("low", messageListener.getBulkheadGroup());
	}

}
