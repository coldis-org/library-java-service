package org.coldis.library.service.statistics;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Statistics context configuration service component. */
@Component
public class StatisticsContextConfigurationServiceComponent {

  /** Default truncation in minutes (configurable via property). */
  @Value(
      "${org.coldis.library.service.statistics.default-truncation-minutes:15}")
  private Long defaultTruncationMinutes;

  /** Statistics context configuration repository. */
  @Autowired
  private StatisticsContextConfigurationRepository statisticsContextConfigurationRepository;

  /**
   * Gets the truncation minutes for a context. Returns the default if no configuration exists.
   *
   * @param context Context.
   * @return The truncation minutes.
   */
  @Cacheable(
      cacheManager = "hoursExpirationLocalCacheManager",
      value = "StatisticsContextConfigurationServiceComponent.getTruncationMinutes")
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Long getTruncationMinutes(final String context) {
    return this.statisticsContextConfigurationRepository
        .findById(context)
        .map(StatisticsContextConfiguration::getTruncationMinutes)
        .orElse(this.defaultTruncationMinutes);
  }

  /**
   * Truncates a date time using the configured interval for the given context.
   *
   * @param context Context.
   * @param dateTime Date time.
   * @return Truncated date time.
   */
  public LocalDateTime truncateDateTime(final String context, final LocalDateTime dateTime) {
    return StatisticsEvent.truncateDateTime(dateTime, this.getTruncationMinutes(context));
  }

  /**
   * Finds a configuration by context.
   *
   * @param context Context.
   * @return The configuration, or null if not found.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public StatisticsContextConfiguration findByContext(final String context) {
    return this.statisticsContextConfigurationRepository.findById(context).orElse(null);
  }

  /**
   * Creates or updates a context configuration.
   *
   * @param configuration The configuration.
   * @return The saved configuration.
   */
  @CacheEvict(
      cacheManager = "hoursExpirationLocalCacheManager",
      value = "StatisticsContextConfigurationServiceComponent.getTruncationMinutes",
      key = "#configuration.context")
  @Transactional(propagation = Propagation.REQUIRED)
  public StatisticsContextConfiguration upsertConfiguration(
      final StatisticsContextConfiguration configuration) {
    return this.statisticsContextConfigurationRepository.save(configuration);
  }
}
