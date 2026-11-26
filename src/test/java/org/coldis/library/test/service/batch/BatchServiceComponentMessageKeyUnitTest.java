package org.coldis.library.test.service.batch;

import java.util.ArrayList;
import java.util.List;

import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.service.batch.BatchServiceComponent;
import org.coldis.library.service.jms.JmsMessage;
import org.coldis.library.service.jms.JmsTemplateHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
	 * Messages sent through the JMS template helper.
	 */
	private List<JmsMessage<?>> sentMessages;

	/**
	 * Creates the batch service with a JMS template helper that records the messages it is given
	 * instead of sending them.
	 */
	@BeforeEach
	public void setUp() {
		this.batchService = new BatchServiceComponent();
		this.sentMessages = new ArrayList<>();
		final JmsTemplateHelper recordingJmsTemplateHelper = (jmsTemplate, message) -> this.sentMessages.add(message);
		ReflectionTestUtils.setField(this.batchService, "jmsTemplate", new JmsTemplate());
		ReflectionTestUtils.setField(this.batchService, "jmsTemplateHelper", recordingJmsTemplateHelper);
	}

	/**
	 * Gets the single message sent.
	 *
	 * @return The message sent.
	 */
	private JmsMessage<?> getSentMessage() {
		Assertions.assertEquals(1, this.sentMessages.size());
		return this.sentMessages.get(0);
	}

	/**
	 * Tests that a resume message carries both keys.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A resume message carries the batch key suffix as last-value key and stale filter key")
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
	@DisplayName("A scheduled resume message carries the batch key suffix as last-value key and stale filter key")
	public void testScheduledResumeMessageHasLastValueAndStaleFilterKeys() throws Exception {
		this.batchService.queueResumeAsync("scheduled-resume-key", DateTimeHelper.getCurrentLocalDateTime().plusMinutes(1), true);
		final JmsMessage<?> message = this.getSentMessage();
		Assertions.assertEquals("scheduled-resume-key", message.getLastValueKey());
		Assertions.assertEquals("scheduled-resume-key", message.getStaleFilterKey());
	}

	/**
	 * Tests that a conditional delete message carries the batch key as both keys.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A conditional delete message carries the batch key as last-value key and stale filter key")
	public void testDeleteMessageHasLastValueAndStaleFilterKeys() throws Exception {
		this.batchService.queueDeleteAsync("delete-key", true);
		final JmsMessage<?> message = this.getSentMessage();
		Assertions.assertEquals("delete-key", message.getLastValueKey());
		Assertions.assertEquals("delete-key", message.getStaleFilterKey());
	}

	/**
	 * Tests that a forced delete message has its own keys, so a conditional delete
	 * (which may keep the batch) neither replaces it at the broker nor makes it
	 * look stale.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	@DisplayName("A forced delete message has its own last-value key and stale filter key")
	public void testForcedDeleteMessageHasOwnKeys() throws Exception {
		this.batchService.queueDeleteAsync("forced-delete-key", false);
		final JmsMessage<?> message = this.getSentMessage();
		Assertions.assertEquals("forced-delete-key" + BatchServiceComponent.FORCED_DELETE_KEY_SUFFIX, message.getLastValueKey());
		Assertions.assertEquals("forced-delete-key" + BatchServiceComponent.FORCED_DELETE_KEY_SUFFIX, message.getStaleFilterKey());
	}

}
