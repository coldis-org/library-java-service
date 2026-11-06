package org.coldis.library.service.jms;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;

/**
 * JMS asynchronous hops exceeded exception. The message payload is not part of
 * the exception text (it may hold sensitive data and is kept on the exceeded
 * queue anyway).
 */
public class JmsAsyncHopsExceededException extends IntegrationException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 2574019464963826277L;

	/**
	 * Default constructor.
	 *
	 * @param destination Destination.
	 * @param hopsCount   Current hops.
	 */
	public JmsAsyncHopsExceededException(final String destination, final Long hopsCount) {
		super(new SimpleMessage("jms.async.hops.exceeded",
				"The maximum number of async hops (" + hopsCount + ") was exceeded in destination '" + destination + "'.",
				new Object[] { destination, hopsCount, null }));
	}

	/**
	 * Constructor kept for compatibility: the payload is ignored.
	 *
	 * @param      destination Destination.
	 * @param      hopsCount   Current hops.
	 * @param      payload     Ignored.
	 * @deprecated             Use
	 *                             {@link #JmsAsyncHopsExceededException(String, Long)}.
	 */
	@Deprecated
	public JmsAsyncHopsExceededException(final String destination, final Long hopsCount, final Object payload) {
		this(destination, hopsCount);
	}

	/**
	 * Sets the destination.
	 *
	 * @param destination The destination.
	 */
	public void setDestination(
			final String destination) {
		this.getInternalMessage().getParameters()[0] = destination;
	}

	/**
	 * Sets the hops count.
	 *
	 * @param hopsCount The hops count.
	 */
	public void setCurrentHops(
			final Long hopsCount) {
		this.getInternalMessage().getParameters()[1] = hopsCount;
	}

	/**
	 * Does nothing: kept for compatibility.
	 *
	 * @param      payload Ignored.
	 * @deprecated         The payload is no longer part of the exception.
	 */
	@Deprecated
	public void setPayload(
			final Object payload) {
		// The payload is not kept (it may hold sensitive data).
	}

	/**
	 * @see java.lang.Throwable#getLocalizedMessage()
	 */
	@Override
	public String getLocalizedMessage() {
		return this.getMessage();
	}

}
