package org.coldis.library.test.service.jms;

import java.util.List;

import org.coldis.library.service.jms.EnhancedJmsErrorHandler;
import org.coldis.library.service.jms.JmsListenerBulkheadException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link EnhancedJmsErrorHandler}: a bulkhead wait abort is one line without a
 * stack trace (WARN for a timeout, DEBUG otherwise); other errors keep their
 * three ERROR lines.
 */
@DisplayName("Enhanced JMS error handler")
public class EnhancedJmsErrorHandlerTest {

	/**
	 * Handles an error and returns the WARN and ERROR events this thread logged.
	 *
	 * @param  throwable Error.
	 * @return           The events (level, has a stack trace).
	 */
	private static List<String> handle(
			final Throwable throwable) {
		final Logger handlerLogger = (Logger) LoggerFactory.getLogger(EnhancedJmsErrorHandler.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		handlerLogger.addAppender(appender);
		try {
			new EnhancedJmsErrorHandler().handleError(throwable);
		}
		finally {
			handlerLogger.detachAppender(appender);
		}
		final String threadName = Thread.currentThread().getName();
		return appender.list.stream().filter(event -> threadName.equals(event.getThreadName()) && event.getLevel().isGreaterOrEqual(Level.WARN))
				.map(event -> event.getLevel() + (event.getThrowableProxy() == null ? "" : "+trace")).toList();
	}

	/**
	 * Tests the log lines per error.
	 */
	@Test
	@DisplayName("Wait timeout: one WARN without trace; container stop: nothing above DEBUG; other errors: three ERROR lines")
	public void testLogLines() {
		Assertions.assertEquals(List.of("WARN"), EnhancedJmsErrorHandlerTest
				.handle(new JmsListenerBulkheadException(JmsListenerBulkheadException.Reason.WAIT_TIMEOUT, "group", "queue", 1_000_000L)));
		Assertions.assertEquals(List.of(), EnhancedJmsErrorHandlerTest
				.handle(new JmsListenerBulkheadException(JmsListenerBulkheadException.Reason.CONTAINER_STOPPED, "group", "queue", 1_000_000L)));
		Assertions.assertEquals(List.of("ERROR", "ERROR", "ERROR+trace"),
				EnhancedJmsErrorHandlerTest.handle(new IllegalStateException("failure", new IllegalArgumentException("cause"))));
	}

}
