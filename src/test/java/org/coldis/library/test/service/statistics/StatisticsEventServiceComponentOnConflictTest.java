package org.coldis.library.test.service.statistics;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Re-runs every test in {@link StatisticsEventServiceComponentTest} against the
 * {@code on-conflict} upsert strategy ({@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}),
 * exercising the Postgres 9.5+ portable path. The default test class covers the {@code merge}
 * strategy (Postgres 17+).
 *
 * <p>The strategy is flipped via {@link ReflectionTestUtils} on the autowired bean rather than
 * via {@code @TestPropertySource}, so this subclass shares the same Spring context as its parent
 * (no context-cache miss, no lingering JMS broker overlap with concurrent contexts).
 */
public class StatisticsEventServiceComponentOnConflictTest
    extends StatisticsEventServiceComponentTest {

  @BeforeEach
  public void useOnConflictStrategy() {
    ReflectionTestUtils.setField(
        this.statisticsEventRepositoryImpl, "upsertStrategy", "on-conflict");
  }
}
