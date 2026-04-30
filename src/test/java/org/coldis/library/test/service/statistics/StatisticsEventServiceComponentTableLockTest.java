package org.coldis.library.test.service.statistics;

import org.springframework.test.context.TestPropertySource;

/**
 * Re-runs every test in {@link StatisticsEventServiceComponentTest} against the {@code TABLE}
 * lock type (row locks on the {@code lock_key} table), exercising the alternate
 * {@code LockServiceComponent} acquisition path. The default test class covers {@code ADVISORY}.
 */
@TestPropertySource(
    properties = "org.coldis.library.service.statistics.event.lock-type=TABLE")
public class StatisticsEventServiceComponentTableLockTest
    extends StatisticsEventServiceComponentTest {}
