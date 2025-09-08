package org.coldis.library.test.service.security;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestHelper;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.StringValueResolver;
import org.testcontainers.containers.GenericContainer;

/**
 * Security test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "org.coldis.library.service.security.deny-non-browser-requests=true",
				"org.coldis.library.service.security.ignore-non-browser-requests-paths=/exception/business" }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class BrowserRequestSecurityTest extends ContainerTestHelper {

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
	 * Value resolver.
	 */
	@Autowired
	private StringValueResolver valueResolver;

	/**
	 * Service client.
	 */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient genericRestServiceClient;

	/**
	 * Tests requests from browsers and non-browsers.
	 */
	@Test
	public void testBrowserRequests() throws Exception {
		// Non-ignored paths should be unauthorized if a non-browser request.
		Exception exception = null;
		try {
			exception = null;
			this.genericRestServiceClient
					.executeOperation(this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/integration",
							HttpMethod.POST, null, null, null, new ParameterizedTypeReference<Void>() {})
					.getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(BusinessException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.UNAUTHORIZED.value(), ((BusinessException) exception).getStatusCode());

		// Non-ignored paths should be unauthorized if a non-browser request.
		try {
			exception = null;
			this.genericRestServiceClient
					.executeOperation(this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/integration",
							HttpMethod.POST, GenericRestServiceClient.addHeaders(null, true, HttpHeaders.USER_AGENT, "insomnia/10.1.1"), null, null,
							new ParameterizedTypeReference<Void>() {})
					.getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(BusinessException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.UNAUTHORIZED.value(), ((BusinessException) exception).getStatusCode());

		// Non-ignored paths should be authorized if a browser request.
		try {
			exception = null;
			this.genericRestServiceClient.executeOperation(
					this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/integration", HttpMethod.POST,
					GenericRestServiceClient.addHeaders(null, true, HttpHeaders.USER_AGENT,
							"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"),
					null, null, new ParameterizedTypeReference<Void>() {}).getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(IntegrationException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), ((IntegrationException) exception).getStatusCode());

		// Ignored paths should be authorized if a non-browser request.
		try {
			exception = null;
			this.genericRestServiceClient
					.executeOperation(this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/business",
							HttpMethod.POST, null, null, null, new ParameterizedTypeReference<Void>() {})
					.getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(BusinessException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.BAD_REQUEST.value(), ((BusinessException) exception).getStatusCode());

	}

}
