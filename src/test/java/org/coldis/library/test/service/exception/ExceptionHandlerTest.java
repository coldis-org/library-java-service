package org.coldis.library.test.service.exception;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Exception handler test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class ExceptionHandlerTest extends TestHelper {

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
			this.exceptionHandlerServiceClient.constraintViolationExceptionService(new TestClass());
		}
		// Makes sure the message is enhanced from the code.
		catch (final BusinessException exception) {
			Assertions.assertTrue(exception.getMessages().stream().anyMatch(message -> message.getCode().equals("testclass.attribute1.notnull")));
			Assertions.assertTrue(exception.getMessages().stream().anyMatch(message -> message.getCode().equals("testclass.attribute2.notempty")));
			Assertions.assertTrue(exception.getMessages().stream().anyMatch(message -> message.getContent().equals("testclass.attribute1: must not be null")));
			Assertions.assertTrue(exception.getMessages().stream().anyMatch(message -> message.getContent().equals("testclass.attribute2: must not be empty")));
		}
	}

}
