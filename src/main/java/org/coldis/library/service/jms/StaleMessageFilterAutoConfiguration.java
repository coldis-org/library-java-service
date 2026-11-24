package org.coldis.library.service.jms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stale message filter auto-configuration. The filter synchronizes on its own
 * thread and no longer needs Spring scheduling; scheduling stays enabled when
 * the filter is on so services that relied on it for their own scheduled jobs
 * keep working.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
		name = "org.coldis.library.service.jms.stale-filter.enabled",
		havingValue = "true",
		matchIfMissing = false
)
public class StaleMessageFilterAutoConfiguration {

}
