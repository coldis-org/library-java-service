package org.coldis.library.test.service.statistics;

import org.coldis.library.service.cache.CacheHelper;
import org.coldis.library.service.statistics.StatisticsContextConfiguration;
import org.coldis.library.service.statistics.StatisticsContextConfigurationServiceComponent;
import org.coldis.library.test.StartTestWithContainerExtension;
import org.coldis.library.test.TestWithContainer;
import org.coldis.library.test.service.ContainerTestHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Tests {@link StatisticsContextConfigurationServiceComponent}: exact-match lookup, ancestor
 * fallback (drop the suffix after the last dash) and cache eviction on upsert.
 */
@TestWithContainer(reuse = true)
@ExtendWith(StartTestWithContainerExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class StatisticsContextConfigurationServiceComponentTest extends ContainerTestHelper {

  @Autowired private CacheHelper cacheHelper;

  @Autowired
  private StatisticsContextConfigurationServiceComponent statisticsContextConfigurationServiceComponent;

  @BeforeEach
  public void setUp() {
    this.truncateTables("statistics_context_configuration");
    this.cacheHelper.clearCaches();
  }

  @Test
  public void findByContextReturnsNullWhenAbsent() {
    Assertions.assertNull(
        this.statisticsContextConfigurationServiceComponent.findByContext("application-topsim"));
  }

  @Test
  public void findByContextReturnsConfigurationWhenPresent() {
    this.statisticsContextConfigurationServiceComponent.upsertConfiguration(
        new StatisticsContextConfiguration("application-topsim", 60L));

    final StatisticsContextConfiguration configuration =
        this.statisticsContextConfigurationServiceComponent.findByContext("application-topsim");

    Assertions.assertNotNull(configuration);
    Assertions.assertEquals("application-topsim", configuration.getContext());
    Assertions.assertEquals(60L, configuration.getTruncationMinutes());
  }

  @Test
  public void findByContextOrParentWalksUpToParentWhenLeafIsMissing() {
    this.statisticsContextConfigurationServiceComponent.upsertConfiguration(
        new StatisticsContextConfiguration("application-topsim", 60L));

    final StatisticsContextConfiguration configuration =
        this.statisticsContextConfigurationServiceComponent.findByContextOrParent(
            "application-topsim-new-organic");

    Assertions.assertNotNull(configuration, "should walk up to application-topsim");
    Assertions.assertEquals("application-topsim", configuration.getContext());
    Assertions.assertEquals(60L, configuration.getTruncationMinutes());
  }

  @Test
  public void findByContextOrParentPrefersClosestAncestor() {
    this.statisticsContextConfigurationServiceComponent.upsertConfiguration(
        new StatisticsContextConfiguration("application", 5L));
    this.statisticsContextConfigurationServiceComponent.upsertConfiguration(
        new StatisticsContextConfiguration("application-topsim-new", 30L));

    final StatisticsContextConfiguration configuration =
        this.statisticsContextConfigurationServiceComponent.findByContextOrParent(
            "application-topsim-new-organic");

    Assertions.assertEquals("application-topsim-new", configuration.getContext());
    Assertions.assertEquals(30L, configuration.getTruncationMinutes());
  }

  @Test
  public void findByContextOrParentReturnsNullWhenNoAncestorExists() {
    Assertions.assertNull(
        this.statisticsContextConfigurationServiceComponent.findByContextOrParent(
            "application-topsim-new-organic"));
  }

  @Test
  public void getTruncationMinutesFallsBackToDefaultWhenNoConfiguration() {
    final Long minutes =
        this.statisticsContextConfigurationServiceComponent.getTruncationMinutes("application-x");

    // Default is 15 (from @Value default in the component, also matches StatisticsContextConfiguration.DEFAULT_TRUNCATION_MINUTES).
    Assertions.assertEquals(15L, minutes);
  }

  @Test
  public void getTruncationMinutesUsesAncestorConfiguration() {
    this.statisticsContextConfigurationServiceComponent.upsertConfiguration(
        new StatisticsContextConfiguration("application-topsim", 60L));

    final Long minutes =
        this.statisticsContextConfigurationServiceComponent.getTruncationMinutes(
            "application-topsim-new-organic");

    Assertions.assertEquals(60L, minutes);
  }

  @Test
  public void upsertEvictsAllAncestorWalkResults() {
    // Walk caches an empty result for the leaf context.
    Assertions.assertNull(
        this.statisticsContextConfigurationServiceComponent.findByContextOrParent(
            "application-topsim-new-organic"));

    // After upserting an ancestor, the previously cached null must not survive.
    this.statisticsContextConfigurationServiceComponent.upsertConfiguration(
        new StatisticsContextConfiguration("application-topsim", 60L));

    final StatisticsContextConfiguration configuration =
        this.statisticsContextConfigurationServiceComponent.findByContextOrParent(
            "application-topsim-new-organic");
    Assertions.assertNotNull(configuration, "ancestor upsert should invalidate descendant cache");
    Assertions.assertEquals("application-topsim", configuration.getContext());
  }
}
