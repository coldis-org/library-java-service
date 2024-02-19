package org.coldis.library.test.service.template;

import java.util.Map;

import org.apache.velocity.VelocityContext;
import org.coldis.library.service.template.TemplatingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Templating service test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class TemplatingServiceTest {

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
