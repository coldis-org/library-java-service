package org.coldis.library.service.warmup;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Request factory that refuses requests from warm-up calls and delegates the others.
 */
public class ServiceWarmUpClientHttpRequestFactory implements ClientHttpRequestFactory {

	/**
	 * Request factory.
	 */
	private final ClientHttpRequestFactory requestFactory;

	/**
	 * Constructor.
	 *
	 * @param requestFactory Request factory.
	 */
	public ServiceWarmUpClientHttpRequestFactory(final ClientHttpRequestFactory requestFactory) {
		super();
		this.requestFactory = requestFactory;
	}

	/**
	 * Gets the guarded request factory.
	 *
	 * @return The guarded request factory.
	 */
	public ClientHttpRequestFactory getRequestFactory() {
		return this.requestFactory;
	}

	/**
	 * @see org.springframework.http.client.ClientHttpRequestFactory#createRequest(java.net.URI,
	 *      org.springframework.http.HttpMethod)
	 */
	@Override
	public ClientHttpRequest createRequest(
			final URI uri,
			final HttpMethod httpMethod) throws IOException {
		ServiceWarmUpContext.rejectDuringWarmUp(ServiceWarmUpBeanPostProcessor.HTTP_REQUEST);
		return this.requestFactory.createRequest(uri, httpMethod);
	}

}
