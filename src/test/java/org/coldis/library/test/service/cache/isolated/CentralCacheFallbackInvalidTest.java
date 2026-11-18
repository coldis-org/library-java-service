package org.coldis.library.test.service.cache.isolated;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.coldis.library.service.cache.CacheManagerFallbackRegistrar;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A service that misspelled the central cache fallback.
 */
public class CentralCacheFallbackInvalidTest {

	/**
	 * Tests that the service does not start, with an error that names the property, the value and the accepted values.
	 */
	@Test
	@DisplayName("A misspelled central cache fallback stops the start with an error naming the property and the values")
	public void testCentralCacheFallbackInvalid() {
		IsolatedCacheContext.withoutRedis().withPropertyValues(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY + "=lcoal").run(context -> {
			Assertions.assertNotNull(context.getStartupFailure());
			final Throwable cause = ExceptionUtils.getThrowableList(context.getStartupFailure()).stream()
					.filter(throwable -> (throwable.getMessage() != null) && throwable.getMessage().contains(CacheManagerFallbackRegistrar.CENTRAL_FALLBACK_PROPERTY))
					.findFirst().orElseThrow();
			Assertions.assertInstanceOf(IllegalArgumentException.class, cause);
			Assertions.assertTrue(cause.getMessage().contains("'lcoal'"));
			Assertions.assertTrue(cause.getMessage().contains("[none, local]"));
		});
	}

}
