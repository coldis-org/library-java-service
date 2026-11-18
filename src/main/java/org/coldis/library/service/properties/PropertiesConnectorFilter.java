package org.coldis.library.service.properties;

import java.io.IOException;

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
	public static final String PROPERTIES_PATH_PREFIX = "/properties/";

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
	 * Gets the path a request is served by: the servlet path and the path info,
	 * as Tomcat mapped them, decoded and normalized (no "..", no path
	 * parameters). The request URI is neither, so "/properties/../health-check"
	 * starts like the endpoint and is served by the health check.
	 *
	 * @param  request Request.
	 * @return         The path within the application.
	 */
	private static String getMappedPath(
			final HttpServletRequest request) {
		final String servletPath = request.getServletPath();
		final String pathInfo = request.getPathInfo();
		return ((servletPath == null) ? "" : servletPath) + ((pathInfo == null) ? "" : pathInfo);
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
		return (connectorPort != null) && (request.getLocalPort() == connectorPort)
				&& !PropertiesConnectorFilter.getMappedPath(request).startsWith(PropertiesConnectorFilter.PROPERTIES_PATH_PREFIX);
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
	 * Runs before the other filters, so a refused request costs as little as
	 * possible.
	 *
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

}
