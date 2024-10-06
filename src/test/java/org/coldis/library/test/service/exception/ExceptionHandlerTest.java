package org.coldis.library.test.service.exception;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.StopTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Exception handler test.
 */
@TestWithContainer
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(StopTestWithContainerExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ExceptionHandlerTest extends TestHelper {

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
	 * Exception handler service client.
	 */
	@Autowired
	private ExceptionHandlerServiceClient exceptionHandlerServiceClient;

	/**
	 * Tests business exception messages.
	 */
	@Test
	public void testBusinessException() {
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.businessExceptionService("test", new Object[] { 1 });
		}
		// Makes sure the message is enhanced from the code.
		catch (final BusinessException exception) {
			Assertions.assertEquals("123", exception.getMessage());
		}
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.businessExceptionService("test.test", new Object[] { 1 });
		}
		// Makes sure the message is enhanced from the code.
		catch (final BusinessException exception) {
			Assertions.assertEquals("456", exception.getMessage());
		}
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.businessExceptionService("test.test.test", new Object[] { "789" });
		}
		// Makes sure the message is enhanced from the code.
		catch (final BusinessException exception) {
			Assertions.assertEquals("123456789", exception.getMessage());
		}
	}

	/**
	 * Tests integration exception messages.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testIntegrationException() throws Exception {
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.integrationExceptionService("test", new Object[] { 1 });
		}
		// Makes sure the message is enhanced from the code.
		catch (final IntegrationException exception) {
			Assertions.assertEquals("123", exception.getMessage());
		}
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.integrationExceptionService("test.test", new Object[] { 1 });
		}
		// Makes sure the message is enhanced from the code.
		catch (final IntegrationException exception) {
			Assertions.assertEquals("456", exception.getMessage());
		}
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.integrationExceptionService("test.test.test", new Object[] { "789" });
		}
		// Makes sure the message is enhanced from the code.
		catch (final IntegrationException exception) {
			Assertions.assertEquals("123456789", exception.getMessage());
		}
	}

	/**
	 * Tests constraint violation exception messages.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testConstraintViolationException() throws Exception {
		// Tests exception messages.
		try {
			this.exceptionHandlerServiceClient.constraintViolationExceptionService(new ExceptionTestClass());
		}
		// Makes sure the message is enhanced from the code.
		catch (final BusinessException exception) {
			Assertions.assertTrue(exception.getMessages().stream().anyMatch(message -> message.getCode().equals("exceptiontestclass.attribute1.notnull")));
			Assertions.assertTrue(exception.getMessages().stream().anyMatch(message -> message.getCode().equals("exceptiontestclass.attribute2.notempty")));
			Assertions.assertTrue(
					exception.getMessages().stream().anyMatch(message -> message.getContent().equals("exceptiontestclass.attribute1: must not be null")));
			Assertions.assertTrue(
					exception.getMessages().stream().anyMatch(message -> message.getContent().equals("exceptiontestclass.attribute2: must not be empty")));
		}
	}

}
