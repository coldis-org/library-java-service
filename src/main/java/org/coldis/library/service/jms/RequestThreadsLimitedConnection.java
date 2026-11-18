package org.coldis.library.service.jms;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import org.messaginghub.pooled.jms.JmsPoolConnection;
import org.messaginghub.pooled.jms.pool.PooledConnection;

import jakarta.jms.JMSException;

/**
 * Pooled JMS connection that gives back the request-thread permit it was
 * created under ({@link JmsRequestThreadsLimit}) when it is closed, once.
 */
public class RequestThreadsLimitedConnection extends JmsPoolConnection {

	/**
	 * Permits to give the held permit back to, or null when none is held.
	 */
	private final AtomicReference<Semaphore> heldPermits = new AtomicReference<>();

	/**
	 * Constructor.
	 *
	 * @param connection Pooled connection.
	 */
	public RequestThreadsLimitedConnection(final PooledConnection connection) {
		super(connection);
	}

	/**
	 * Holds a permit until the connection is closed.
	 *
	 * @param permits Permits to give the permit back to.
	 */
	public void holdPermit(
			final Semaphore permits) {
		this.heldPermits.set(permits);
	}

	/**
	 * Closes the connection and gives the held permit back.
	 *
	 * @see org.messaginghub.pooled.jms.JmsPoolConnection#close()
	 */
	@Override
	public void close() throws JMSException {
		try {
			super.close();
		}
		finally {
			final Semaphore permits = this.heldPermits.getAndSet(null);
			if (permits != null) {
				permits.release();
			}
		}
	}

}
