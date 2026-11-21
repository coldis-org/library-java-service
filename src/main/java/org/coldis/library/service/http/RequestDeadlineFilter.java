package org.coldis.library.service.http;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.service.client.ServiceClientRequestDeadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Request deadline: reads the time the caller still waits for the request, in
 * the {@value ServiceClientRequestDeadline#HEADER} header (milliseconds, sent by
 * the generated clients of the service client library), when the request
 * arrives, before any controller.
 * <ul>
 * <li>No header, or one that is not an integer: nothing changes (callers on an
 * older library send none).</li>
 * <li>Zero or less: the caller has given up, so the request is answered 504 at
 * once, without running the controller (logged at debug, counted in
 * {@value #EXPIRED_COUNTER} when there is a meter registry).</li>
 * <li>More: the deadline (now plus that time) is kept for the request in
 * {@link ServiceClientRequestDeadline} while the request runs on this thread,
 * and cleared after it; the generated clients called for it cap their calls to
 * what is left of it, and send it on.</li>
 * </ul>
 * On by default ({@code org.coldis.configuration.request-deadline.enabled},
 * also a runtime switch: {@link #setEnabled(boolean)}); off, every request goes
 * through as before. Accepted decisions: the header is taken from any caller (a
 * caller can shorten or skip its own request's downstream calls, which matters
 * only where a check lets a request through when its downstream call fails),
 * and once the original caller has given up a chain of writes can stop
 * partway. The health check is never filtered. Nothing running is interrupted: the
 * deadline is only checked when the request arrives and when a generated client
 * call starts. Work the request hands to another thread (asynchronous methods,
 * JMS, executors, asynchronous request processing) does not get it. Any failure
 * reading the header is logged and the request goes on without a deadline.
 */
public class RequestDeadlineFilter extends OncePerRequestFilter {

	/**
	 * Counter of requests answered 504 because their deadline had passed when they
	 * arrived.
	 */
	public static final String EXPIRED_COUNTER = "http.server.requests.deadline.expired";

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(RequestDeadlineFilter.class);

	/**
	 * Health check path (not filtered).
	 */
	private final String healthCheckPath;

	/**
	 * Counter of requests answered 504 on arrival ({@code null} without a meter
	 * registry).
	 */
	private final Counter expiredCounter;

	/**
	 * If the request deadline is on (a runtime switch, read once per request; no
	 * lock).
	 */
	private volatile boolean isEnabled;

	/**
	 * Constructor.
	 *
	 * @param isEnabled       If the request deadline is on.
	 * @param healthCheckPath Health check path (not filtered).
	 * @param meterRegistry   Meter registry ({@code null} for none).
	 */
	public RequestDeadlineFilter(final boolean isEnabled, final String healthCheckPath, final MeterRegistry meterRegistry) {
		super();
		this.isEnabled = isEnabled;
		this.healthCheckPath = StringUtils.defaultString(healthCheckPath);
		this.expiredCounter = (meterRegistry == null ? null
				: Counter.builder(RequestDeadlineFilter.EXPIRED_COUNTER)
						.description("Requests answered 504 on arrival: the caller's deadline had passed.").register(meterRegistry));
	}

	/**
	 * Gets if the request deadline is on.
	 *
	 * @return If the request deadline is on.
	 */
	public boolean isEnabled() {
		return this.isEnabled;
	}

	/**
	 * Turns the request deadline on or off for the requests that arrive from now
	 * on (what {@code org.coldis.configuration.request-deadline.enabled} sets at
	 * start).
	 *
	 * @param isEnabled If the request deadline is on.
	 */
	public void setEnabled(
			final boolean isEnabled) {
		this.isEnabled = isEnabled;
	}

	/**
	 * Parses the time the caller still waits for the request.
	 *
	 * @param  headerValue Header value ({@code null} if absent).
	 * @return             The time the caller still waits (milliseconds; zero or
	 *                     less when it has given up), or {@code null} if the
	 *                     header is absent or not an integer.
	 */
	public static Long parseTimeoutMillis(
			final String headerValue) {
		Long timeoutMillis = null;
		if (StringUtils.isNotBlank(headerValue)) {
			try {
				timeoutMillis = Long.parseLong(headerValue.trim());
			}
			catch (final NumberFormatException exception) {
				RequestDeadlineFilter.LOGGER.debug("Request deadline header ignored: not an integer.");
			}
		}
		return timeoutMillis;
	}

	/**
	 * Reads the time the caller still waits for a request. Never fails: a failure
	 * is logged and the request goes on without a deadline.
	 *
	 * @param  request Request.
	 * @return         The time the caller still waits (milliseconds), or
	 *                 {@code null} for none.
	 */
	private Long readTimeoutMillis(
			final HttpServletRequest request) {
		Long timeoutMillis = null;
		try {
			timeoutMillis = RequestDeadlineFilter.parseTimeoutMillis(request.getHeader(ServiceClientRequestDeadline.HEADER));
		}
		catch (final RuntimeException exception) {
			RequestDeadlineFilter.LOGGER.warn("Request deadline not read ({}): the request goes on without it.", exception.getClass().getSimpleName());
			RequestDeadlineFilter.LOGGER.debug("Request deadline not read.", exception);
		}
		return timeoutMillis;
	}

	/**
	 * Skips every request while the deadline is off, and the health check.
	 *
	 * @see OncePerRequestFilter#shouldNotFilter(HttpServletRequest)
	 */
	@Override
	protected boolean shouldNotFilter(
			final HttpServletRequest request) {
		final String requestUri = StringUtils.defaultString(request.getRequestURI());
		final int contextPathLength = StringUtils.length(request.getContextPath());
		return !this.isEnabled || (!this.healthCheckPath.isEmpty() && (requestUri.length() == (contextPathLength + this.healthCheckPath.length()))
				&& requestUri.startsWith(this.healthCheckPath, contextPathLength));
	}

	/**
	 * Answers 504 at once when the caller has given up; otherwise keeps the
	 * request's deadline while it runs.
	 *
	 * @see OncePerRequestFilter#doFilterInternal(HttpServletRequest,
	 *      HttpServletResponse, FilterChain)
	 */
	@Override
	protected void doFilterInternal(
			final HttpServletRequest request,
			final HttpServletResponse response,
			final FilterChain filterChain) throws ServletException, IOException {
		final Long timeoutMillis = this.readTimeoutMillis(request);
		if (timeoutMillis == null) {
			filterChain.doFilter(request, response);
		}
		else if (timeoutMillis <= 0) {
			RequestDeadlineFilter.LOGGER.debug("Request {} answered 504: its caller's deadline had passed ({} ms left).", request.getMethod(), timeoutMillis);
			if (this.expiredCounter != null) {
				this.expiredCounter.increment();
			}
			response.setStatus(HttpStatus.GATEWAY_TIMEOUT.value());
			response.setContentType(MediaType.TEXT_PLAIN_VALUE);
			response.getWriter().write("Request deadline expired.");
		}
		else {
			final Long previousDeadlineNanos = ServiceClientRequestDeadline.getDeadlineNanos();
			ServiceClientRequestDeadline.setDeadlineNanos(ServiceClientRequestDeadline.getDeadlineNanosIn(timeoutMillis));
			try {
				filterChain.doFilter(request, response);
			}
			finally {
				ServiceClientRequestDeadline.setDeadlineNanos(previousDeadlineNanos);
			}
		}
	}

}
