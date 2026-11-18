package org.coldis.library.test.service.jms;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;

/**
 * Endpoints that take a pooled JMS connection on a request thread: one holds it
 * until released, as a request stuck on a broker would; the other takes it and
 * closes it at once.
 */
@RestController
@RequestMapping(path = "jms-request-threads-test")
public class JmsRequestThreadsLimitTestService {

	/**
	 * Longest a request holds its connection, so a failed test never holds the
	 * permits of the shared context.
	 */
	private static final long MAXIMUM_HOLD_SECONDS = 60;

	/**
	 * Pooled connection factory.
	 */
	@Autowired
	@Qualifier("pooledJmsConnectionFactory")
	private ConnectionFactory connectionFactory;

	/**
	 * Release of the held connections; released until a test holds them.
	 */
	private volatile CountDownLatch release = new CountDownLatch(0);

	/**
	 * Makes the next requests hold their connections until released.
	 */
	public void hold() {
		this.release = new CountDownLatch(1);
	}

	/**
	 * Releases the held connections.
	 */
	public void release() {
		this.release.countDown();
	}

	/**
	 * Takes a pooled connection and holds it until released.
	 *
	 * @throws JMSException         If the connection cannot be taken.
	 * @throws InterruptedException If interrupted while holding it.
	 */
	@RequestMapping(
			path = "hold",
			method = RequestMethod.GET
	)
	public void holdConnection() throws JMSException, InterruptedException {
		final CountDownLatch currentRelease = this.release;
		try (Connection connection = this.connectionFactory.createConnection()) {
			currentRelease.await(JmsRequestThreadsLimitTestService.MAXIMUM_HOLD_SECONDS, TimeUnit.SECONDS);
		}
	}

	/**
	 * Takes a pooled connection and closes it at once.
	 *
	 * @throws JMSException If the connection cannot be taken.
	 */
	@RequestMapping(
			path = "connect",
			method = RequestMethod.GET
	)
	public void connect() throws JMSException {
		try (Connection connection = this.connectionFactory.createConnection()) {
			connection.getMetaData();
		}
	}

}
