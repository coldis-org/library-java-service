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

	private Boolean compressLargeMessage;

	private Integer compressionLevel;

	private Integer consumerWindowSize;

	private Integer consumerMaxRate;

	private Integer producerWindowSize;

	private Integer producerMaxRate;

	private Integer confirmationWindowSize;

	private Integer ackBatchSize;

	private Integer dupsAckBatchSize;

	private Boolean blockOnAcknowledge;

	private Boolean blockOnDurableSend;

	private Boolean blockOnNonDurableSend;

	private Long clientFailureCheckPeriod;

	private Long connectionTTL;

	private Long callTimeout;

	private Long callFailoverTimeout;

	private Integer reconnectAttempts;

	private Long retryInterval;

	private Double retryIntervalMultiplier;

	/** Max messages per task. */
	private Integer maxMessagesPerTask;

	/** Back-off initial interval. */
	private Long backoffInitialInterval;

	/** Back-off multiplier. */
	private Double backoffMultiplier;

	/** Back-off max elapsed time. */
	private Long backoffMaxElapsedTime;

	/** Cache level. */
	private Integer cacheLevel;

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
	 * Gets the compressLargeMessages.
	 *
	 * @return The compressLargeMessages.
	 */
	public Boolean getCompressLargeMessage() {
		return this.compressLargeMessage;
	}

	/**
	 * Sets the compressLargeMessages.
	 *
	 * @param compressLargeMessages New compressLargeMessages.
	 */
	public void setCompressLargeMessage(
			final Boolean compressLargeMessage) {
		this.compressLargeMessage = compressLargeMessage;
	}

	/**
	 * Gets the compressionLevel.
	 *
	 * @return The compressionLevel.
	 */
	public Integer getCompressionLevel() {
		return this.compressionLevel;
	}

	/**
	 * Sets the compressionLevel.
	 *
	 * @param compressionLevel New compressionLevel.
	 */
	public void setCompressionLevel(
			final Integer compressionLevel) {
		this.compressionLevel = compressionLevel;
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
	 * Gets the producerWindowSize.
	 *
	 * @return The producerWindowSize.
	 */
	public Integer getProducerWindowSize() {
		return this.producerWindowSize;
	}

	/**
	 * Sets the producerWindowSize.
	 *
	 * @param producerWindowSize New producerWindowSize.
	 */
	public void setProducerWindowSize(
			final Integer producerWindowSize) {
		this.producerWindowSize = producerWindowSize;
	}

	/**
	 * Gets the producerMaxRate.
	 *
	 * @return The producerMaxRate.
	 */
	public Integer getProducerMaxRate() {
		return this.producerMaxRate;
	}

	/**
	 * Sets the producerMaxRate.
	 *
	 * @param producerMaxRate New producerMaxRate.
	 */
	public void setProducerMaxRate(
			final Integer producerMaxRate) {
		this.producerMaxRate = producerMaxRate;
	}

	/**
	 * Gets the confirmationWindowSize.
	 *
	 * @return The confirmationWindowSize.
	 */
	public Integer getConfirmationWindowSize() {
		return this.confirmationWindowSize;
	}

	/**
	 * Sets the confirmationWindowSize.
	 *
	 * @param confirmationWindowSize New confirmationWindowSize.
	 */
	public void setConfirmationWindowSize(
			final Integer confirmationWindowSize) {
		this.confirmationWindowSize = confirmationWindowSize;
	}

	/**
	 * Gets the ackBatchSize.
	 *
	 * @return The ackBatchSize.
	 */
	public Integer getAckBatchSize() {
		return this.ackBatchSize;
	}

	/**
	 * Sets the ackBatchSize.
	 *
	 * @param ackBatchSize New ackBatchSize.
	 */
	public void setAckBatchSize(
			final Integer ackBatchSize) {
		this.ackBatchSize = ackBatchSize;
	}

	/**
	 * Gets the dupsAckBatchSize.
	 *
	 * @return The dupsAckBatchSize.
	 */
	public Integer getDupsAckBatchSize() {
		return this.dupsAckBatchSize;
	}

	/**
	 * Sets the dupsAckBatchSize.
	 *
	 * @param dupsAckBatchSize New dupsAckBatchSize.
	 */
	public void setDupsAckBatchSize(
			final Integer dupsAckBatchSize) {
		this.dupsAckBatchSize = dupsAckBatchSize;
	}

	/**
	 * Gets the blockOnAcknowledge.
	 *
	 * @return The blockOnAcknowledge.
	 */
	public Boolean getBlockOnAcknowledge() {
		return this.blockOnAcknowledge;
	}

	/**
	 * Sets the blockOnAcknowledge.
	 *
	 * @param blockOnAcknowledge New blockOnAcknowledge.
	 */
	public void setBlockOnAcknowledge(
			final Boolean blockOnAcknowledge) {
		this.blockOnAcknowledge = blockOnAcknowledge;
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

	/**
	 * Gets the retryInterval.
	 *
	 * @return The retryInterval.
	 */
	public Long getRetryInterval() {
		return this.retryInterval;
	}

	/**
	 * Sets the retryInterval.
	 *
	 * @param retryInterval New retryInterval.
	 */
	public void setRetryInterval(
			final Long retryInterval) {
		this.retryInterval = retryInterval;
	}

	/**
	 * Gets the retryIntervalMultiplier.
	 *
	 * @return The retryIntervalMultiplier.
	 */
	public Double getRetryIntervalMultiplier() {
		return this.retryIntervalMultiplier;
	}

	/**
	 * Sets the retryIntervalMultiplier.
	 *
	 * @param retryIntervalMultiplier New retryIntervalMultiplier.
	 */
	public void setRetryIntervalMultiplier(
			final Double retryIntervalMultiplier) {
		this.retryIntervalMultiplier = retryIntervalMultiplier;
	}

	/**
	 * Gets the maxMessagesPerTask.
	 *
	 * @return The maxMessagesPerTask.
	 */
	public Integer getMaxMessagesPerTask() {
		return this.maxMessagesPerTask;
	}

	/**
	 * Sets the maxMessagesPerTask.
	 *
	 * @param maxMessagesPerTask New maxMessagesPerTask.
	 */
	public void setMaxMessagesPerTask(
			final Integer maxMessagesPerTask) {
		this.maxMessagesPerTask = maxMessagesPerTask;
	}

	/**
	 * Gets the backoffInitialInterval.
	 *
	 * @return The backoffInitialInterval.
	 */
	public Long getBackoffInitialInterval() {
		return this.backoffInitialInterval;
	}

	/**
	 * Sets the backoffInitialInterval.
	 *
	 * @param backoffInitialInterval New backoffInitialInterval.
	 */
	public void setBackoffInitialInterval(
			final Long backoffInitialInterval) {
		this.backoffInitialInterval = backoffInitialInterval;
	}

	/**
	 * Gets the backoffMultiplier.
	 *
	 * @return The backoffMultiplier.
	 */
	public Double getBackoffMultiplier() {
		return this.backoffMultiplier;
	}

	/**
	 * Sets the backoffMultiplier.
	 *
	 * @param backoffMultiplier New backoffMultiplier.
	 */
	public void setBackoffMultiplier(
			final Double backoffMultiplier) {
		this.backoffMultiplier = backoffMultiplier;
	}

	/**
	 * Gets the backoffMaxElapsedTime.
	 *
	 * @return The backoffMaxElapsedTime.
	 */
	public Long getBackoffMaxElapsedTime() {
		return this.backoffMaxElapsedTime;
	}

	/**
	 * Sets the backoffMaxElapsedTime.
	 *
	 * @param backoffMaxElapsedTime New backoffMaxElapsedTime.
	 */
	public void setBackoffMaxElapsedTime(
			final Long backoffMaxElapsedTime) {
		this.backoffMaxElapsedTime = backoffMaxElapsedTime;
	}

	/**
	 * Gets the cacheLevel.
	 *
	 * @return The cacheLevel.
	 */
	public Integer getCacheLevel() {
		return this.cacheLevel;
	}

	/**
	 * Sets the cacheLevel.
	 *
	 * @param cacheLevel New cacheLevel.
	 */
	public void setCacheLevel(
			final Integer cacheLevel) {
		this.cacheLevel = cacheLevel;
	}

}
