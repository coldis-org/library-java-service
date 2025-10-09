package org.coldis.library.service.batch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.RetriableIn;
import org.coldis.library.model.Typable;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValue;
import org.coldis.library.persistence.keyvalue.KeyValueService;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.slack.SlackIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch helper.
 */
@RestController
@RequestMapping(path = "service-batch")
@ConditionalOnProperty(
		name = "org.coldis.configuration.service.batch-enabled",
		matchIfMissing = false
)
public class BatchService {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(BatchService.class);

	/**
	 * Batch key prefix.
	 */
	public static final String BATCH_KEY_PREFIX = "batch-record-";

	/**
	 * Delete queue.
	 */
	private static final String DELETE_QUEUE = "batch/delete";

	/**
	 * Batch record execute queue.
	 */
	private static final String RESUME_QUEUE = "batch/resume";

	/**
	 * Placeholder resolver.
	 */
	private static final PropertyPlaceholderHelper PLACEHOLDER_HELPER = new PropertyPlaceholderHelper("${", "}");

	/**
	 * JMS template.
	 */
	@Autowired(required = false)
	private JmsTemplate jmsTemplate;

	/**
	 * JMS template helper.
	 */
	@Autowired(required = false)
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Key batchExecutorValue service.
	 */
	@Autowired(required = false)
	private KeyValueService keyValueService;

	/**
	 * Slack integration.
	 */
	@Autowired
	private SlackIntegration slackIntegration;

	/**
	 * Gets the batch key.
	 *
	 * @param  keySuffix Batch key suffix.
	 * @return           Batch key.
	 */
	public String getKey(
			final String keySuffix) {
		return BatchService.BATCH_KEY_PREFIX + keySuffix;
	}

	/**
	 * @param executor Executor.
	 * @param action   Action.*@throws BusinessException Exception.
	 **/
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	private <Type> void log(
			final BatchExecutor<Type> executor,
			final BatchAction action) throws BusinessException {
		try {
			// Gets the template and Slack channel.
			final String template = executor.getMessagesTemplates().get(action);
			final String slackChannel = executor.getSlackChannels().get(action);
			// If the template is given.
			if (StringUtils.isNotBlank(template)) {
				// Gets the message properties.
				final String key = this.getKey(executor.getKeySuffix());
				final Type lastProcessed = executor.getLastProcessed();
				final Long duration = (executor.getLastStartedAt().until(DateTimeHelper.getCurrentLocalDateTime(), ChronoUnit.MINUTES));
				final Properties messageProperties = new Properties();
				messageProperties.put("key", key);
				messageProperties.put("lastProcessed", Objects.toString(lastProcessed));
				messageProperties.put("duration", duration.toString());
				// Gets the message from the template.
				final String message = BatchService.PLACEHOLDER_HELPER.replacePlaceholders(template, messageProperties);
				// If there is a message.
				if (StringUtils.isNotBlank(message)) {
					BatchService.LOGGER.info(message);
					// If there is a channel to use, sends the message.
					if (StringUtils.isNotBlank(slackChannel)) {
						this.slackIntegration.send(slackChannel, message);
					}
				}
			}
		}
		// Ignores errors.
		catch (final Throwable exception) {
			BatchService.LOGGER.error("Batch action could not be logged: " + exception.getLocalizedMessage());
			BatchService.LOGGER.debug("Batch action could not be logged.", exception);
		}

	}

