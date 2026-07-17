package org.coldis.library.service.jms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stale message filter auto-configuration. Enables scheduling so the filter
 * synchronizes with the shared store (scheduling may also be enabled
 * elsewhere; this only guarantees it when the filter is on).
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
