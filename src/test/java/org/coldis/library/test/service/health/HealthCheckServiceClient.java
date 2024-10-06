package org.coldis.library.test.service.health;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.GenericRestServiceClient;
import org.coldis.library.service.health.HealthCheckValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.StringValueResolver;

/**
 * Health check service client.
 */
@Service
public class HealthCheckServiceClient extends GenericRestServiceClient {

	/**
	 * Value resolver.
	 */
	@Autowired
	private StringValueResolver valueResolver;

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
		return this.executeOperation(
				this.valueResolver.resolveStringValue("http://localhost:${local.server.port:9090}")
						+ this.valueResolver.resolveStringValue("${org.coldis.configuration.health-check:/health-check}"),
				HttpMethod.GET, null, null, null, new ParameterizedTypeReference<HealthCheckValue>() {}).getBody();
	}
}
