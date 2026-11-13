package org.coldis.library.service.warmup;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers the health check with 503 while the service warms up, so that the instance takes no traffic before the
 * warm-up stops. It answers without reaching the health check service, so it does not touch the repository.
 */
public class ServiceWarmUpHealthCheckFilter extends OncePerRequestFilter {

	/**
	 * Warm-up service.
	 */
	private final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent;

	/**
	 * Constructor.
	 *
	 * @param serviceWarmUpServiceComponent Warm-up service.
	 */
	public ServiceWarmUpHealthCheckFilter(final ServiceWarmUpServiceComponent serviceWarmUpServiceComponent) {
		super();
		this.serviceWarmUpServiceComponent = serviceWarmUpServiceComponent;
	}

	/**
	 * @see org.springframework.web.filter.OncePerRequestFilter#doFilterInternal(jakarta.servlet.http.HttpServletRequest,
	 *      jakarta.servlet.http.HttpServletResponse, jakarta.servlet.FilterChain)
	 */
	@Override
	protected void doFilterInternal(
			final HttpServletRequest request,
			final HttpServletResponse response,
			final FilterChain filterChain) throws ServletException, IOException {
		if (this.serviceWarmUpServiceComponent.isTrafficAllowed()) {
			filterChain.doFilter(request, response);
		}
		else {
			response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
			response.setContentType(MediaType.TEXT_PLAIN_VALUE);
			response.getWriter().write("Warming up.");
		}
	}

}
