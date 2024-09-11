package org.coldis.library.service.helper;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import org.coldis.library.thread.ThreadMapContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Session helper.
 */
@Component
public class MultiLayerSessionHelper {

	/**
	 * Gets the thread session.
	 *
	 * @return Session.
	 */
	public static Map<String, Object> getThreadSession() {
		return ThreadMapContextHolder.getAttributes();
	}

	/**
	 * Gets the Spring session.
	 *
	 * @return Session.
	 */
	public static HttpServletRequest getServletRequest() {
		final ServletRequestAttributes requestAttributes = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes());
		return (requestAttributes == null ? null : requestAttributes.getRequest());
	}

	/**
	 * Gets an object from the session.
	 *
	 * @return Object from the session.
	 */
	public static Object getAttribute(
			final String key) {
		Object attribute = null;
		final HttpServletRequest servletRequest = MultiLayerSessionHelper.getServletRequest();
		final HttpSession servletSession = (servletRequest == null ? null : servletRequest.getSession(false));
		if (attribute == null) {
			if (servletRequest != null) {
				attribute = servletRequest.getAttribute(key);
			}
		}
		if (attribute == null) {
			if (servletSession != null) {
				attribute = servletSession.getAttribute(key);
			}
		}
		if (attribute == null) {
			if ((servletSession != null) && Set.of("session", "sessionid").contains(key.toLowerCase())) {
				attribute = servletSession.getId();
			}
		}
		if (attribute == null) {
			if (servletRequest != null) {
				attribute = servletRequest.getParameter(key);
			}
		}
		if (attribute == null) {
			if (servletRequest != null) {
				attribute = servletRequest.getHeader(key);
			}
		}
		if (attribute == null) {
			if ((servletRequest != null) && (servletRequest.getCookies() != null)) {
				attribute = Arrays.stream(servletRequest.getCookies()).filter(cookie -> key.equalsIgnoreCase(cookie.getName())).map(Cookie::getValue)
						.findFirst().orElse(null);
			}
		}
		if (attribute == null) {
			final Map<String, Object> threadSession = MultiLayerSessionHelper.getThreadSession();
			if (threadSession != null) {
				attribute = threadSession.get(key);
			}
		}
		return attribute;
	}

}
