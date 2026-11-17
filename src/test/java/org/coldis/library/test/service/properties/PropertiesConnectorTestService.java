package org.coldis.library.test.service.properties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint that holds its request thread until released, to take every request
 * thread of a connector.
 */
@RestController
@RequestMapping(path = "properties-connector-test")
public class PropertiesConnectorTestService {

	/**
	 * Longest a request waits for its release, so a failed test never holds the
	 * threads of the shared context.
	 */
	private static final long MAXIMUM_WAIT_SECONDS = 60;

	/**
	 * Requests waiting for their release.
	 */
	private final AtomicInteger waitingRequests = new AtomicInteger();

	/**
	 * Release of the waiting requests.
	 */
	private volatile CountDownLatch release = new CountDownLatch(1);

	/**
	 * Gets the requests waiting for their release.
	 *
	 * @return The requests waiting for their release.
	 */
	public Integer getWaitingRequests() {
		return this.waitingRequests.get();
	}

	/**
	 * Makes the next requests wait again.
	 */
	public void hold() {
		this.release = new CountDownLatch(1);
	}

	/**
	 * Releases the waiting requests.
	 */
	public void release() {
		this.release.countDown();
	}

	/**
	 * Holds the request thread until released.
	 *
	 * @throws InterruptedException If interrupted while waiting.
	 */
	@RequestMapping(
			path = "wait",
			method = RequestMethod.GET
	)
	public void waitForRelease() throws InterruptedException {
		final CountDownLatch currentRelease = this.release;
		this.waitingRequests.incrementAndGet();
		try {
			currentRelease.await(PropertiesConnectorTestService.MAXIMUM_WAIT_SECONDS, TimeUnit.SECONDS);
		}
		finally {
			this.waitingRequests.decrementAndGet();
		}
	}

}
