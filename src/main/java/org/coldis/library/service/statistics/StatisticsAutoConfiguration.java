package org.coldis.library.service.statistics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for the statistics module.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "org.coldis.configuration.service.statistics-enabled",
    matchIfMissing = false
)
public class StatisticsAutoConfiguration {}
