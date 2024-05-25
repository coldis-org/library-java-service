package org.coldis.library.service.jms;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Artemis properties.
 */
@Component
@Qualifier("defaultArtemisProperties")
@ConfigurationProperties(prefix = "spring.artemis")
public class ExtendedArtemisProperties extends ArtemisProperties {

	private Boolean useGlobalPools;

	private Boolean cacheDestinations;

	private Boolean cacheLargeMessagesClient;

	private Long clientFailureCheckPeriod;

	private Long connectionTTL;

	private Long callTimeout;

	private Long callFailoverTimeout;

	/**
	 * Gets the useGlobalPools.
	 *
	 * @return The useGlobalPools.
	 */
	public Boolean getUseGlobalPools() {
		return this.useGlobalPools;
	}

	/**
	 * Sets the useGlobalPools.
	 *
	 * @param useGlobalPools New useGlobalPools.
	 */
	public void setUseGlobalPools(
			final Boolean useGlobalPools) {
		this.useGlobalPools = useGlobalPools;
	}

	/**
	 * Gets the cacheDestinations.
	 *
	 * @return The cacheDestinations.
	 */
	public Boolean getCacheDestinations() {
		return this.cacheDestinations;
	}

	/**
	 * Sets the cacheDestinations.
	 *
	 * @param cacheDestinations New cacheDestinations.
	 */
	public void setCacheDestinations(
			final Boolean cacheDestinations) {
		this.cacheDestinations = cacheDestinations;
	}

	/**
	 * Gets the cacheLargeMessagesClient.
	 *
	 * @return The cacheLargeMessagesClient.
	 */
	public Boolean getCacheLargeMessagesClient() {
		return this.cacheLargeMessagesClient;
	}

	/**
	 * Sets the cacheLargeMessagesClient.
	 *
	 * @param cacheLargeMessagesClient New cacheLargeMessagesClient.
	 */
	public void setCacheLargeMessagesClient(
			final Boolean cacheLargeMessagesClient) {
		this.cacheLargeMessagesClient = cacheLargeMessagesClient;
	}

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
