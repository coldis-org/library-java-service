package org.coldis.library.test.service.jms;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.coldis.library.service.jms.EnhancedJmsBootstrapConfiguration;
import org.coldis.library.service.jms.ExtendedArtemisProperties;
import org.coldis.library.service.jms.ExtendedMessageListenerContainer;
import org.coldis.library.service.jms.ExtendedMessagingMessageListenerAdapter;
import org.coldis.library.service.jms.JmsConfigurationHelper;
import org.coldis.library.service.jms.JmsConverterProperties;
import org.coldis.library.service.jms.StaleMessageFilterService;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@link JmsConfigurationHelper}: the object message deserialization allow list
 * of the connection factory, the listener executor and the listener container
 * factory.
 */
@TestWithContainer(reuse = true, stopDelay = ContainerTestHelper.CONTAINER_STOP_DELAY_SECONDS)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DisplayName("JmsConfigurationHelper — deserialization allow list, listener executor and container factory")
public class JmsConfigurationHelperTest extends ContainerTestHelper {

	/** JMS configuration helper. */
	@Autowired
	private JmsConfigurationHelper jmsConfigurationHelper;

	/** Meter registry. */
	@Autowired
	private MeterRegistry meterRegistry;

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
	 * Runs an action capturing the WARN lines the helper logs on this thread.
	 *
	 * @param  action    Action.
	 * @return           The WARN messages.
	 * @throws Throwable If the action fails.
	 */
	private static List<String> captureWarnings(
			final Executable action) throws Throwable {
		final Logger helperLogger = (Logger) LoggerFactory.getLogger(JmsConfigurationHelper.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		helperLogger.addAppender(appender);
		try {
			action.execute();
		}
		finally {
			helperLogger.detachAppender(appender);
		}
		final String threadName = Thread.currentThread().getName();
		return appender.list.stream().filter(event -> (event.getLevel() == Level.WARN) && threadName.equals(event.getThreadName()))
				.map(ILoggingEvent::getFormattedMessage).toList();
	}

	/**
	 * Gets the listener executor of a helper as a thread pool, shutting it down
	 * (the assertions only read its configuration).
	 *
	 * @param  helper Helper.
	 * @return        The pool.
	 */
	private static ThreadPoolExecutor getPoolAndShutdown(
			final JmsConfigurationHelper helper) {
		final ThreadPoolExecutor pool = Assertions.assertInstanceOf(ThreadPoolExecutor.class, JmsConfigurationHelperTest.getListenerExecutor(helper));
		pool.shutdownNow();
		return pool;
	}

	/**
	 * Tests that the listener executor is an unbounded cached pool by default,
	 * growing with consumers and shrinking to no thread when idle.
	 *
	 * @throws Throwable If the test fails.
	 */
	@Test
	@DisplayName("Listener executor: by default a cached pool with no core threads, growing with consumers and shrinking to 0 when idle, no WARN")
	public void testDefaultExecutorIsUnbounded() throws Throwable {
		final JmsConfigurationHelper helper = new JmsConfigurationHelper();
		final List<String> warnings = JmsConfigurationHelperTest.captureWarnings(
				() -> helper.setJmsListenerExecutor("executor-default", null, false, null, null, null, null, null, null, null, null, 1, true));
		final ThreadPoolExecutor pool = Assertions.assertInstanceOf(ThreadPoolExecutor.class, JmsConfigurationHelperTest.getListenerExecutor(helper));
		try {
			Assertions.assertEquals(List.of(), warnings);
			Assertions.assertEquals(0, pool.getCorePoolSize());
			Assertions.assertEquals(Integer.MAX_VALUE, pool.getMaximumPoolSize());
			Assertions.assertInstanceOf(SynchronousQueue.class, pool.getQueue());
			final CountDownLatch release = new CountDownLatch(1);
			for (int taskIndex = 0; taskIndex < 5; taskIndex++) {
				pool.execute(() -> JmsListenerBulkheadTestSupport.awaitIgnoringInterrupts(release));
			}
			Assertions.assertEquals(5, pool.getPoolSize());
			release.countDown();
			JmsListenerBulkheadTestSupport.waitUntil(() -> pool.getPoolSize() == 0, "idle threads released after the keep-alive");
		}
		finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Tests the unbounded and bounded settings.
	 *
	 * @throws Throwable If the test fails.
	 */
	@Test
	@DisplayName("Listener executor: core-size* is ignored with a WARN when unbounded; unbounded=false keeps the previous bounded pool, with WARNs")
	public void testBoundedOnlyWhenUnboundedFalse() throws Throwable {
		final JmsConfigurationHelper ignoredHelper = new JmsConfigurationHelper();
		final List<String> ignoredWarnings = JmsConfigurationHelperTest.captureWarnings(() -> ignoredHelper.setJmsListenerExecutor("executor-ignored",
				null, false, null, null, 4, null, 2, null, 2, 50D, 60, true));
		final ThreadPoolExecutor ignoredPool = JmsConfigurationHelperTest.getPoolAndShutdown(ignoredHelper);
		Assertions.assertEquals(0, ignoredPool.getCorePoolSize());
		Assertions.assertEquals(Integer.MAX_VALUE, ignoredPool.getMaximumPoolSize());
		Assertions.assertEquals(3, ignoredWarnings.size());
		Assertions.assertTrue(ignoredWarnings.stream().allMatch(warning -> warning.contains("deprecated") && warning.contains("ignored")));

		final JmsConfigurationHelper boundedHelper = new JmsConfigurationHelper();
		final List<String> boundedWarnings = JmsConfigurationHelperTest.captureWarnings(() -> boundedHelper.setJmsListenerExecutor("executor-bounded",
				null, false, null, null, null, null, null, null, 2, null, 60, false));
		final ThreadPoolExecutor boundedPool = JmsConfigurationHelperTest.getPoolAndShutdown(boundedHelper);
		Assertions.assertEquals(2, boundedPool.getCorePoolSize());
		Assertions.assertEquals(2, boundedPool.getMaximumPoolSize());
		Assertions.assertEquals(2, boundedWarnings.size());
		Assertions.assertTrue(boundedWarnings.stream().anyMatch(warning -> warning.contains("unbounded=false") && warning.contains("bulkhead")));

		final JmsConfigurationHelper defaultBoundedHelper = new JmsConfigurationHelper();
		defaultBoundedHelper.setJmsListenerExecutor("executor-default-bounded", null, false, null, null, null, null, null, null, null, null, 60, false);
		final ThreadPoolExecutor defaultBoundedPool = JmsConfigurationHelperTest.getPoolAndShutdown(defaultBoundedHelper);
		final int previousDefaultSize = (int) (Runtime.getRuntime().availableProcessors() * JmsConfigurationHelper.DEFAULT_BOUNDED_CORE_SIZE_CPU_MULTIPLIER);
		Assertions.assertEquals(previousDefaultSize, defaultBoundedPool.getCorePoolSize());
		Assertions.assertEquals(previousDefaultSize, defaultBoundedPool.getMaximumPoolSize());

		final JmsConfigurationHelper priorityHelper = new JmsConfigurationHelper();
		final List<String> priorityWarnings = JmsConfigurationHelperTest.captureWarnings(() -> priorityHelper.setJmsListenerExecutor("executor-priority", 3,
				false, null, null, null, null, null, null, null, null, 60, true));
		JmsConfigurationHelperTest.getPoolAndShutdown(priorityHelper);
		Assertions.assertEquals(1, priorityWarnings.size());
		Assertions.assertTrue(priorityWarnings.get(0).contains("priority"));
	}

	/**
	 * Tests the deprecated signature and setting.
	 *
	 * @throws Throwable If the test fails.
	 */
	@Test
	@DisplayName("Deprecated: the old executor setter (now unbounded, sizes ignored with a WARN) and the legacy concurrency limit with its WARN")
	@SuppressWarnings("deprecation")
	public void testDeprecatedExecutorPaths() throws Throwable {
		final JmsConfigurationHelper oldHelper = new JmsConfigurationHelper();
		final List<String> oldWarnings = JmsConfigurationHelperTest
				.captureWarnings(() -> oldHelper.setJmsListenerExecutor("executor-old", 5, false, null, null, null, null, null, null, null, 20D, 60));
		Assertions.assertEquals(Integer.MAX_VALUE, JmsConfigurationHelperTest.getPoolAndShutdown(oldHelper).getMaximumPoolSize());
		Assertions.assertTrue(oldWarnings.stream().anyMatch(warning -> warning.contains("core-size") && warning.contains("ignored")));

		final JmsConfigurationHelper legacyHelper = new JmsConfigurationHelper();
		final List<String> legacyWarnings = JmsConfigurationHelperTest.captureWarnings(
				() -> legacyHelper.setJmsListenerExecutor("executor-legacy", null, false, 3, null, null, null, null, null, null, null, 60));
		Assertions.assertEquals(3, Assertions.assertInstanceOf(SimpleAsyncTaskExecutor.class, JmsConfigurationHelperTest.getListenerExecutor(legacyHelper))
				.getConcurrencyLimit());
		Assertions.assertEquals(1, legacyWarnings.size());
		Assertions.assertTrue(legacyWarnings.get(0).contains("deprecated") && legacyWarnings.get(0).contains("bulkhead"));
	}

	/**
	 * Tests that the JMS executors of the context expose metrics.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The listener executor and the Artemis global, scheduled and flow control executors expose executor metrics")
	public void testExecutorMetrics() throws Exception {
		for (final String executorName : List.of(JmsConfigurationHelper.LISTENER_EXECUTOR_METRICS_NAME, JmsConfigurationHelper.GLOBAL_EXECUTOR_METRICS_NAME,
				JmsConfigurationHelper.GLOBAL_SCHEDULED_EXECUTOR_METRICS_NAME, JmsConfigurationHelper.GLOBAL_FLOW_CONTROL_EXECUTOR_METRICS_NAME)) {
			Assertions.assertNotNull(this.meterRegistry.find("executor.pool.size").tag("name", executorName).gauge(), executorName);
		}
		Assertions.assertEquals((double) Integer.MAX_VALUE, this.meterRegistry.find("executor.pool.max")
				.tag("name", JmsConfigurationHelper.LISTENER_EXECUTOR_METRICS_NAME).gauge().value());
	}

	/**
	 * Tests that the builder's factory tags its listeners.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The built factory tags its listeners with the factory bean name and bulkhead group; containers are ExtendedMessageListenerContainer (the bulkhead gate)")
	public void testFactoryTagsListeners() throws Exception {
		final DefaultJmsListenerContainerFactory factory = this.jmsConfigurationHelper.createJmsListenerContainerFactoryBuilder().bulkheadGroup("low").build();
		Assertions.assertInstanceOf(BeanNameAware.class, factory).setBeanName("lowFactory");
		final DefaultMessageListenerContainer listenerContainer = factory.createListenerContainer(JmsListenerBulkheadTestSupport.createEndpoint("tagged",
				"bulkhead/tagged", "1", message -> {}, this.jmsConverterProperties, null, null));
		Assertions.assertEquals(ExtendedMessageListenerContainer.class, listenerContainer.getClass());
		final ExtendedMessagingMessageListenerAdapter messageListener = Assertions.assertInstanceOf(ExtendedMessagingMessageListenerAdapter.class,
				listenerContainer.getMessageListener());
		Assertions.assertEquals("lowFactory", messageListener.getFactoryBeanName());
		Assertions.assertEquals("low", messageListener.getBulkheadGroup());
	}

	/**
	 * Tests that the listener annotation processor's old signature still exists
	 * and creates a working processor.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("Deprecated: the listener annotation processor's signature without the bulkhead registry still creates a processor")
	@SuppressWarnings("deprecation")
	public void testDeprecatedAnnotationProcessorSignature() throws Exception {
		final StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		Assertions.assertNotNull(new EnhancedJmsBootstrapConfiguration().enhancedJmsListenerAnnotationProcessor(this.jmsConverterProperties,
				beanFactory.getBeanProvider(StaleMessageFilterService.class), beanFactory.getBeanProvider(MeterRegistry.class), List.of()));
	}

	/**
	 * Resolves the setting of one parameter of the executor setter as Spring
	 * does: its placeholder, with service.properties as the lowest source.
	 *
	 * @param  parameterIndex Parameter index.
	 * @param  properties     Application properties.
	 * @return                The resolved value (empty when not set).
	 * @throws Exception      If the resolution fails.
	 */
	private static String resolveExecutorSetting(
			final int parameterIndex,
			final Map<String, Object> properties) throws Exception {
		final Method setter = Arrays.stream(JmsConfigurationHelper.class.getMethods())
				.filter(method -> "setJmsListenerExecutor".equals(method.getName()) && (method.getParameterCount() == 13)).findFirst().orElseThrow();
		final String placeholder = setter.getParameters()[parameterIndex].getAnnotation(Value.class).value();
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		environment.getPropertySources().addLast(new ResourcePropertySource("classpath:service.properties"));
		return environment.resolvePlaceholders(placeholder);
	}

	/**
	 * Tests that the library's own defaults leave the core size and the
	 * unbounded setting unset, so only an application's value selects the
	 * bounded pool.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("The library defaults leave core-size* and priority unset and unbounded=true; an application's values are seen as set")
	public void testLibraryDefaultsAreNotExplicit() throws Exception {
		Assertions.assertEquals("", JmsConfigurationHelperTest.resolveExecutorSetting(1, Map.of()));
		Assertions.assertEquals("", JmsConfigurationHelperTest.resolveExecutorSetting(9, Map.of()));
		Assertions.assertEquals("", JmsConfigurationHelperTest.resolveExecutorSetting(10, Map.of()));
		Assertions.assertEquals("true", JmsConfigurationHelperTest.resolveExecutorSetting(12, Map.of()));
		Assertions.assertEquals("true", JmsConfigurationHelperTest.resolveExecutorSetting(12,
				Map.of("org.coldis.library.service.jms.listener.bulkhead.enabled", "false")));
		Assertions.assertEquals("20", JmsConfigurationHelperTest.resolveExecutorSetting(10,
				Map.of("org.coldis.library.service.jms.listener.executor.core-size-cpu-multiplier", "20")));
		Assertions.assertEquals("false", JmsConfigurationHelperTest.resolveExecutorSetting(12,
				Map.of("org.coldis.library.service.jms.listener.executor.unbounded", "false")));
	}

}
