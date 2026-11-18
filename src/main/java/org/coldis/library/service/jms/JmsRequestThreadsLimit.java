package org.coldis.library.service.jms;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.jms.JMSException;
import jakarta.jms.ResourceAllocationException;

/**
 * Limit on the request threads that may hold a pooled JMS connection at once
 * ({@link MeteredJmsPoolConnectionFactory}), sized as a share of the request
 * threads the server runs.
 * <p>
 * A request that sends a message holds its connection until the send, or the
 * local transaction the send joined, is done. While a broker is gone or has
 * moved, each of those waits for the client's call and failover timeouts, often
 * several times over. Without a limit, requests that touch the broker take
 * every request thread, and the service stops answering anything: its health
 * check, and the endpoint update that would give it the broker's new address,
 * included. With it, a request past the share waits up to the maximum wait for
 * a holder to finish and then fails with a {@link ResourceAllocationException},
 * so the other request threads keep serving.
 * <p>
 * Only request threads count: listeners, schedulers and other executors have
 * pools of their own. The limit is sized when the main web server starts, from
 * its request threads. A server on an external executor (virtual threads) has
 * no such count and is not limited. One limit covers every pooled factory, so
 * two brokers failing together still leave the other threads free.
 */
@Component
public class JmsRequestThreadsLimit implements ApplicationListener<WebServerInitializedEvent> {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(JmsRequestThreadsLimit.class);

	/**
	 * Property prefix.
	 */
	public static final String PROPERTY_PREFIX = "org.coldis.library.service.jms.request-threads";

	/**
	 * Whether the limit applies.
	 */
	private final Boolean isEnabled;

	/**
	 * Share of the request threads that may hold a pooled JMS connection at
	 * once.
	 */
	private final Double maxShare;

	/**
	 * Longest a request past the limit waits for a holder to finish.
	 */
	private final Duration maximumWait;

	/**
	 * Permits, once sized; null while the limit does not apply.
	 */
	private volatile Semaphore permits;

	/**
	 * Permits in all, once sized.
	 */
	private volatile Integer maxPermits;

	/**
	 * Constructor.
	 *
	 * @param isEnabled   Whether the limit applies.
	 * @param maxShare    Share of the request threads that may hold a pooled JMS
	 *                        connection at once.
	 * @param maximumWait Longest a request past the limit waits for a holder to
	 *                        finish.
	 */
	public JmsRequestThreadsLimit(
			@Value("${" + JmsRequestThreadsLimit.PROPERTY_PREFIX + ".enabled:true}") final Boolean isEnabled,
			@Value("${" + JmsRequestThreadsLimit.PROPERTY_PREFIX + ".max-share:0.5}") final Double maxShare,
			@Value("${" + JmsRequestThreadsLimit.PROPERTY_PREFIX + ".maximum-wait:2s}") final Duration maximumWait) {
		this.isEnabled = isEnabled;
		this.maxShare = maxShare;
		this.maximumWait = maximumWait;
	}

	/**
	 * Gets how many request threads may hold a pooled JMS connection at once.
	 *
	 * @param  maxThreads Request threads of the server; 0 or less when it has
	 *                        no such count (an external executor).
	 * @param  maxShare   Share of them.
	 * @return            The permits, at least 1, or null when the server has
	 *                    no thread count.
	 */
	public static Integer getMaxPermits(
			final Integer maxThreads,
			final Double maxShare) {
		return ((maxThreads == null) || (maxThreads <= 0)) ? null : Math.max(1, (int) Math.floor(maxThreads * maxShare));
	}

	/**
	 * Sizes the limit from the request threads of the server, when it applies.
	 *
	 * @param maxThreads Request threads of the server; 0 or less when it has no
	 *                       such count.
	 */
	public void size(
			final Integer maxThreads) {
		final Integer newMaxPermits = JmsRequestThreadsLimit.getMaxPermits(maxThreads, this.maxShare);
		if (Boolean.TRUE.equals(this.isEnabled) && (newMaxPermits != null)) {
			this.maxPermits = newMaxPermits;
			this.permits = new Semaphore(newMaxPermits);
			JmsRequestThreadsLimit.LOGGER.info("Request threads may hold at most " + newMaxPermits + " pooled JMS connections at once (" + this.maxShare
					+ " of " + maxThreads + " request threads). Past that, a request waits up to " + this.maximumWait.toMillis() + " ms and then fails.");
		}
	}

	/**
	 * Sizes the limit from the main server's request threads. The management
	 * server, when it runs apart, has a namespace and is left out.
	 *
	 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
	 */
	@Override
	public void onApplicationEvent(
			final WebServerInitializedEvent event) {
		if ((event.getApplicationContext().getServerNamespace() == null) && (event.getWebServer() instanceof final TomcatWebServer tomcatWebServer)
				&& (tomcatWebServer.getTomcat().getConnector().getProtocolHandler() instanceof final AbstractProtocol<?> protocol)) {
			this.size(protocol.getMaxThreads());
		}
	}

	/**
	 * Gets the permits in all.
	 *
	 * @return The permits in all, or null while the limit does not apply.
	 */
	public Integer getMaxPermits() {
		return this.maxPermits;
	}

	/**
	 * Gets the permits request threads hold now.
	 *
	 * @return The permits held, 0 while the limit does not apply.
	 */
	public Integer getHeldPermits() {
		final Semaphore currentPermits = this.permits;
		return (currentPermits == null) ? 0 : (this.maxPermits - currentPermits.availablePermits());
	}

	/**
	 * Takes a permit when the current thread serves a request and the limit
	 * applies.
	 *
	 * @return              The permits to give the permit back to, or null when
	 *                      none was taken.
	 * @throws JMSException A {@link ResourceAllocationException} when no permit
	 *                          freed up within the maximum wait.
	 */
	public Semaphore acquire() throws JMSException {
		final Semaphore currentPermits = this.permits;
		Semaphore acquiredPermits = null;
		if ((currentPermits != null) && (RequestContextHolder.getRequestAttributes() != null)) {
			Boolean isAcquired;
			try {
				isAcquired = currentPermits.tryAcquire(this.maximumWait.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (final InterruptedException exception) {
				Thread.currentThread().interrupt();
				isAcquired = false;
			}
			if (!isAcquired) {
				final String message = "All " + this.maxPermits + " pooled JMS connections request threads may hold are taken, so a broker may be slow or gone: "
						+ "refused after " + this.maximumWait.toMillis() + " ms, so the other request threads keep serving (" + JmsRequestThreadsLimit.PROPERTY_PREFIX
						+ ").";
				JmsRequestThreadsLimit.LOGGER.warn(message);
				throw new ResourceAllocationException(message);
			}
			acquiredPermits = currentPermits;
		}
		return acquiredPermits;
	}

}
