package org.coldis.library.service.security;

import java.io.IOException;
import java.util.Objects;

import org.coldis.library.service.http.HttpServletHelper;
import org.coldis.library.service.http.UserAgentHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Browser request security filter. */
@Component
@Order(-100)
@ConditionalOnProperty(
		name = "org.coldis.library.service.security.deny-non-browser-requests",
		havingValue = "true",
		matchIfMissing = false
)
public class BrowserRequestSecurityFilter implements Filter {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(BrowserRequestSecurityFilter.class);

	/**
	 * Ignored paths.
	 */
	@Value(value = "#{'${org.coldis.library.service.security.ignore-non-browser-requests-paths:}'.split(',')}")
	private String[] ignorePaths;

	/**
	 * If the request is from a browser.
	 *
	 * @param  servletRequest Request.
	 * @return                If the request is from a browser.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = "BrowserRequestSecurityFilter.isFromBrowser"
	)
	private boolean isFromBrowser(
			final String userAgent) {
		return Objects.equals("Browser", UserAgentHelper.getUserAgentDetails(userAgent).getOrDefault("agentClass", "Browser"));
	}

	/**
	 * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
	 *      javax.servlet.ServletResponse, javax.servlet.FilterChain)
	 */
	@Override
	public void doFilter(
			final ServletRequest request,
			final ServletResponse response,
			final FilterChain chain) throws IOException, ServletException {
		// Throws an unauthorized request if not a browser.
		boolean isBrowser = true;
		if (HttpServletHelper.shouldConsiderPath(request, this.ignorePaths)) {
			if (request instanceof final HttpServletRequest servletRequest) {
				isBrowser = this.isFromBrowser(servletRequest.getHeader(HttpHeaders.USER_AGENT));
			}
		}

		// Continues the chain if a browser.
		if (isBrowser) {
			chain.doFilter(request, response);
		}
		// Sends unauthorized response if not.
		else if (response instanceof final HttpServletResponse servletResponse) {
			servletResponse.sendError(HttpStatus.UNAUTHORIZED.value());
		}

	}

}
