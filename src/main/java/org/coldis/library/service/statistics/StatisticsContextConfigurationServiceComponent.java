package org.coldis.library.service.statistics;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Statistics context configuration service component. */
@Component
@ConditionalOnProperty(name = "org.coldis.configuration.service.statistics-enabled", matchIfMissing = false)
public class StatisticsContextConfigurationServiceComponent {

  /** Default truncation in minutes (configurable via property). */
  @Value(
      "${org.coldis.library.service.statistics.default-truncation-minutes:15}")
  private Long defaultTruncationMinutes;

  /** Statistics context configuration repository. */
  @Autowired
  private StatisticsContextConfigurationRepository statisticsContextConfigurationRepository;

  /**
   * Gets the truncation minutes for a context. Falls back to the closest parent configuration
   * (stripping segments after the last dash) and finally to the default.
   *
   * @param context Context.
   * @return The truncation minutes.
   */
  @Cacheable(
      cacheManager = "hoursExpirationLocalCacheManager",
      value = "StatisticsContextConfigurationServiceComponent.getTruncationMinutes")
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Long getTruncationMinutes(final String context) {
    final StatisticsContextConfiguration configuration = this.findByContextOrParent(context);
    return (configuration == null
        ? this.defaultTruncationMinutes
        : configuration.getTruncationMinutes());
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
   * Finds a configuration by exact context match.
   *
   * @param context Context.
   * @return The configuration, or null if not found.
   */
  @Cacheable(
      cacheManager = "hoursExpirationLocalCacheManager",
      value = "StatisticsContextConfigurationServiceComponent.findByContext")
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public StatisticsContextConfiguration findByContext(final String context) {
    return this.statisticsContextConfigurationRepository.findById(context).orElse(null);
  }

  /**
   * Finds a configuration matching {@code context}, falling back to the closest parent context by
   * progressively dropping the suffix after the last dash. Returns {@code null} if no ancestor has
   * a configuration. Useful for hierarchical contexts (e.g. {@code application-product-recurrency-source})
   * so callers don't have to register every leaf permutation.
   *
   * @param context Context.
   * @return The closest matching configuration, or null.
   */
  @Cacheable(
      cacheManager = "hoursExpirationLocalCacheManager",
      value = "StatisticsContextConfigurationServiceComponent.findByContextOrParent")
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public StatisticsContextConfiguration findByContextOrParent(final String context) {
    String current = context;
    while ((current != null) && !current.isEmpty()) {
      final StatisticsContextConfiguration configuration =
          this.statisticsContextConfigurationRepository.findById(current).orElse(null);
      if (configuration != null) {
        return configuration;
      }
      final int lastDash = current.lastIndexOf('-');
      if (lastDash < 0) {
        return null;
      }
      current = current.substring(0, lastDash);
    }
    return null;
  }

  /**
   * Creates or updates a context configuration. Evicts all cached lookups since the upsert can
   * affect ancestor walks for unrelated contexts.
   *
   * @param configuration The configuration.
   * @return The saved configuration.
   */
  @CacheEvict(
      cacheManager = "hoursExpirationLocalCacheManager",
      value = {
        "StatisticsContextConfigurationServiceComponent.getTruncationMinutes",
        "StatisticsContextConfigurationServiceComponent.findByContext",
        "StatisticsContextConfigurationServiceComponent.findByContextOrParent"
      },
      allEntries = true)
  @Transactional(propagation = Propagation.REQUIRED)
  public StatisticsContextConfiguration upsertConfiguration(
      final StatisticsContextConfiguration configuration) {
    return this.statisticsContextConfigurationRepository.save(configuration);
  }
}
