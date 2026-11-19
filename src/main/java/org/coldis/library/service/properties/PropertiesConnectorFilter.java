package org.coldis.library.service.properties;

import java.io.IOException;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps the properties connector ({@link PropertiesConnectorCustomizer}) to the
 * properties endpoint: any other path that arrives on it is answered with 404,
 * so nothing else (the health check, the management endpoints) takes its few
 * request threads. Requests on every other connector pass untouched. It exists
 * only while the connector is on.
 * <p>
 * Filters at the highest precedence run before it: while a service warms up,
 * the warm-up filter answers the health check with 503 on this connector too.
 * The paths are judged from the root, as the dispatcher servlet is mapped
 * there ({@code spring.mvc.servlet.path} unset); under a servlet path, the
 * endpoint itself would be refused here.
 */
@Component
@ConditionalOnProperty(
		name = PropertiesConnectorCustomizer.PROPERTY_PREFIX + ".enabled",
		havingValue = "true",
		matchIfMissing = false
)
public class PropertiesConnectorFilter extends OncePerRequestFilter implements Ordered {

	/**
	 * Path prefix of the properties endpoint.
	 */
	public static final String PROPERTIES_PATH_PREFIX = PropertiesService.PATH + "/";

	/**
	 * Properties connector.
	 */
	private final PropertiesConnectorCustomizer propertiesConnectorCustomizer;

	/**
	 * Constructor.
	 *
	 * @param propertiesConnectorCustomizer Properties connector.
	 */
	public PropertiesConnectorFilter(final PropertiesConnectorCustomizer propertiesConnectorCustomizer) {
		this.propertiesConnectorCustomizer = propertiesConnectorCustomizer;
	}

	/**
	 * Whether a path of a request is within the properties endpoint. Tomcat
	 * maps a servlet by the servlet path and the path info, decoded and
	 * normalized (no "..", no path parameters), while Spring MVC picks a handler
	 * by the request URI as sent, less the context path. So both must be within
	 * the endpoint: "/properties/../health-check" maps to "/health-check", and
	 * "/actuator/health/../../properties/x" maps to the endpoint but goes to the
	 * actuator.
	 *
	 * @param  request Request.
	 * @return         Whether both paths are within the endpoint.
	 */
	private static Boolean isWithinEndpoint(
			final HttpServletRequest request) {
		final String mappedPath = Objects.toString(request.getServletPath(), "") + Objects.toString(request.getPathInfo(), "");
		final String requestUri = Objects.toString(request.getRequestURI(), "");
		final String contextPath = Objects.toString(request.getContextPath(), "");
		final String pathAsSent = requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
		return mappedPath.startsWith(PropertiesConnectorFilter.PROPERTIES_PATH_PREFIX) && pathAsSent.startsWith(PropertiesConnectorFilter.PROPERTIES_PATH_PREFIX);
	}

	/**
	 * Whether a request arrived on the properties connector for a path outside
	 * the properties endpoint.
	 *
	 * @param  request Request.
	 * @return         Whether the request is refused.
	 */
	public Boolean isRefused(
			final HttpServletRequest request) {
		final Integer connectorPort = this.propertiesConnectorCustomizer.getLocalPort();
		return (connectorPort != null) && (request.getLocalPort() == connectorPort) && !PropertiesConnectorFilter.isWithinEndpoint(request);
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
		if (this.isRefused(request)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
		else {
			filterChain.doFilter(request, response);
		}
	}

	/**
	 * Runs right after the filters at the highest precedence (character
	 * encoding, the warm-up health check), so a refused request costs as little
	 * as possible.
	 *
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

}
