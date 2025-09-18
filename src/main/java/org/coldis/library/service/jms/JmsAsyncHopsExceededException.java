package org.coldis.library.service.jms;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;

/**
 * JMS asynchronous hops exceeded exception.
 */
public class JmsAsyncHopsExceededException extends IntegrationException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 2574019464963826277L;

	/**
	 * Default constructor.
	 *
	 * @param hopsCount Maximum allowed hops.
	 * @param payload   Message payload.
	 */
	public JmsAsyncHopsExceededException(final String destination, final Long hopsCount, final Object payload) {
		super(new SimpleMessage("jms.async.hops.exceeded",
				"The maximum number of async hops (" + hopsCount + ") was exceeded for message '" + payload + "' in destination '" + destination + "'.",
				new Object[] { destination, hopsCount, payload }));
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
	 * Sets the payload.
	 *
	 * @param payload The payload.
	 */
	public void setPayload(
			final Object payload) {
		this.getInternalMessage().getParameters()[2] = payload;
	}

	/**
	 * @see org.coldis.library.exception.IntegrationException#getMessage()
	 */
	@Override
	public String getMessage() {
		return super.getMessage().formatted(this.getInternalMessage().getParameters());
	}

	/**
	 * @see java.lang.Throwable#getLocalizedMessage()
	 */
	@Override
	public String getLocalizedMessage() {
		return this.getMessage();
	}

}
