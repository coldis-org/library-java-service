package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.helper.BufferedReducer;
import org.coldis.library.model.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PreDestroy;

/**
 * Statistics event summary buffer/processing service component. Owns the write
 * path: buffering summary deltas, flushing them to JMS in batches, applying
 * batched deltas to summary rows with pessimistic locking, and single-key
 * summary lookup/creation. The read/query side (period aggregation, comparison,
 * probability, z-score aggregators) lives in
 * {@link StatisticsEventSummaryServiceComponent}.
 */
@Component
@Qualifier(StatisticsEventSummaryBufferServiceComponent.QUALIFIER)
@ConditionalOnProperty(
		name = "org.coldis.configuration.service.statistics-enabled",
		matchIfMissing = false
)
public class StatisticsEventSummaryBufferServiceComponent {

	/**
	 * Bean qualifier. Pin this library bean with {@code @Qualifier(StatisticsEventSummaryBufferServiceComponent.QUALIFIER)}
	 * when an extended subclass makes injection by type ambiguous.
	 */
	public static final String QUALIFIER = "statisticsEventSummaryBufferServiceComponent";

	/** Logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsEventSummaryBufferServiceComponent.class);

	/** Statistics context configuration service component. */
	@Autowired
	private StatisticsContextConfigurationServiceComponent statisticsContextConfigurationServiceComponent;

	/** Statistics event summary repository. */
	@Autowired
	private StatisticsEventSummaryRepository statisticsEventSummaryRepository;

	/** JMS template. */
	@Autowired
	private JmsTemplate jmsTemplate;

	/** Internal queue for buffered summary delta batches. */
	private static final String SUMMARY_DELTA_BATCH_QUEUE = "statistics-event/summary/delta/batch";

	/** Maximum number of deltas sent in a single batch message. */
	@Value("${org.coldis.library.service.statistics.summary.buffer.batch-size:100}")
	private int deltaBatchSize;

	/** Local buffer for summary deltas. */
	private final BufferedReducer<StatisticsEventSummaryKey, StatisticsEventSummaryDelta> summaryDeltaBuffer = new BufferedReducer<>();

	/**
	 * Buffers a summary delta for deferred processing.
	 *
	 * @param key   Summary key.
	 * @param delta The delta.
	 */
	public void bufferDelta(
			final StatisticsEventSummaryKey key,
			final StatisticsEventSummaryDelta delta) {
		this.summaryDeltaBuffer.reduce(key, delta);
	}

	/**
	 * Drains the summary delta buffer and dispatches batches to the delta batch
	 * queue. Runs on a schedule and on shutdown.
	 */
	@PreDestroy
	@Scheduled(cron = "${org.coldis.library.service.statistics.summary.buffer.cron:0 */5 * * * *}")
	public void flushSummaryDeltaBuffer() {
		StatisticsEventSummaryBufferServiceComponent.LOGGER.debug("Flushing summary delta buffer.");
		final List<StatisticsEventSummaryDelta> drained = new ArrayList<>();
		this.summaryDeltaBuffer.flushLocalBuffer(drained::add);
		if (!drained.isEmpty()) {
			final int batchSize = Math.max(1, this.deltaBatchSize);
			for (int from = 0; from < drained.size(); from += batchSize) {
				final int to = Math.min(from + batchSize, drained.size());
				final ArrayList<StatisticsEventSummaryDelta> chunk = new ArrayList<>(drained.subList(from, to));
				this.jmsTemplate.convertAndSend(StatisticsEventSummaryBufferServiceComponent.SUMMARY_DELTA_BATCH_QUEUE, chunk);
			}
		}
	}

