package org.coldis.library.test.service.statistics;

import org.springframework.test.context.TestPropertySource;

/**
 * Re-runs every test in {@link StatisticsEventServiceComponentTest} against the
 * {@code on-conflict} upsert strategy ({@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}),
 * exercising the Postgres 9.5+ portable path. The default test class covers the {@code merge}
 * strategy (Postgres 17+).
 */
@TestPropertySource(
    properties = "org.coldis.library.service.statistics.event.upsert-strategy=on-conflict")
public class StatisticsEventServiceComponentOnConflictTest
    extends StatisticsEventServiceComponentTest {}
