package org.coldis.library.test.service.statistics;

import java.util.ArrayList;
import java.util.List;

import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.statistics.StatisticsEventServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the keys set on the delete-expired trigger: a single constant key used both as the
 * last-value key (the broker keeps one pending trigger) and as the stale filter key (consumers drop a
 * trigger posted before the last one started processing, e.g. the copies every instance enqueues from
 * the same scheduled run).
 */
@DisplayName("StatisticsEventServiceComponent — delete-expired message keys")
public class StatisticsEventServiceComponentMessageKeyUnitTest {

	/**
	 * Tests that the scheduled delete-expired trigger carries both keys, with the same constant value.
	 */
	@Test
	@DisplayName("Every delete-expired trigger carries the same constant as last-value key and stale filter key")
	public void testDeleteExpiredTriggerHasLastValueAndStaleFilterKeys() {
		final StatisticsEventServiceComponent service = new StatisticsEventServiceComponent();
		final List<JmsMessage<?>> sentMessages = new ArrayList<>();
		final JmsTemplateHelper recordingJmsTemplateHelper = (jmsTemplate, message) -> sentMessages.add(message);
		ReflectionTestUtils.setField(service, "jmsTemplate", new JmsTemplate());
		ReflectionTestUtils.setField(service, "jmsTemplateHelper", recordingJmsTemplateHelper);

		service.scheduleDeleteExpired();
		service.scheduleDeleteExpired();

		Assertions.assertEquals(2, sentMessages.size());
		final String key = sentMessages.get(0).getLastValueKey();
		Assertions.assertNotNull(key);
		for (final JmsMessage<?> message : sentMessages) {
			Assertions.assertEquals(key, message.getLastValueKey());
			Assertions.assertEquals(key, message.getStaleFilterKey());
		}
	}

}
