package org.coldis.library.service.warmup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the table of guarded methods, whose types and methods are named rather than referenced: a renamed or moved
 * class or method would otherwise turn its guard off without failing the build.
 */
public class ServiceWarmUpSideEffectAdvisorTest {

	/**
	 * Tests that every guarded type is present (the test class path has every optional dependency) and declares every
	 * guarded method as a public method.
	 */
	@Test
	@DisplayName("Every guarded type exists and has every guarded method")
	public void testGuardsResolve() {
		for (final ServiceWarmUpSideEffectAdvisor.Guard guard : ServiceWarmUpSideEffectAdvisor.GUARDS) {
			final Class<?> type = guard.getType();
			Assertions.assertNotNull(type, guard.typeName());
			final Set<String> publicMethodNames = Arrays.stream(type.getMethods()).filter(method -> Modifier.isPublic(method.getModifiers()))
					.map(Method::getName).collect(Collectors.toSet());
			Assertions.assertTrue(publicMethodNames.containsAll(guard.methodNames()), guard.typeName() + " " + guard.methodNames());
		}
	}

}
