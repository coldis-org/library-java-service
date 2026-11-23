package org.coldis.library.test.service.jms;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import org.coldis.library.service.jms.JmsRequestThreadsLimit;
import org.coldis.library.test.TestHelper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Runs calls on request threads, for the tests of the JMS request-threads
 * limit.
 */
public final class RequestThreadTestHelper {

	/**
	 * No instances.
	 */
	private RequestThreadTestHelper() {
	}

	/**
	 * Runs a call on a request thread of its own.
	 *
	 * @param  <Type> Result type.
	 * @param  call   Call.
	 * @return        The call, running.
	 */
	public static <Type> FutureTask<Type> startOnRequestThread(
			final Callable<Type> call) {
		final FutureTask<Type> task = new FutureTask<>(() -> {
			RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
			try {
				return call.call();
			}
			finally {
				RequestContextHolder.resetRequestAttributes();
			}
		});
		Thread.ofVirtual().start(task);
		return task;
	}

	/**
	 * Waits until requests wait for a permit, up to a deadline.
	 *
	 * @param  limit     Limit.
	 * @return           Whether a request waits.
	 * @throws Exception If the wait fails.
	 */
	public static Boolean awaitWaitingRequest(
			final JmsRequestThreadsLimit limit) throws Exception {
		return TestHelper.waitUntilValid(limit::getWaitingRequests, waitingRequests -> waitingRequests > 0, TestHelper.LONG_WAIT,
				TestHelper.VERY_SHORT_WAIT);
	}

}
