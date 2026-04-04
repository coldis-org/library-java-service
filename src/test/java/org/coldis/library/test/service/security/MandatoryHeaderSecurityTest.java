package org.coldis.library.test.service.security;

import java.util.List;
import java.util.Map;

import org.coldis.library.helper.ReflectionHelper;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringValueResolver;

/**
 * Security test.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class MandatoryHeaderSecurityTest extends ContainerTestHelper {

	@Autowired
	private org.coldis.library.service.security.MandatoryHeaderSecurityFilter mandatoryHeaderSecurityFilter;

	/** Value resolver. */
	@Autowired
	private StringValueResolver valueResolver;

	/** Service client. */
	@Autowired
	@Qualifier(value = "restServiceClient")
	private GenericRestServiceClient genericRestServiceClient;

	/** Configures mandatory headers before each test. */
	@org.junit.jupiter.api.BeforeEach
	public void enableFilter() {
		ReflectionHelper.setAttribute(this.mandatoryHeaderSecurityFilter, true, "mandatoryHeaders",
				new String[] { "x-mandatory1", "x-mandatory2=abc" });
	}

	/** Clears mandatory headers after each test. */
	@org.junit.jupiter.api.AfterEach
	public void disableFilter() {
		ReflectionHelper.setAttribute(this.mandatoryHeaderSecurityFilter, true, "mandatoryHeaders",
				new String[] {});
	}

	/**
	 * Tests requests from browsers and non-browsers.
	 */
	@Test
	public void testBrowserRequests() throws Exception {
		// Requests without mandatory headers should be unauthorized.
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

		// Requests without mandatory headers values should be unauthorized.
		try {
			exception = null;
			this.genericRestServiceClient
					.executeOperation(this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/integration",
							HttpMethod.POST, new LinkedMultiValueMap<>(Map.of("X-Mandatory1", List.of(""), "X-Mandatory2", List.of(""))), null, null,
							new ParameterizedTypeReference<Void>() {})
					.getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(BusinessException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.UNAUTHORIZED.value(), ((BusinessException) exception).getStatusCode());

		// Requests without mandatory headers values should be unauthorized.
		try {
			exception = null;
			this.genericRestServiceClient
					.executeOperation(this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/integration",
							HttpMethod.POST, new LinkedMultiValueMap<>(Map.of("X-Mandatory1", List.of(""), "X-Mandatory2", List.of("bcd"))), null, null,
							new ParameterizedTypeReference<Void>() {})
					.getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(BusinessException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.UNAUTHORIZED.value(), ((BusinessException) exception).getStatusCode());

		// Requests with mandatory headers values should be authorized.
		try {
			exception = null;
			this.genericRestServiceClient
					.executeOperation(this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}") + "/exception/integration",
							HttpMethod.POST, new LinkedMultiValueMap<>(Map.of("X-Mandatory1", List.of(""), "X-Mandatory2", List.of("abc"))), null, null,
							new ParameterizedTypeReference<Void>() {})
					.getBody();
		}
		catch (final Exception thrownException) {
			exception = thrownException;
		}
		Assertions.assertNotNull(exception);
		Assertions.assertEquals(IntegrationException.class, exception.getClass());
		Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), ((IntegrationException) exception).getStatusCode());

	}

}