	/**
	 * Processes a partial batch.
	 *
	 * @param  executor          Executor.
	 * @return                   The last processed id.
	 * @throws BusinessException If the batch could not be processed.
	 */
	@Transactional(
			propagation = Propagation.REQUIRES_NEW,
			timeoutString = "${org.coldis.library.service.transaction.minutes-timeout}"
	)
	protected <Type> Type executeBatch(
			final BatchExecutor<Type> batchExecutorValue) throws BusinessException {

		// Last processed.
		Type actualLastProcessed = batchExecutorValue.getLastProcessed();

		// Throws an exception if the batch has expired.
		if (batchExecutorValue.isExpired()) {
			throw new BatchExpiredException();
		}

		// For each item in the next batch.
		this.log(batchExecutorValue, BatchAction.GET);
		final List<Type> nextBatchToProcess = batchExecutorValue.get();
		for (final Type next : nextBatchToProcess) {
			batchExecutorValue.execute(next);
			this.log(batchExecutorValue, BatchAction.EXECUTE);
			actualLastProcessed = next;
			batchExecutorValue.setLastProcessedCount(batchExecutorValue.getLastProcessedCount() + 1);
		}

		// Returns the last processed. id.
		return actualLastProcessed;
	}

	/**
	 * Deletes a key entry.
	 *
	 * @param  key               The key.
	 * @throws BusinessException If the batch cannot be found.
	 */
	@JmsListener(
			destination = BatchService.DELETE_QUEUE,
			concurrency = "1-3"
	)
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.hour-timeout}"
	)
	private void deleteAsync(
			final Map<String, Object> message) throws BusinessException {
		final String key = (String) message.get("key");
		final Boolean onlyIfShouldBeCleaned = (Boolean) message.get("onlyIfShouldBeCleaned");
		if (this.keyValueService.getRepository().existsById(key)) {
			final KeyValue<Typable> batchExecutor = this.keyValueService.findById(key, LockBehavior.WAIT_AND_LOCK, false);
			final BatchExecutor<?> batchExecutorValue = (BatchExecutor<?>) batchExecutor.getValue();
			if (!onlyIfShouldBeCleaned || batchExecutorValue.shouldBeCleaned()) {
				this.keyValueService.delete(key);
			}
		}
	}

	/**
	 * Deletes a batch record.
	 *
	 * @param key Key.
	 */
	public void queueDeleteAsync(
			final String key,
			final Boolean onlyIfShouldBeCleaned) {
		this.jmsTemplateHelper.send(this.jmsTemplate, new JmsMessage<>().withDestination(BatchService.DELETE_QUEUE).withLastValueKey(key)
				.withMessage(Map.of("key", key, "onlyIfShouldBeCleaned", onlyIfShouldBeCleaned)));
	}

	/**
	 * Resumes a batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			noRollbackFor = Throwable.class,
			timeoutString = "${org.coldis.library.service.transaction.hour-timeout}"
	)
	@RequestMapping(
			method = RequestMethod.PUT,
			path = "{keySuffix}"
	)
	public <Type> void resume(
			@PathVariable
			final String keySuffix) throws BusinessException {
		// Synchronizes the batch (preventing to happen in parallel).
		final String key = this.getKey(keySuffix);
		final KeyValue<Typable> batchExecutor = this.keyValueService.findById(key, LockBehavior.LOCK_SKIP, true);
		if (batchExecutor != null) {
			@SuppressWarnings("unchecked")
			final BatchExecutor<Type> batchExecutorValue = (BatchExecutor<Type>) batchExecutor.getValue();

			// Deletes the batch if empty.
			if (batchExecutorValue == null) {
				this.keyValueService.delete(key);
			}
			// Resumes the batch if not empty.
			else if ((batchExecutorValue.getLastFinishedAt() == null)
					|| !batchExecutorValue.getLastFinishedAt().isAfter(batchExecutorValue.getLastStartedAt())) {
				try {
					// Gets the next id to be processed.
					Type previousLastProcessed = null;
					Type currentLastProcessed = batchExecutorValue.getLastProcessed();

					// Starts or resumes the batch.
					if (currentLastProcessed == null) {
						batchExecutorValue.start();
						this.log(batchExecutorValue, BatchAction.START);
					}
					else {
						batchExecutorValue.resume();
						this.log(batchExecutorValue, BatchAction.RESUME);
					}

					// Runs the batch until the next id does not change.
					final LocalDateTime batchStartedAt = DateTimeHelper.getCurrentLocalDateTime();
					final Type nextLastProcessed = this.executeBatch(batchExecutorValue);
					final LocalDateTime batchFinishedAt = DateTimeHelper.getCurrentLocalDateTime();
					batchExecutorValue.setLastBatchStartedAt(batchStartedAt);
					batchExecutorValue.setLastBatchFinishedAt(batchFinishedAt);
					batchExecutorValue.setLastProcessed(nextLastProcessed);
					previousLastProcessed = currentLastProcessed;
					currentLastProcessed = nextLastProcessed;

					// If there is no new data, finishes the batch.
					if (Objects.equals(previousLastProcessed, currentLastProcessed)) {
						batchExecutorValue.finish();
						this.log(batchExecutorValue, BatchAction.FINISH);
						batchExecutorValue.setLastFinishedAt(DateTimeHelper.getCurrentLocalDateTime());
					}
					else {
						this.queueResumeAsync(keySuffix, batchExecutorValue.getNextBatchStartingAt());
					}

					// Saves the executor.
					this.keyValueService.getRepository().save(batchExecutor);

				}
				// If there is an error in the batch, retry.
				catch (final Throwable throwable) {
					BatchService.LOGGER.error("Error processing batch '" + key + "': " + throwable.getLocalizedMessage());
					BatchService.LOGGER.debug("Error processing batch '" + key + "'.", throwable);
					if (!(throwable instanceof final RetriableIn retriableException) || (retriableException.getRetryIn() != null)) {
						this.queueResumeAsync(keySuffix, DateTimeHelper.getCurrentLocalDateTime().plus(batchExecutorValue.getActualDelayBetweenRuns()));
					}
					throw throwable;
				}
			}
		}

	}

	/**
	 * Processes a complete batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	@JmsListener(
			destination = BatchService.RESUME_QUEUE,
			concurrency = "${org.coldis.configuration.service.batch-concurrency:1-17}"
	)
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.hour-timeout}"
	)
	public <Type> void resumeAsync(
			final String keySuffix) throws BusinessException {
		try {
			this.resume(keySuffix);
		}
		catch (final BatchExpiredException exception) {
			BatchService.LOGGER.debug("Error processing batch '" + keySuffix + "'.", exception);
		}
	}

	/**
	 * Processes a complete batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	public <Type> void queueResumeAsync(
			final String keySuffix,
			final LocalDateTime scheduledFor) throws BusinessException {
		final Duration delay = Duration.ofMillis((scheduledFor == null) || scheduledFor.isBefore(DateTimeHelper.getCurrentLocalDateTime()) ? 0L
				: ChronoUnit.MILLIS.between(DateTimeHelper.getCurrentLocalDateTime(), scheduledFor));
		this.jmsTemplateHelper.send(this.jmsTemplate,
				new JmsMessage<>().withDestination(BatchService.RESUME_QUEUE).withFixedDelay(delay).withLastValueKey(keySuffix).withMessage(keySuffix));
	}

	/**
	 * Cancels a batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			noRollbackFor = Throwable.class,
			timeoutString = "${org.coldis.library.service.transaction.hour-timeout}"
	)
	@RequestMapping(method = RequestMethod.POST)
	public <Type> void start(
			@RequestBody
			final BatchExecutor<Type> executor,
			@RequestParam(defaultValue = "false")
			final Boolean restart,
			@RequestParam(defaultValue = "true")
			final Boolean useLastCountAsExpected) throws BusinessException {

		// Gets (and locks) the executor.
		final String key = this.getKey(executor.getKeySuffix());
		final KeyValue<Typable> batchExecutor = this.keyValueService.lock(key, LockBehavior.WAIT_AND_LOCK);

		// If there is no previous record for the batch, saves the given one.
		if (batchExecutor.getValue() == null) {
			batchExecutor.setValue(new BatchExecutor<>());
		}
		// Updates fields.
		BeanUtils.copyProperties(executor, batchExecutor.getValue(), "lastStartedAt", "lastProcessed", "lastFinishedAt", "lastCancelledAt",
				"lastProcessedCount");
		// If the executor should be restarted, resets it.
		@SuppressWarnings("unchecked")
		final BatchExecutor<Type> batchExecutorValue = (BatchExecutor<Type>) batchExecutor.getValue();
		if (restart || batchExecutorValue.isExpired() || batchExecutorValue.isFinished()) {
			batchExecutorValue.reset(useLastCountAsExpected);
		}

		// Saves and resumes the batch.
		this.keyValueService.getRepository().save(batchExecutor);
		this.queueResumeAsync(batchExecutorValue.getKeySuffix(), DateTimeHelper.getCurrentLocalDateTime());
	}

	/**
	 * Cancels a batch.
	 *
	 * @param  executor          Executor.
	 * @throws BusinessException If the batch fails.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			noRollbackFor = Throwable.class,
			timeoutString = "${org.coldis.library.service.transaction.hour-timeout}"
	)
	@RequestMapping(
			method = RequestMethod.DELETE,
			path = "{keySuffix}"
	)
	public <Type> void cancel(
			@PathVariable
			final String keySuffix) throws BusinessException {
		final String key = this.getKey(keySuffix);
		final KeyValue<Typable> batchExecutor = this.keyValueService.findById(key, LockBehavior.WAIT_AND_LOCK, false);
		@SuppressWarnings("unchecked")
		final BatchExecutor<Type> batchExecutorValue = (BatchExecutor<Type>) batchExecutor.getValue();
		batchExecutorValue.setLastCancelledAt(DateTimeHelper.getCurrentLocalDateTime());
		this.keyValueService.getRepository().save(batchExecutor);
	}

	/**
	 * Cleans old batches.
	 *
	 * @throws BusinessException If the batches cannot be cleaned.
	 */
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	@RequestMapping(
			method = RequestMethod.DELETE,
			path = "*/clean"
	)
	public void cleanAll(
			@RequestParam(defaultValue = "false")
			final Boolean sync) throws BusinessException {
		final List<KeyValue<Typable>> batchExecutors = this.keyValueService.findByKeyStart(BatchService.BATCH_KEY_PREFIX);
		for (final KeyValue<Typable> batchExecutor : batchExecutors) {
			if (sync) {
				this.deleteAsync(Map.of("key", batchExecutor.getKey(), "onlyIfShouldBeCleaned", false));
			}
			else {
				this.queueDeleteAsync(batchExecutor.getKey(), false);
			}
		}
	}

	/**
	 * Cleans old batches.
	 *
	 * @throws BusinessException If the batches cannot be cleaned.
	 */
	@Scheduled(cron = "0 */5 * * * *")
	@Transactional(
			propagation = Propagation.NOT_SUPPORTED,
			readOnly = true
	)
	@RequestMapping(
			method = RequestMethod.POST,
			path = "*/check"
	)
	public void checkAll() throws BusinessException {
		final List<KeyValue<Typable>> batchExecutors = this.keyValueService.findByKeyStart(BatchService.BATCH_KEY_PREFIX);
		for (final KeyValue<Typable> batchExecutor : batchExecutors) {
			final BatchExecutor<?> batchExecutorValue = (BatchExecutor<?>) batchExecutor.getValue();
			if ((batchExecutorValue != null)) {
				// Deletes old batches.
				if (batchExecutorValue.shouldBeCleaned()) {
					this.queueDeleteAsync(batchExecutor.getKey(), true);
				}
				// Makes sure non-expired are still running.
				else if (batchExecutorValue.getNextBatchStartingAt() != null) {
					this.queueResumeAsync(batchExecutorValue.getKeySuffix(), batchExecutorValue.getNextBatchStartingAt());
				}
			}
		}
	}

}
