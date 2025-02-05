package org.coldis.library.service.http;

import java.util.Arrays;

import org.apache.commons.lang3.ArrayUtils;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP servlet helper.
 */
public class HttpServletHelper {
	
	/**
	 * If path should not be ignored.
	 *
	 * @param  request          Request.
	 * @param  ignorePathsRegex Paths to ignore.
	 * @return                  If path should not be ignored.
	 */
	public static boolean shouldConsiderPath(
			final ServletRequest request,
			final String[] ignorePathsRegex) {
		return request instanceof final HttpServletRequest servletRequest && (ArrayUtils.isEmpty(ignorePathsRegex)
				|| Arrays.stream(ignorePathsRegex).noneMatch(ignorePath -> servletRequest.getRequestURI().matches(ignorePath)));
	}


}
