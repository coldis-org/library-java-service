package org.coldis.library.service.http;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;

/** User agent configuration. */
@Service
@ConditionalOnClass(UserAgentAnalyzer.class)
public class UserAgentHelper {

	/** User agent analyzer. */
	private static UserAgentAnalyzer USER_AGENT_ANALYZER;

	/** Default constructor. */
	public UserAgentHelper(@Value("${org.coldis.library.service.user-agent.cache:10000}")
	final Integer userAgentCache) {
		// Makes sure the user agent analyzer is created.
		UserAgentHelper.USER_AGENT_ANALYZER = (UserAgentHelper.USER_AGENT_ANALYZER == null
				? UserAgentAnalyzer.newBuilder().immediateInitialization().hideMatcherLoadStats().withAllFields().withCache(userAgentCache).build()
				: UserAgentHelper.USER_AGENT_ANALYZER);
	}

	/**
	 * Parses the user agent details.
	 *
	 * @param  userAgentDetails User agent details.
	 * @return                  The user agent details as a map.
	 */
	private static Map<String, Object> getUserAgentDetails(
			final UserAgent userAgentDetails) {
		// User agent details map.
		final Map<String, Object> userAgentDetailsMap = new HashMap<>();
		// If the user agent could be parsed.
		if (userAgentDetails != null) {
			// Converts the parsed user agent to a map.
			userAgentDetails.getAvailableFieldNamesSorted().forEach(
					userAgentAttribute -> userAgentDetailsMap.put(StringUtils.uncapitalize(userAgentAttribute), userAgentDetails.getValue(userAgentAttribute)));
		}
		// Returns the user agent details map.
		return userAgentDetailsMap;
	}

	/**
	 * Parses the user agent details.
	 *
	 * @param  userAgent User agent.
	 * @return           The user agent details as a map.
	 */
	public static Map<String, Object> getUserAgentDetails(
			final String userAgent) {
		return UserAgentHelper.getUserAgentDetails(UserAgentHelper.USER_AGENT_ANALYZER.parse(userAgent));
	}

	/**
	 * Parses the user agent details.
	 *
	 * @param  userAgent User agent.
	 * @return           The user agent details as a map.
	 */
	public static Map<String, Object> getUserAgentDetails(
			final Map<String, String> headers) {
		return UserAgentHelper.getUserAgentDetails(UserAgentHelper.USER_AGENT_ANALYZER.parse(headers));
	}

	/**
	 * Parses the user agent details (using request context holder to retrieve
	 * request).
	 *
	 * @return The user agent details as a map.
	 */
	public static Map<String, Object> getUserAgentDetails() {
		Map<String, Object> userAgentDetails = Map.of();
		final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
		if ((requestAttributes != null) && (requestAttributes instanceof final ServletRequestAttributes servletRequestAttributes)) {
			final HttpServletRequest request = servletRequestAttributes.getRequest();
			final String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
			if (StringUtils.isNotBlank(userAgent)) {
				userAgentDetails = UserAgentHelper.getUserAgentDetails(userAgent);
			}
		}
		return userAgentDetails;
	}

}
