package org.coldis.library.test.service.serialization;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.coldis.library.serialization.CompositeObjectCloner;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * {@link CompositeObjectCloner} Spring wiring test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ObjectClonerTest extends ContainerTestHelper {

	/**
	 * Composite cloner.
	 */
	@Autowired
	private CompositeObjectCloner cloner;

	/**
	 * Tests the auto-wired composite cloner produces an independent deep
	 * copy of a structured payload (Fory path — first in the chain).
	 */
	@Test
	public void testClone() throws Exception {
		final Map<String, Object> original = new HashMap<>();
		original.put("a", 1);
		original.put("b", List.of("x", "y", "z"));
		original.put("c", Map.of("nested", true));

		final Map<String, Object> clone = this.cloner.clone(original);

		Assertions.assertNotSame(original, clone);
		Assertions.assertEquals(original, clone);
	}

	/**
	 * Tests null is propagated through the auto-wired cloner.
	 */
	@Test
	public void testCloneNull() throws Exception {
		Assertions.assertNull(this.cloner.clone(null));
	}

}
