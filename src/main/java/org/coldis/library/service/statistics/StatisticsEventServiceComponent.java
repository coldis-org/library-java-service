package org.coldis.library.service.statistics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.helper.ExtendedValidator;
import org.coldis.library.model.SimpleMessage;
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

  /** Batch size for expired event deletion. */
  @org.springframework.beans.factory.annotation.Value(
      "${org.coldis.library.service.statistics.event.deleteexpired.batch-size:1000}")
  private int deleteExpiredBatchSize;

  /** Validator. */
  @Autowired private ExtendedValidator validator;

  /** JMS template. */
  @Autowired private JmsTemplate jmsTemplate;

  /** Statistics event repository. */
  @Autowired private StatisticsEventRepository statisticsEventRepository;

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
   * Creates a statistics event.
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
   * Upserts a single statistics event. If the event already exists and the dimension value changed,
   * both the event and the summary are updated in the same transaction.
   *
   * @param statisticsEvent The statistics event.
   * @return The upserted statistics event.
   * @throws BusinessException If the event cannot be upserted.
   */

  @Transactional(propagation = Propagation.REQUIRED)
  public StatisticsEvent upsertStatisticsEvent(final StatisticsEvent statisticsEvent)
      throws BusinessException {
    StatisticsEventServiceComponent.LOGGER.debug(
        "Upserting event: context={}, owner={}, dimension={}, value={}",
        statisticsEvent.getContext(), statisticsEvent.getOwnerKey(),
        statisticsEvent.getDimensionName(), statisticsEvent.getDimensionValue());
    // Truncates the date time using the context configuration.
    statisticsEvent.setDateTime(
        this.statisticsContextConfigurationServiceComponent.truncateDateTime(
            statisticsEvent.getContext(), statisticsEvent.getDateTime()));
    this.validator.validateAndThrowViolations(statisticsEvent);
    // Checks if the event already exists before findOrCreate persists it.
    final StatisticsEvent existing =
        this.statisticsEventRepository
            .findByIdForUpdate(
                statisticsEvent.getContext(),
                statisticsEvent.getOwnerKey(),
                statisticsEvent.getDimensionName())
            .orElse(null);
    final String oldDimensionValue = existing != null ? existing.getDimensionValue() : null;
    final BigDecimal oldWeight = existing != null ? existing.getWeight() : null;
    final LocalDateTime oldDateTime = existing != null ? existing.getDateTime() : null;
    final StatisticsEvent actual = this.findOrCreate(statisticsEvent);
    final String newDimensionValue = statisticsEvent.getDimensionValue();
    final LocalDateTime newDateTime = statisticsEvent.getDateTime();
    // Updates the event.
    actual.setDateTime(newDateTime);
    actual.setDimensionValue(newDimensionValue);
    actual.setWeight(statisticsEvent.getWeight());
    actual.setExpiredAt(statisticsEvent.getExpiredAt());
    final StatisticsEvent saved = this.statisticsEventRepository.save(actual);
    // Buffers summary deltas for deferred processing.
    final boolean isNew = oldDimensionValue == null;
    final boolean dateTimeChanged = !isNew && !oldDateTime.equals(newDateTime);
    final boolean dimensionValueChanged = isNew || !oldDimensionValue.equals(newDimensionValue);
    final boolean weightChanged =
        !isNew && oldWeight.compareTo(statisticsEvent.getWeight()) != 0;
    if (dateTimeChanged) {
      // Decrement from old bucket.
      final StatisticsEventSummaryDelta oldDelta =
          new StatisticsEventSummaryDelta(
              statisticsEvent.getContext(), statisticsEvent.getDimensionName(), oldDateTime);
      oldDelta.addDelta(oldDimensionValue, -1, oldWeight.negate());
      this.statisticsEventSummaryServiceComponent.bufferDelta(oldDelta.getKey(), oldDelta);
      // Increment in new bucket.
      final StatisticsEventSummaryDelta newDelta =
          new StatisticsEventSummaryDelta(
              statisticsEvent.getContext(), statisticsEvent.getDimensionName(), newDateTime);
      newDelta.addDelta(newDimensionValue, 1, statisticsEvent.getWeight());
      this.statisticsEventSummaryServiceComponent.bufferDelta(newDelta.getKey(), newDelta);
    } else if (dimensionValueChanged) {
      // Dimension value changed within the same bucket.
      final StatisticsEventSummaryDelta delta =
          new StatisticsEventSummaryDelta(
              statisticsEvent.getContext(), statisticsEvent.getDimensionName(), newDateTime);
      if (!isNew) {
        delta.addDelta(oldDimensionValue, -1, oldWeight.negate());
      }
      delta.addDelta(newDimensionValue, 1, statisticsEvent.getWeight());
      this.statisticsEventSummaryServiceComponent.bufferDelta(delta.getKey(), delta);
    } else if (weightChanged) {
      // Only weight changed — adjust the weight delta without changing counts.
      final StatisticsEventSummaryDelta delta =
          new StatisticsEventSummaryDelta(
              statisticsEvent.getContext(), statisticsEvent.getDimensionName(), newDateTime);
      delta.addDelta(
          newDimensionValue, 0, statisticsEvent.getWeight().subtract(oldWeight));
      this.statisticsEventSummaryServiceComponent.bufferDelta(delta.getKey(), delta);
    }
    return saved;
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
      concurrency = "1")

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
