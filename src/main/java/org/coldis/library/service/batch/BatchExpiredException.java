package org.coldis.library.service.batch;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;

public class BatchExpiredException extends BusinessException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -8968252851096665636L;

	/**
	 * Batch expired message.
	 */
	private static final SimpleMessage BATCH_EXPIRED_MESSAGE = new SimpleMessage("batch.expired", "The batch has expired.");

	/**
	 * Message, status and cause constructor.
	 *
	 * @param message    Message.
	 * @param statusCode Status code.
	 * @param cause      Cause.
	 */
	public BatchExpiredException(final Integer statusCode, final Throwable cause) {
		super(BatchExpiredException.BATCH_EXPIRED_MESSAGE, statusCode, cause);
	}

	/**
	 * Message and status constructor.
	 *
	 * @param message    Message.
	 * @param statusCode Status code.
	 */
	public BatchExpiredException(final Integer statusCode) {
		this(statusCode, null);
	}

	/**
	 * Message and cause constructor.
	 *
	 * @param message Message.
	 * @param cause   Cause.
	 */
	public BatchExpiredException(final Throwable cause) {
		this(BusinessException.DEFAULT_STATUS_CODE, cause);
	}

	/**
	 * Default constructor.
	 */
	public BatchExpiredException() {
		this(BusinessException.DEFAULT_STATUS_CODE);
	}

	/**
	 * Message constructor.
	 *
	 * @param message Message.
	 */
	public BatchExpiredException(final SimpleMessage message) {
		super(message);
	}

}
