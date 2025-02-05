package org.coldis.library.service.ratelimit;

import java.util.Set;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.SimpleMessage;

/**
 * Rate limit exception.
 */
public class RateLimitException extends BusinessException {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = -3722141293051171506L;

	/**
	 * Code.
	 */
	private static final Integer DEFAULT_STATUS_CODE = 429;

	/**
	 * No arguments constructor.
	 */
	public RateLimitException(final String name, final Long limit) {
		super(Set.of(new SimpleMessage("service.ratelimit.exceeded", "Limit '" + limit + "' has been reached for method '" + name + "'.",
				new Object[] { name, limit })), RateLimitException.DEFAULT_STATUS_CODE, null);
	}

}
