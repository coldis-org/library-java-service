package org.coldis.library.service.security;

import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.coldis.library.service.http.HttpServletHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Mandatory header security filter. */
@Component
@Order(-100)
@ConditionalOnProperty(
		name = "org.coldis.library.service.security.mandatory-headers",
		matchIfMissing = false
)
public class MandatoryHeaderSecurityFilter implements Filter {

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(MandatoryHeaderSecurityFilter.class);

	/**
	 * Ignored paths.
	 */
	@Value(value = "#{'${org.coldis.library.service.security.ignore-mandatory-headers-paths:}'.split(',')}")
	private String[] ignorePaths;

	/**
	 * Mandatory headers.
	 */
	@Value(value = "#{'${org.coldis.library.service.security.mandatory-headers:}'.split(',')}")
	private String[] mandatoryHeaders;

	/**
	 * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
	 *      javax.servlet.ServletResponse, javax.servlet.FilterChain)
	 */
	@Override
	public void doFilter(
			final ServletRequest request,
			final ServletResponse response,
			final FilterChain chain) throws IOException, ServletException {
		// Throws an unauthorized request if headers are not present.
		boolean headersPresent = true;
		if (request instanceof final HttpServletRequest servletRequest) {
			if (ArrayUtils.isNotEmpty(this.mandatoryHeaders)) {
				if (HttpServletHelper.shouldConsiderPath(servletRequest, this.ignorePaths)) {
					headersPresent = Arrays.stream(this.mandatoryHeaders).allMatch(mandatoryHeader -> {
						boolean headerPresent = true;
						if (StringUtils.isNotBlank(mandatoryHeader)) {
							final String[] mandatoryHeaderKeyValue = mandatoryHeader.split("=");
							if (mandatoryHeaderKeyValue.length == 1) {
								headerPresent = servletRequest.getHeader(mandatoryHeader) != null;
							}
							else {
								final String mandatoryHeaderKey = mandatoryHeaderKeyValue[0];
								final String mandatoryHeaderValue = mandatoryHeaderKeyValue[1];
								headerPresent = EnumerationUtils.toList(servletRequest.getHeaders(mandatoryHeaderKey)).stream()
										.anyMatch(headerValue -> StringUtils.equalsAnyIgnoreCase(headerValue, mandatoryHeaderValue));
							}
						}
						return headerPresent;
					});
				}
			}
		}

		// Continues the chain if headers are present.
		if (headersPresent) {
			chain.doFilter(request, response);
		}
		// Sends unauthorized response if not.
		else if (response instanceof final HttpServletResponse servletResponse) {
			servletResponse.sendError(HttpStatus.UNAUTHORIZED.value());
		}

	}
}
