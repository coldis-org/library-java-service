package org.coldis.library.test.service.installer;

import java.util.Objects;

import org.apache.commons.lang3.ArrayUtils;
import org.coldis.library.service.installer.DataInstaller;
import org.coldis.library.test.ContainerExtension;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;

/**
 * Data installer test.
 */
@ExtendWith(ContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class DataInstallerTest extends TestHelper {

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

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
					.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null);
			// Makes sure the created and updated date are different.
			Assertions.assertTrue(persistedEntity.getCreatedAt().isBefore(persistedEntity.getUpdatedAt()));
		}
		// For each non-updatable object.
		for (final DataInstallerTestEntity testEntity : DataInstallerTest.NON_UPDATABLE_DATA) {
			// Asserts that the object have been created.
			Assertions.assertTrue(TestHelper.waitUntilValid(
					() -> this.testRepository.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null),
					data -> Objects.equals(testEntity, data), TestHelper.LONG_WAIT, TestHelper.SHORT_WAIT));
			// Gets the persisted entity.
			final DataInstallerTestEntity persistedEntity = this.testRepository
					.findById(new DataInstallerTestEntityKey(testEntity.getProperty1(), testEntity.getProperty2())).orElse(null);
			// Makes sure the created and updated date are different.
			Assertions.assertTrue(persistedEntity.getCreatedAt().isEqual(persistedEntity.getUpdatedAt()));
		}
	}
}
