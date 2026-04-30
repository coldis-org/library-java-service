package org.coldis.library.service.statistics;

import jakarta.annotation.PreDestroy;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.BufferedReducer;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.helper.ExtendedValidator;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.persistence.lock.AdvisoryLockServiceComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics event service component. Contains the business logic for statistics event operations
 * (find, create, upsert) and triggers summary updates within the same transaction. Buffering is an
 * internal implementation detail — callers use {@link #upsertAllStatisticsEvents(List)} and the
 * component handles deduplication and deferred persistence via an internal JMS queue.
 */
@Component
@ConditionalOnProperty(name = "org.coldis.configuration.service.statistics-enabled", matchIfMissing = false)
public class StatisticsEventServiceComponent {

  /** Logger. */
  private static final Logger LOGGER =
      LoggerFactory.getLogger(StatisticsEventServiceComponent.class);

  /** Internal queue for expired event deletion. */
  private static final String DELETE_EXPIRED_QUEUE = "statistics-event/delete/expired";

  /** Internal queue for buffered upsert batches. */
  private static final String UPSERT_BATCH_QUEUE = "statistics-event/upsert/batch";

  /** Advisory lock namespace for statistics-event upsert serialization. ASCII for "STAT". */
  private static final int LOCK_NAMESPACE = 0x53544154;

  /** Advisory lock key prefix for statistics-event upsert serialization. */
  private static final String LOCK_KEY_PREFIX = "statistics-event:";

  /** Batch size for expired event deletion. */
  @org.springframework.beans.factory.annotation.Value(
      "${org.coldis.library.service.statistics.event.deleteexpired.batch-size:1000}")
  private int deleteExpiredBatchSize;

  /** Maximum number of events sent in a single upsert batch message. */
  @org.springframework.beans.factory.annotation.Value(
      "${org.coldis.library.service.statistics.event.buffer.batch-size:100}")
  private int upsertBatchSize;

  /** In-memory buffer for upserts pending flush. */
  private final BufferedReducer<StatisticsEventKey, StatisticsEvent> eventBuffer =
      new BufferedReducer<>();

  /** Validator. */
  @Autowired private ExtendedValidator validator;

  /** JMS template. */
  @Autowired private JmsTemplate jmsTemplate;

  /** Statistics event repository. */
  @Autowired private StatisticsEventRepository statisticsEventRepository;

  /** Advisory lock service. */
  @Autowired private AdvisoryLockServiceComponent advisoryLockService;

  /** Statistics context configuration service component. */
  @Autowired
  private StatisticsContextConfigurationServiceComponent
      statisticsContextConfigurationServiceComponent;

  /** Statistics event summary service component. */
  @Autowired private StatisticsEventSummaryServiceComponent statisticsEventSummaryServiceComponent;

  /**
   * Finds a statistics event by its composite key.
   *
   * @param id Composite key.
   * @param forUpdate If a pessimistic write lock is required.
   * @return The statistics event.
   * @throws BusinessException If the event cannot be found.
   */

  public StatisticsEvent findById(final StatisticsEventKey id, final Boolean forUpdate)
      throws BusinessException {
    final StatisticsEvent statisticsEvent =
        Boolean.TRUE.equals(forUpdate)
            ? this.statisticsEventRepository
                .findByIdForUpdate(id.getContext(), id.getOwnerKey(), id.getDimensionName())
                .orElse(null)
            : this.statisticsEventRepository.findById(id).orElse(null);
    if (statisticsEvent == null) {
      throw new BusinessException(
          new SimpleMessage("statistics.event.notfound"), HttpStatus.NOT_FOUND.value());
    }
    return statisticsEvent;
  }

  /**
   * Creates a statistics event in a separate transaction so duplicate-key violations don't poison
   * the outer transaction.
   *
   * @param statisticsEvent Statistics event.
   * @return The created statistics event.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected StatisticsEvent create(final StatisticsEvent statisticsEvent) {
    return this.statisticsEventRepository.saveAndFlush(statisticsEvent);
  }

  /**
   * Finds or creates a statistics event. Uses pessimistic locking to prevent duplicates.
   *
   * @param statisticsEvent Statistics event data.
   * @return Found or created statistics event.
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      noRollbackFor = DataIntegrityViolationException.class)
  protected StatisticsEvent findOrCreate(final StatisticsEvent statisticsEvent) {
    // Tries to find the statistics event.
    StatisticsEvent actual =
        this.statisticsEventRepository
            .findByIdForUpdate(
                statisticsEvent.getContext(),
                statisticsEvent.getOwnerKey(),
                statisticsEvent.getDimensionName())
            .orElse(null);
    // If there is no statistics event.
    if (actual == null) {
      // Tries creating the statistics event.
      try {
        actual = this.create(statisticsEvent);
      } catch (final Exception exception) {
        StatisticsEventServiceComponent.LOGGER.warn(
            String.format(
                "Could not create statistics event: %s", exception.getLocalizedMessage()));
        StatisticsEventServiceComponent.LOGGER.debug(
            "Could not create statistics event.", exception);
      }
      // Tries to find the statistics event again.
      actual =
          this.statisticsEventRepository
              .findByIdForUpdate(
                  statisticsEvent.getContext(),
                  statisticsEvent.getOwnerKey(),
                  statisticsEvent.getDimensionName())
              .orElse(null);
    }
    // If the statistics event was not created.
    if (actual == null) {
      throw new IntegrationException(new SimpleMessage("statistics.event.creation.error"));
    }
    return actual;
  }

  /**
   * Buffers a statistics event for deferred upsert. Validates and truncates synchronously so
   * callers see input errors immediately, but the actual DB write happens asynchronously via the
   * upsert batch queue.
   *
   * @param statisticsEvent The statistics event.
   * @throws BusinessException If validation fails.
   */
  public void upsertStatisticsEvent(final StatisticsEvent statisticsEvent)
      throws BusinessException {
    StatisticsEventServiceComponent.LOGGER.debug(
        "Buffering event: context={}, owner={}, dimension={}, value={}",
        statisticsEvent.getContext(), statisticsEvent.getOwnerKey(),
        statisticsEvent.getDimensionName(), statisticsEvent.getDimensionValue());
    // Truncates the date time using the context configuration.
    statisticsEvent.setDateTime(
        this.statisticsContextConfigurationServiceComponent.truncateDateTime(
            statisticsEvent.getContext(), statisticsEvent.getDateTime()));
    // Default emittedAt to now so the latest-emission-wins ordering invariant holds even when
    // callers don't set it.
    if (statisticsEvent.getEmittedAt() == null) {
      statisticsEvent.setEmittedAt(DateTimeHelper.getCurrentLocalDateTime());
    }
    this.validator.validateAndThrowViolations(statisticsEvent);
    this.eventBuffer.reduce(statisticsEvent.getId(), statisticsEvent);
  }

  /**
   * Drains the upsert buffer and dispatches batches to the upsert batch queue. Runs on a schedule
   * and on shutdown.
   */
  @PreDestroy
  @Scheduled(
      cron =
          "${org.coldis.library.service.statistics.event.buffer.cron:0 * * * * *}")
  public void flushEventBuffer() {
    StatisticsEventServiceComponent.LOGGER.debug("Flushing event buffer.");
    final List<StatisticsEvent> drained = new ArrayList<>();
    this.eventBuffer.flushLocalBuffer(drained::add);
    if (drained.isEmpty()) {
      return;
    }
    final int batchSize = Math.max(1, this.upsertBatchSize);
    for (int from = 0; from < drained.size(); from += batchSize) {
      final int to = Math.min(from + batchSize, drained.size());
      final ArrayList<StatisticsEvent> chunk = new ArrayList<>(drained.subList(from, to));
      this.jmsTemplate.convertAndSend(
          StatisticsEventServiceComponent.UPSERT_BATCH_QUEUE, (Serializable) chunk);
    }
  }

  /**
   * Processes a buffered upsert batch from the internal JMS queue. Acquires per-key advisory locks
   * to serialize cross-instance writers, then runs a single MERGE statement that applies the
   * latest-emission-wins ordering rule and reports back the pre-update state. Summary deltas are
   * computed from the result and buffered.
   *
   * @param events The batch of events to upsert.
   */
  @Transactional(propagation = Propagation.REQUIRED)
  @JmsListener(
      destination = StatisticsEventServiceComponent.UPSERT_BATCH_QUEUE,
      concurrency =
          "${org.coldis.library.service.statistics.event.buffer.processupsertbatch.concurrency:1}",
      containerFactory =
          "${org.coldis.library.service.statistics.container-factory:jmsListenerContainerFactory}")
  public void processEventUpsertBatch(final List<StatisticsEvent> events) {
    if (events == null || events.isEmpty()) {
      return;
    }
    // Serialize cross-instance writers per-key. While we hold these locks, no other instance can
    // run the upsert path for the same keys — so the MERGE's old-state snapshot is stable.
    final List<String> lockKeys = new ArrayList<>(events.size());
    for (final StatisticsEvent event : events) {
      lockKeys.add(
          StatisticsEventServiceComponent.LOCK_KEY_PREFIX
              + event.getContext()
              + "|" + event.getOwnerKey()
              + "|" + event.getDimensionName());
    }
    this.advisoryLockService.lockKeys(StatisticsEventServiceComponent.LOCK_NAMESPACE, lockKeys);
    // Single round-trip: insert/update + capture old state for delta computation.
    final List<StatisticsEventUpsertResult> results =
        this.statisticsEventRepository.upsertBatch(events);
    final Map<StatisticsEventKey, StatisticsEventUpsertResult> resultByKey =
        new HashMap<>(results.size());
    for (final StatisticsEventUpsertResult result : results) {
      resultByKey.put(result.key(), result);
    }
    for (final StatisticsEvent incoming : events) {
      final StatisticsEventUpsertResult result = resultByKey.get(incoming.getId());
      if (result == null || !result.applied()) {
        StatisticsEventServiceComponent.LOGGER.debug(
            "Dropping stale event: context={}, owner={}, dimension={}, incomingEmittedAt={}",
            incoming.getContext(), incoming.getOwnerKey(), incoming.getDimensionName(),
            incoming.getEmittedAt());
        continue;
      }
      this.bufferSummaryDeltasForUpsert(result, incoming);
    }
  }

  /**
   * Computes and buffers the summary deltas implied by applying {@code incoming} given the
   * pre-update state captured in {@code result}.
   */
  private void bufferSummaryDeltasForUpsert(
      final StatisticsEventUpsertResult result, final StatisticsEvent incoming) {
    final String newDimensionValue = incoming.getDimensionValue();
    final LocalDateTime newDateTime = incoming.getDateTime();
    final boolean isNew = result.wasInserted();
    final String oldDimensionValue = isNew ? null : result.oldDimensionValue();
    final BigDecimal oldWeight = isNew ? null : result.oldWeight();
    final LocalDateTime oldDateTime = isNew ? null : result.oldDateTime();
    final boolean dateTimeChanged = !isNew && !oldDateTime.equals(newDateTime);
    final boolean dimensionValueChanged = isNew || !oldDimensionValue.equals(newDimensionValue);
    final boolean weightChanged =
        !isNew && oldWeight.compareTo(incoming.getWeight()) != 0;
    if (dateTimeChanged) {
      // Decrement from old bucket.
      final StatisticsEventSummaryDelta oldDelta =
          new StatisticsEventSummaryDelta(
              incoming.getContext(), incoming.getDimensionName(), oldDateTime);
      oldDelta.addDelta(oldDimensionValue, -1, oldWeight.negate());
      this.statisticsEventSummaryServiceComponent.bufferDelta(oldDelta.getKey(), oldDelta);
      // Increment in new bucket.
      final StatisticsEventSummaryDelta newDelta =
          new StatisticsEventSummaryDelta(
              incoming.getContext(), incoming.getDimensionName(), newDateTime);
      newDelta.addDelta(newDimensionValue, 1, incoming.getWeight());
      this.statisticsEventSummaryServiceComponent.bufferDelta(newDelta.getKey(), newDelta);
    } else if (dimensionValueChanged) {
      final StatisticsEventSummaryDelta delta =
          new StatisticsEventSummaryDelta(
              incoming.getContext(), incoming.getDimensionName(), newDateTime);
      if (!isNew) {
        delta.addDelta(oldDimensionValue, -1, oldWeight.negate());
      }
      delta.addDelta(newDimensionValue, 1, incoming.getWeight());
      this.statisticsEventSummaryServiceComponent.bufferDelta(delta.getKey(), delta);
    } else if (weightChanged) {
      final StatisticsEventSummaryDelta delta =
          new StatisticsEventSummaryDelta(
              incoming.getContext(), incoming.getDimensionName(), newDateTime);
      delta.addDelta(
          newDimensionValue, 0, incoming.getWeight().subtract(oldWeight));
      this.statisticsEventSummaryServiceComponent.bufferDelta(delta.getKey(), delta);
    }
  }

  /**
   * Deletes a statistics event and decrements the corresponding summary.
   *
   * @param statisticsEvent The statistics event to delete.
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteStatisticsEvent(final StatisticsEvent statisticsEvent) {
    this.statisticsEventRepository.delete(statisticsEvent);
    final StatisticsEventSummaryDelta delta =
        new StatisticsEventSummaryDelta(
            statisticsEvent.getContext(),
            statisticsEvent.getDimensionName(),
            statisticsEvent.getDateTime());
    delta.addDelta(
        statisticsEvent.getDimensionValue(), -1, statisticsEvent.getWeight().negate());
    this.statisticsEventSummaryServiceComponent.bufferDelta(delta.getKey(), delta);
  }

  /**
   * Upserts multiple statistics events.
   *
   * @param statisticsEvents The list of statistics events.
   * @throws BusinessException If an event cannot be upserted.
   */
  public void upsertAllStatisticsEvents(final List<StatisticsEvent> statisticsEvents)
      throws BusinessException {
    for (final StatisticsEvent statisticsEvent : statisticsEvents) {
      this.upsertStatisticsEvent(statisticsEvent);
    }
  }

  /**
   * Scheduled trigger that sends a message to the delete-expired queue to start the deletion
   * process.
   */
  @Scheduled(
      cron =
          "${org.coldis.library.service.statistics.event.deleteexpired.cron:0 0 3 * * *}")
  public void scheduleDeleteExpired() {
    this.jmsTemplate.convertAndSend(
        StatisticsEventServiceComponent.DELETE_EXPIRED_QUEUE, "delete-expired");
  }

  /**
   * Deletes a batch of expired statistics events. If rows were deleted, sends another message to
   * continue the loop via JMS.
   *
   * @param message Trigger message.
   */

  @Transactional(propagation = Propagation.REQUIRED)
  @JmsListener(
      destination = StatisticsEventServiceComponent.DELETE_EXPIRED_QUEUE,
      concurrency = "1",
      containerFactory = "${org.coldis.library.service.statistics.container-factory:jmsListenerContainerFactory}")

  public void deleteExpiredEvents(final String message) {
    final int deleted =
        this.statisticsEventRepository.deleteExpired(
            DateTimeHelper.getCurrentLocalDateTime(), this.deleteExpiredBatchSize);
    if (deleted > 0) {
      this.jmsTemplate.convertAndSend(
          StatisticsEventServiceComponent.DELETE_EXPIRED_QUEUE, "delete-expired");
    }
  }
}
