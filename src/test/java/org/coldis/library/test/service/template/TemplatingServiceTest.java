package org.coldis.library.test.service.template;

import java.util.Map;

import org.apache.velocity.VelocityContext;
import org.coldis.library.service.template.TemplatingService;
import org.coldis.library.test.ContainerExtension;
import org.coldis.library.test.TestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.testcontainers.containers.GenericContainer;

/**
 * Templating service test.
 */
@ExtendWith(ContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class TemplatingServiceTest {

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Templating service.
	 */
	@Autowired
	private TemplatingService templatingService;

	/**
	 * Test templates.
	 */
	@Test
	public void testTemplates() {
		this.templatingService.addStringTemplate("template1", "test ${param1}: ${param2.abc}");
		final String processedTemplate = this.templatingService.applyStringTemplate("template1",
				new VelocityContext(Map.of("param1", "param1Value", "param2", Map.of("abc", "param2Value"))));
		Assertions.assertEquals("test param1Value: param2Value", processedTemplate);
	}
}
