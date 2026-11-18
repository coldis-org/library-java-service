package org.coldis.library.service.jms;

import java.util.concurrent.atomic.AtomicReference;

import org.messaginghub.pooled.jms.JmsPoolConnection;
import org.messaginghub.pooled.jms.pool.PooledConnection;

import jakarta.jms.JMSException;

/**
 * Pooled JMS connection that gives back its hold on the request-thread permit
 * it was created under ({@link JmsRequestThreadsLimit}) when it is closed, once.
 */
public class RequestThreadsLimitedConnection extends JmsPoolConnection {

	/**
	 * Permit held, or null when none is held.
	 */
	private final AtomicReference<JmsRequestThreadsLimit.Permit> heldPermit = new AtomicReference<>();

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
	 * @param permit Permit.
	 */
	public void holdPermit(
			final JmsRequestThreadsLimit.Permit permit) {
		this.heldPermit.set(permit);
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
			final JmsRequestThreadsLimit.Permit permit = this.heldPermit.getAndSet(null);
			if (permit != null) {
				permit.release();
			}
		}
	}

}
