package org.coldis.library.test.service.http;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.client.ServiceClientRequestDeadline;
import org.coldis.library.service.client.generator.ServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Request deadline test service: tells what deadline a request got, and calls
 * itself through its generated client, as a service calls another one.
 */
@RestController
@RequestMapping(path = "request-deadline")
@ServiceClient(
		namespace = "org.coldis.library.test.service.http",
		targetPath = "src/test/java",
		endpoint = "http://localhost:${local.server.port}/request-deadline"
)
public class RequestDeadlineTestService {

	/**
	 * Answer when there is no deadline (or no header).
	 */
	public static final String NONE = "none";

	/**
	 * How long the slow operation takes to answer (milliseconds).
	 */
	public static final long SLOW_DELAY_MILLIS = 2000;

	/**
	 * Requests that reached the controller.
	 */
	public static final AtomicInteger CONTROLLER_CALLS = new AtomicInteger();

	/**
	 * Generated client of this service.
	 */
	@Lazy
	@Autowired
	private RequestDeadlineTestServiceClient requestDeadlineTestServiceClient;

	/**
	 * Gets what is left of the request's deadline.
	 *
	 * @return What is left of the request's deadline (milliseconds), or
	 *         {@link #NONE}.
	 */
	@RequestMapping(
			path = "remaining",
			method = RequestMethod.GET
	)
	public String remaining() {
		RequestDeadlineTestService.CONTROLLER_CALLS.incrementAndGet();
		final Long deadlineNanos = ServiceClientRequestDeadline.getDeadlineNanos();
		return (deadlineNanos == null ? RequestDeadlineTestService.NONE
				: Long.toString(ServiceClientRequestDeadline.getRemainingNanos(deadlineNanos) / 1_000_000L));
	}

	/**
	 * Gets the deadline header the request came with.
	 *
	 * @param  header Deadline header.
	 * @return        The deadline header ({@link #NONE} if absent).
	 */
	@RequestMapping(
			path = "header",
			method = RequestMethod.GET
	)
	public String header(
			@RequestHeader(
					name = ServiceClientRequestDeadline.HEADER,
					required = false
			)
			final String header) {
		RequestDeadlineTestService.CONTROLLER_CALLS.incrementAndGet();
		return Objects.toString(header, RequestDeadlineTestService.NONE);
	}

	/**
	 * Answers after {@link #SLOW_DELAY_MILLIS}.
	 *
	 * @return An answer.
	 */
	@RequestMapping(
			path = "slow",
			method = RequestMethod.GET
	)
	public String slow() {
		RequestDeadlineTestService.CONTROLLER_CALLS.incrementAndGet();
		try {
			Thread.sleep(RequestDeadlineTestService.SLOW_DELAY_MILLIS);
		}
		catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
		return "slow";
	}

	/**
	 * Calls {@link #header(String)} through the generated client.
	 *
	 * @return                      The deadline header the call carried.
	 * @throws IntegrationException If the call fails.
	 * @throws BusinessException    If the call fails.
	 */
	@RequestMapping(
			path = "forward-header",
			method = RequestMethod.GET
	)
	public String forwardHeader() throws IntegrationException, BusinessException {
		RequestDeadlineTestService.CONTROLLER_CALLS.incrementAndGet();
		return this.requestDeadlineTestServiceClient.header(null);
	}

	/**
	 * Calls {@link #slow()} through the generated client.
	 *
	 * @return                      The answer.
	 * @throws IntegrationException If the call fails.
	 * @throws BusinessException    If the call fails.
	 */
	@RequestMapping(
			path = "forward-slow",
			method = RequestMethod.GET
	)
	public String forwardSlow() throws IntegrationException, BusinessException {
		RequestDeadlineTestService.CONTROLLER_CALLS.incrementAndGet();
		return this.requestDeadlineTestServiceClient.slow();
	}

}
