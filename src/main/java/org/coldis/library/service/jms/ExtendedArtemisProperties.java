package org.coldis.library.service.jms;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConnectionDetails;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Artemis properties.
 */
@Component
@Qualifier("defaultArtemisProperties")
@ConfigurationProperties(prefix = "spring.artemis")
public class ExtendedArtemisProperties extends ArtemisProperties implements ArtemisConnectionDetails {

	private Boolean useGlobalPools;

	private Boolean cacheDestinations;

	private Boolean cacheLargeMessagesClient;

	private Integer consumerWindowSize;

	private Integer consumerMaxRate;

	private Boolean blockOnDurableSend;

	private Boolean blockOnNonDurableSend;

	private Long clientFailureCheckPeriod;

	private Long connectionTTL;

	private Long callTimeout;

	private Long callFailoverTimeout;
	
	private Integer reconnectAttempts;

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
	 * Gets the consumerWindowSize.
	 *
	 * @return The consumerWindowSize.
	 */
	public Integer getConsumerWindowSize() {
		return this.consumerWindowSize;
	}

	/**
	 * Sets the consumerWindowSize.
	 *
	 * @param consumerWindowSize New consumerWindowSize.
	 */
	public void setConsumerWindowSize(
			final Integer consumerWindowSize) {
		this.consumerWindowSize = consumerWindowSize;
	}

	/**
	 * Gets the consumerMaxRate.
	 *
	 * @return The consumerMaxRate.
	 */
	public Integer getConsumerMaxRate() {
		return this.consumerMaxRate;
	}

	/**
	 * Sets the consumerMaxRate.
	 *
	 * @param consumerMaxRate New consumerMaxRate.
	 */
	public void setConsumerMaxRate(
			final Integer consumerMaxRate) {
		this.consumerMaxRate = consumerMaxRate;
	}

	/**
	 * Gets the blockOnDurableSend.
	 *
	 * @return The blockOnDurableSend.
	 */
	public Boolean getBlockOnDurableSend() {
		return this.blockOnDurableSend;
	}

	/**
	 * Sets the blockOnDurableSend.
	 *
	 * @param blockOnDurableSend New blockOnDurableSend.
	 */
	public void setBlockOnDurableSend(
			final Boolean blockOnDurableSend) {
		this.blockOnDurableSend = blockOnDurableSend;
	}

	/**
	 * Gets the blockOnNonDurableSend.
	 *
	 * @return The blockOnNonDurableSend.
	 */
	public Boolean getBlockOnNonDurableSend() {
		return this.blockOnNonDurableSend;
	}

	/**
	 * Sets the blockOnNonDurableSend.
	 *
	 * @param blockOnNonDurableSend New blockOnNonDurableSend.
	 */
	public void setBlockOnNonDurableSend(
			final Boolean blockOnNonDurableSend) {
		this.blockOnNonDurableSend = blockOnNonDurableSend;
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
	
	/**
	 * Gets the reconnectAttempts.
	 *
	 * @return The reconnectAttempts.
	 */
	public Integer getReconnectAttempts() {
		return this.reconnectAttempts;
	}

	/**
	 * Sets the reconnectAttempts.
	 *
	 * @param reconnectAttempts New reconnectAttempts.
	 */
	public void setReconnectAttempts(
            final Integer reconnectAttempts) {
		this.reconnectAttempts = reconnectAttempts;
	}
}
