package org.coldis.library.test.service.serialization;

import org.coldis.library.service.batch.BatchAction;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serialization test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class SerializationTest extends ContainerTestHelper {

	/**
	 * Object mapper.
	 */
	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Tests JSON mapper on invalid enum values.
	 */
	@Test
	public void testInvalidEnumValue() throws Exception {
		Assertions.assertEquals(BatchAction.START, this.objectMapper.readValue("\"START\"", BatchAction.class));
		Assertions.assertNull(this.objectMapper.readValue("\"INVALID\"", BatchAction.class));
	}
}