	/**
	 * Processes a buffered summary delta batch from the internal JMS queue. Each
	 * delta is applied within the same transaction; per-row locking is handled by
	 * {@link #applyDelta}.
	 *
	 * @param deltas The batch of deltas to apply.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	@JmsListener(
			destination = StatisticsEventSummaryBufferServiceComponent.SUMMARY_DELTA_BATCH_QUEUE,
			concurrency = "${org.coldis.library.service.statistics.summary.processsummarydelta.concurrency:1}",
			containerFactory = "${org.coldis.library.service.statistics.summary.container-factory:jmsListenerContainerFactory}"
	)
	public void processSummaryDeltaBatch(
			final List<StatisticsEventSummaryDelta> deltas) {
		if ((deltas != null) && !deltas.isEmpty()) {
			for (final StatisticsEventSummaryDelta delta : deltas) {
				StatisticsEventSummaryBufferServiceComponent.LOGGER.debug("Processing summary delta from JMS batch: context={}, dimension={}, dateTime={}",
						delta.getContext(), delta.getDimensionName(), delta.getDateTime());
				this.applyDelta(delta);
			}
		}
	}

	// ---- Find / create / update ----

	/**
	 * Finds a statistics event summary by its composite key.
	 *
	 * @param  id                Composite key.
	 * @param  forUpdate         If a pessimistic write lock is required.
	 * @return                   The statistics event summary.
	 * @throws BusinessException If the summary cannot be found.
	 */
	public StatisticsEventSummary findById(
			final StatisticsEventSummaryKey id,
			final Boolean forUpdate) throws BusinessException {
		final LocalDateTime truncatedDateTime = this.statisticsContextConfigurationServiceComponent.truncateDateTime(id.getContext(), id.getDateTime());
		final StatisticsEventSummary summary = Boolean.TRUE.equals(forUpdate)
				? this.statisticsEventSummaryRepository
						.findByIdForUpdateWait(new StatisticsEventSummaryKey(id.getContext(), id.getDimensionName(), truncatedDateTime), Duration.ofSeconds(11))
						.orElse(null)
				: this.statisticsEventSummaryRepository.findById(new StatisticsEventSummaryKey(id.getContext(), id.getDimensionName(), truncatedDateTime))
						.orElse(null);
		if (summary == null) {
			throw new BusinessException(new SimpleMessage("statistics.event.summary.notfound"), HttpStatus.NOT_FOUND.value());
		}
		return summary;
	}

	/**
	 * Finds or creates a statistics event summary. Hot path: a single
	 * {@code FOR UPDATE} read returns when the row already exists (the common
	 * case). Only on first-write does it fall back to
	 * {@code INSERT ... ON CONFLICT DO NOTHING} followed by a second read — atomic
	 * against concurrent inserters, no nested transactions, no exception bouncing.
	 *
	 * @param  context       Context.
	 * @param  dimensionName Dimension name.
	 * @param  dateTime      Date time (already truncated).
	 * @return               Found or created statistics event summary, locked for
	 *                       update.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public StatisticsEventSummary findOrCreate(
			final String context,
			final String dimensionName,
			final LocalDateTime dateTime) {
		return this.statisticsEventSummaryRepository.findByIdForUpdateOrCreate(
				new StatisticsEventSummaryKey(context, dimensionName, dateTime),
				() -> this.statisticsEventSummaryRepository.insertIfAbsent(context, dimensionName, dateTime));
	}

	/**
	 * Applies a buffered delta to a summary. Finds or creates the summary with
	 * pessimistic locking, then applies all count and weight changes atomically.
	 *
	 * @param delta The delta to apply.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public void applyDelta(
			final StatisticsEventSummaryDelta delta) {
		final StatisticsEventSummary summary = this.findOrCreate(delta.getContext(), delta.getDimensionName(), delta.getDateTime());
		// Apply count deltas.
		final Map<String, Long> counts = summary.getValueCounts();
		for (final Map.Entry<String, Long> entry : delta.getCountDeltas().entrySet()) {
			final long current = counts.getOrDefault(entry.getKey(), 0L);
			final long newValue = current + entry.getValue();
			if (newValue <= 0) {
				counts.remove(entry.getKey());
			}
			else {
				counts.put(entry.getKey(), newValue);
			}
		}
		// Apply weight deltas.
		final Map<String, BigDecimal> weights = summary.getValueWeights();
		for (final Map.Entry<String, BigDecimal> entry : delta.getWeightDeltas().entrySet()) {
			final BigDecimal current = weights.getOrDefault(entry.getKey(), BigDecimal.ZERO);
			final BigDecimal newValue = current.add(entry.getValue());
			if (newValue.compareTo(BigDecimal.ZERO) <= 0) {
				weights.remove(entry.getKey());
			}
			else {
				weights.put(entry.getKey(), newValue);
			}
		}
		// Recompute totals from maps to prevent drift.
		summary.setTotalCount(counts.values().stream().mapToLong(Long::longValue).sum());
		summary.setTotalWeight(weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
		this.statisticsEventSummaryRepository.save(summary);
	}
}
