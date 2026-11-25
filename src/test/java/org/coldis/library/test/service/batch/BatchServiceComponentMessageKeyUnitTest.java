package org.coldis.library.test.service.batch;

import java.time.LocalDateTime;

import org.coldis.library.service.batch.BatchServiceComponent;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the keys set on the batch resume and delete messages: both the last-value key (the broker
 * keeps one pending message per key) and the stale filter key (consumers drop a message whose key was
 * processed after it was posted), with the same value.
 */
@DisplayName("BatchServiceComponent — message keys")
public class BatchServiceComponentMessageKeyUnitTest {

	/**
	 * Batch service.
	 */
	private BatchServiceComponent batchService;

	/**
	 * JMS template helper mock.
	 */
	private JmsTemplateHelper jmsTemplateHelper;

	/**
	 * Creates the batch service with a mocked JMS template helper.
	 */
	@BeforeEach
	public void setUp() {
		this.batchService = new BatchServiceComponent();
		this.jmsTemplateHelper = Mockito.mock(JmsTemplateHelper.class);
		ReflectionTestUtils.setField(this.batchService, "jmsTemplate", Mockito.mock(JmsTemplate.class));
		ReflectionTestUtils.setField(this.batchService, "jmsTemplateHelper", this.jmsTemplateHelper);
	}

	/**
	 * Gets the single message sent.
	 *
	 * @return The message sent.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private JmsMessage<?> getSentMessage() {
		final ArgumentCaptor<JmsMessage> captor = ArgumentCaptor.forClass(JmsMessage.class);
		Mockito.verify(this.jmsTemplateHelper).send(Mockito.any(JmsTemplate.class), captor.capture());
		return captor.getValue();
	}

	/**
	 * Tests that a resume message carries both keys.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testResumeMessageHasLastValueAndStaleFilterKeys() throws Exception {
		this.batchService.queueResumeAsync("resume-key", null, false);
		final JmsMessage<?> message = this.getSentMessage();
		Assertions.assertEquals("resume-key", message.getLastValueKey());
		Assertions.assertEquals("resume-key", message.getStaleFilterKey());
	}

	/**
	 * Tests that a scheduled resume message carries both keys.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testScheduledResumeMessageHasLastValueAndStaleFilterKeys() throws Exception {
		this.batchService.queueResumeAsync("scheduled-resume-key", LocalDateTime.now().plusMinutes(1), true);
		final JmsMessage<?> message = this.getSentMessage();
		Assertions.assertEquals("scheduled-resume-key", message.getLastValueKey());
		Assertions.assertEquals("scheduled-resume-key", message.getStaleFilterKey());
	}

	/**
	 * Tests that a delete message carries both keys.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testDeleteMessageHasLastValueAndStaleFilterKeys() throws Exception {
		this.batchService.queueDeleteAsync("delete-key", true);
		final JmsMessage<?> message = this.getSentMessage();
		Assertions.assertEquals("delete-key", message.getLastValueKey());
		Assertions.assertEquals("delete-key", message.getStaleFilterKey());
	}

}
