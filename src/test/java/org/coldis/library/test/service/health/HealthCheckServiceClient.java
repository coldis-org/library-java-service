package org.coldis.library.test.service.health;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.health.HealthCheckValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Health check service client.
 */
@Service
public class HealthCheckServiceClient extends GenericRestServiceClient {

	/**
	 * Service endpoint.
	 */
	@Value("http://localhost:8080")
	private String endpoint;

	/**
	 * Service context.
	 */
	@Value("${org.coldis.configuration.health-check:/health-check}")
	private String context;

	/**
	 * Default constructor.
	 */
	public HealthCheckServiceClient() {
	}

	/**
	 * Health check service.
	 *
	 * @return                      The check value.
	 * @throws IntegrationException If the service call did not end successfully
	 *                                  (other than bad request).
	 * @throws BusinessException    If the service call did not end successfully
	 *                                  (bad request).
	 */
	public HealthCheckValue check() throws IntegrationException, BusinessException {
		return this.executeOperation(this.endpoint + this.context, HttpMethod.GET, null, null, null, new ParameterizedTypeReference<HealthCheckValue>() {})
				.getBody();
	}
}
