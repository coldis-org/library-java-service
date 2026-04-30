package org.coldis.library.test.service.statistics;

import org.coldis.library.persistence.lock.LockType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Re-runs every test in {@link StatisticsEventServiceComponentTest} against the {@code TABLE}
 * lock type (row locks on the {@code lock_key} table), exercising the alternate
 * {@code LockServiceComponent} acquisition path. The default test class covers {@code ADVISORY}.
 *
 * <p>The lock type is flipped via {@link ReflectionTestUtils} on the autowired bean rather than
 * via {@code @TestPropertySource}, so this subclass shares the same Spring context as its parent
 * (no context-cache miss, no lingering JMS broker overlap with concurrent contexts).
 */
public class StatisticsEventServiceComponentTableLockTest
    extends StatisticsEventServiceComponentTest {

  @BeforeEach
  public void useTableLockType() {
    ReflectionTestUtils.setField(this.statisticsEventServiceComponent, "lockType", LockType.TABLE);
  }
}
