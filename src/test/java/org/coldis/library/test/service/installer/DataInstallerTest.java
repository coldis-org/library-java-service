package org.coldis.library.test.service.installer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.ArrayUtils;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.installer.DataInstaller;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Data installer test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class DataInstallerTest extends ContainerTestHelper {

	/**
	 * Test data.
	 */
	public static final DataInstallerTestEntity[] NON_UPDATABLE_DATA = { new DataInstallerTestEntity(13, 23, "33"),
			new DataInstallerTestEntity(14, 24, "teste5\r\n	\r\n\"abc") };

	/**
	 * Test data.
	 */
	public static final DataInstallerTestEntity[] UPDATABLE_DATA = { new DataInstallerTestEntity(10, 20, "30"), new DataInstallerTestEntity(11, 21, "31"),
			new DataInstallerTestEntity(12, 22, "32") };

	/**
	 * Test data.
	 */
	public static final DataInstallerTestEntity[] ALL_DATA = ArrayUtils.addAll(DataInstallerTest.NON_UPDATABLE_DATA, DataInstallerTest.UPDATABLE_DATA);

	/**
	 * Credential values in the failing data set, which must never be logged.
	 */
	private static final List<String> CREDENTIAL_VALUES = List.of("plain-password-value", "header-token-value", "nested-secret-value",
			"listed-api-key-value");

	/**
	 * Non-credential values in the failing data set, which must stay in the log.
	 */
	private static final List<String> KEPT_VALUES = List.of("kept-name", "kept-label", "kept-provider");

	/**
	 * Test repository.
	 */
	@Autowired
	private DataInstallerTestRepository testRepository;

	/**
	 * Data installer.
	 */
	@Autowired
	private DataInstaller dataInstaller;

	/**
	 * Cleans test data.
	 *
	 * @throws Exception If the test fails.
	 */
	@AfterEach
	@Transactional
	public void clean() throws Exception {
		this.testRepository.deleteAll();
	}

	/**
	 * Tests the auto installation.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testAutoInstallation() throws Exception {
		// For each test object.
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.ALL_DATA) {
			// Asserts that the object have been created.
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null),
					data -> Objects.equals(testEntity, data), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
	}

	/**
	 * Changes property 3 of a test entity (in its own transaction).
	 *
	 * @param key   The entity key.
	 * @param value The new value.
	 */
	@Transactional
	private void changeProperty3(
			final DataInstallerTestEntityKey key,
			final String value) {
		final DataInstallerTestEntity entity = this.testRepository.findById(key).orElseThrow();
		entity.setProperty3(value);
		this.testRepository.save(entity);
	}

	/**
	 * A search path template ("{property1}/{property2}/by-key") is filled with the data object's
	 * values, and nothing is appended: an existing object is found, so a create-only data set keeps it
	 * (without the template, the search misses and the object is created again over it).
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSearchPathTemplate() throws Exception {
		final DataInstallerTestEntityKey key = new DataInstallerTestEntityKey(15, 25);
		this.dataInstaller.install("data-set-4-search-path-template.json");
		Assertions.assertTrue(TestHelper.waitUntilValid(() -> this.testRepository.findById(key).orElse(null),
				data -> (data != null) && "35".equals(data.getProperty3()), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));

		// Changed after the installation: kept by the next one.
		this.changeProperty3(key, "changed");
		this.dataInstaller.install("data-set-4-search-path-template.json");
		Assertions.assertEquals("changed", this.testRepository.findById(key).orElseThrow().getProperty3());
	}

	/**
	 * Tests the single file installation.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testSingleFileInstallation() throws Exception {
		// Cleans any auto-installed data.
		this.testRepository.deleteAll();
		// Installs only data-set-1.
		this.dataInstaller.install("data-set-1.json");
		// For each updatable test object (from data-set-1).
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.UPDATABLE_DATA) {
			// Asserts that the object have been created.
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null),
					data -> Objects.equals(testEntity, data), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}
		// For each non-updatable test object (from data-set-2).
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.NON_UPDATABLE_DATA) {
			// Asserts that the object have not been created.
			Assertions.assertTrue(
					this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).isEmpty());
		}
	}

	/**
	 * Tests the multiple installations.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testMultipleInstallation() throws Exception {
		// Installs data.
		this.dataInstaller.install();
		// For each test object.
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.ALL_DATA) {
			// Asserts that the object have been created.
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null),
					data -> Objects.equals(testEntity, data), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
		}

		// After the first installation.
		final LocalDateTime timeOfInstallation = DateTimeHelper.getCurrentLocalDateTime();

		// Installs data.
		this.dataInstaller.install();
		// For each updatable object.
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.UPDATABLE_DATA) {
			// Asserts that the object have been created.
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null),
					data -> Objects.equals(testEntity, data), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
			// Gets the persisted entity.
			final DataInstallerTestEntity persistedEntity = this.testRepository
					.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElseThrow();
			// Makes sure the created and updated date are different.
			Assertions.assertTrue(persistedEntity.getCreatedAt().isBefore(timeOfInstallation));
			Assertions.assertTrue(persistedEntity.getUpdatedAt().isAfter(timeOfInstallation));
		}
		// For each non-updatable object.
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.NON_UPDATABLE_DATA) {
			// Asserts that the object have been created.
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null),
					data -> Objects.equals(testEntity, data), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
			// Gets the persisted entity.
			final DataInstallerTestEntity persistedEntity = this.testRepository
					.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElseThrow();
			// Makes sure the created and updated date are different.
			Assertions.assertTrue(persistedEntity.getCreatedAt().isBefore(timeOfInstallation));
			Assertions.assertTrue(persistedEntity.getUpdatedAt().isBefore(timeOfInstallation));
		}
	}

	/**
	 * Gets the text a logging event prints: its message and the messages of its
	 * exception chain.
	 *
	 * @param  event Logging event.
	 * @return       The printed text.
	 */
	private String getPrintedText(
			final ILoggingEvent event) {
		final StringBuilder printedText = new StringBuilder(event.getFormattedMessage());
		IThrowableProxy throwable = event.getThrowableProxy();
		while (throwable != null) {
			printedText.append(" ").append(throwable.getMessage());
			throwable = throwable.getCause();
		}
		return printedText.toString();
	}

	/**
	 * Tests that a failed installation logs the data object with its credentials
	 * masked, including nested maps and lists.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testFailedInstallationMasksCredentials() throws Exception {
		// Captures the installer logs, debug included.
		final Logger installerLogger = (Logger) LoggerFactory.getLogger(DataInstaller.class);
		final Level originalLevel = installerLogger.getLevel();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		installerLogger.addAppender(appender);
		installerLogger.setLevel(Level.DEBUG);
		try {
			// Installs the data set whose operation does not exist.
			this.dataInstaller.install("data-set-3-credentials.json");
			// The creation failure is logged with the object, credentials masked.
			final List<ILoggingEvent> objectEvents = appender.list.stream()
					.filter(event -> event.getFormattedMessage().startsWith("Object '")).toList();
			Assertions.assertTrue(objectEvents.stream()
					.anyMatch(event -> Level.ERROR.equals(event.getLevel()) && event.getFormattedMessage().endsWith("' could not be created.")));
			Assertions.assertTrue(objectEvents.stream().anyMatch(event -> Level.DEBUG.equals(event.getLevel())));
			for (final ILoggingEvent event : objectEvents) {
				Assertions.assertTrue(event.getFormattedMessage().contains(DataInstaller.CREDENTIAL_MASK));
				for (final String keptValue : DataInstallerTest.KEPT_VALUES) {
					Assertions.assertTrue(event.getFormattedMessage().contains(keptValue), keptValue);
				}
			}
			// No credential is printed anywhere, exceptions included.
			for (final ILoggingEvent event : appender.list) {
				final String printedText = this.getPrintedText(event);
				for (final String credentialValue : DataInstallerTest.CREDENTIAL_VALUES) {
					Assertions.assertFalse(printedText.contains(credentialValue), printedText);
				}
			}
		}
		finally {
			installerLogger.setLevel(originalLevel);
			installerLogger.detachAppender(appender);
		}
	}
}
