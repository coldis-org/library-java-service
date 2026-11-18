package org.coldis.library.test.service.properties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
	 * Release of the waiting requests; released until a test holds them.
	 */
	private volatile CountDownLatch release = new CountDownLatch(0);

	/**
	 * Makes the next requests wait until released.
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
	 * Holds the request thread until released, when a test holds them.
	 *
	 * @throws InterruptedException If interrupted while waiting.
	 */
	@RequestMapping(
			path = "wait",
			method = RequestMethod.GET
	)
	public void waitForRelease() throws InterruptedException {
		this.release.await(PropertiesConnectorTestService.MAXIMUM_WAIT_SECONDS, TimeUnit.SECONDS);
	}

}
