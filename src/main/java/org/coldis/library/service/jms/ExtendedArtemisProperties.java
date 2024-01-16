package org.coldis.library.service.jms;

import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Artemis properties.
 */
@ConfigurationProperties(prefix = "spring.artemis")
public class ExtendedArtemisProperties extends ArtemisProperties {

	private Long clientFailureCheckPeriod;

	private Long connectionTTL;

	private Long callTimeout;

	private Long callFailoverTimeout;

	/**
	 * Gets the clientFailureCheckPeriod.
	 *
	 * @return The clientFailureCheckPeriod.
	 */
	public Long getClientFailureCheckPeriod() {
		return this.clientFailureCheckPeriod;
	}

	/**
	 * Sets the clientFailureCheckPeriod.
	 *
	 * @param clientFailureCheckPeriod New clientFailureCheckPeriod.
	 */
	public void setClientFailureCheckPeriod(
			final Long clientFailureCheckPeriod) {
		this.clientFailureCheckPeriod = clientFailureCheckPeriod;
	}

	/**
	 * Gets the connectionTTL.
	 *
	 * @return The connectionTTL.
	 */
	public Long getConnectionTTL() {
		return this.connectionTTL;
	}

	/**
	 * Sets the connectionTTL.
	 *
	 * @param connectionTTL New connectionTTL.
	 */
	public void setConnectionTTL(
			final Long connectionTTL) {
		this.connectionTTL = connectionTTL;
	}

	/**
	 * Gets the callTimeout.
	 *
	 * @return The callTimeout.
	 */
	public Long getCallTimeout() {
		return this.callTimeout;
	}

	/**
	 * Sets the callTimeout.
	 *
	 * @param callTimeout New callTimeout.
	 */
	public void setCallTimeout(
			final Long callTimeout) {
		this.callTimeout = callTimeout;
	}

	/**
	 * Gets the callFailoverTimeout.
	 *
	 * @return The callFailoverTimeout.
	 */
	public Long getCallFailoverTimeout() {
		return this.callFailoverTimeout;
	}

	/**
	 * Sets the callFailoverTimeout.
	 *
	 * @param callFailoverTimeout New callFailoverTimeout.
	 */
	public void setCallFailoverTimeout(
			final Long callFailoverTimeout) {
		this.callFailoverTimeout = callFailoverTimeout;
	}

}
