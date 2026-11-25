package org.coldis.library.test.service.statistics;

import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.coldis.library.service.statistics.StatisticsEventServiceComponent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void testDeleteExpiredTriggerHasLastValueAndStaleFilterKeys() {
		final StatisticsEventServiceComponent service = new StatisticsEventServiceComponent();
		final JmsTemplateHelper jmsTemplateHelper = Mockito.mock(JmsTemplateHelper.class);
		ReflectionTestUtils.setField(service, "jmsTemplate", Mockito.mock(JmsTemplate.class));
		ReflectionTestUtils.setField(service, "jmsTemplateHelper", jmsTemplateHelper);

		service.scheduleDeleteExpired();
		service.scheduleDeleteExpired();

		final ArgumentCaptor<JmsMessage> captor = ArgumentCaptor.forClass(JmsMessage.class);
		Mockito.verify(jmsTemplateHelper, Mockito.times(2)).send(Mockito.any(JmsTemplate.class), captor.capture());
		final String key = captor.getAllValues().get(0).getLastValueKey();
		Assertions.assertNotNull(key);
		for (final JmsMessage<?> message : captor.getAllValues()) {
			Assertions.assertEquals(key, message.getLastValueKey());
			Assertions.assertEquals(key, message.getStaleFilterKey());
		}
	}

}
