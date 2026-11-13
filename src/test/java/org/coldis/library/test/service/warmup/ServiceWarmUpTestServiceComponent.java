package org.coldis.library.test.service.warmup;

import java.util.concurrent.atomic.AtomicInteger;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.statistics.StatisticsEvent;
import org.coldis.library.service.statistics.StatisticsEventServiceComponent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

/**
 * Operations with side effects that warm-up calls must not have, for the warm-up tests.
 */
@Component
public class ServiceWarmUpTestServiceComponent {

	/**
	 * Queue the tests send to.
	 */
	public static final String QUEUE = "service-warm-up-test";

	/**
	 * Local cache.
	 */
	public static final String LOCAL_CACHE = "serviceWarmUpTestLocalCache";

	/**
	 * Central cache.
	 */
	public static final String CENTRAL_CACHE = "serviceWarmUpTestCentralCache";

	/**
	 * Number of runs of the cached methods.
	 */
	public static final AtomicInteger CACHED_METHOD_RUNS = new AtomicInteger();

	/**
	 * Key-value service.
	 */
	@Autowired
	private KeyValueServiceComponent keyValueServiceComponent;

	/**
	 * JMS template.
	 */
	@Autowired
	private JmsTemplate jmsTemplate;

	/**
	 * REST template.
	 */
	@Autowired
	private RestTemplate restTemplate;

	/**
	 * Statistics event service.
	 */
	@Autowired
	private StatisticsEventServiceComponent statisticsEventServiceComponent;

	/**
	 * Locks a key, creating it when it does not exist, in a new transaction.
	 *
	 * @param  key               Key.
	 * @throws BusinessException If the key cannot be locked.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void lockInNewTransaction(
			final String key) throws BusinessException {
		this.keyValueServiceComponent.lock(key, LockBehavior.WAIT_AND_LOCK);
	}

	/**
	 * Counts a run, cached locally.
	 *
	 * @param  key Cache key.
	 * @return     The number of runs of the cached methods.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationLocalCacheManager",
			value = ServiceWarmUpTestServiceComponent.LOCAL_CACHE
	)
	public Integer getFromLocalCache(
			final String key) {
		return ServiceWarmUpTestServiceComponent.CACHED_METHOD_RUNS.incrementAndGet();
	}

	/**
	 * Counts a run, cached centrally.
	 *
	 * @param  key Cache key.
	 * @return     The number of runs of the cached methods.
	 */
	@Cacheable(
			cacheManager = "minutesExpirationCentralCacheManager",
			value = ServiceWarmUpTestServiceComponent.CENTRAL_CACHE
	)
	public Integer getFromCentralCache(
			final String key) {
		return ServiceWarmUpTestServiceComponent.CACHED_METHOD_RUNS.incrementAndGet();
	}

	/**
	 * Sends a message to the test queue.
	 *
	 * @param message Message.
	 */
	public void send(
			final String message) {
		this.jmsTemplate.convertAndSend(ServiceWarmUpTestServiceComponent.QUEUE, message);
	}

	/**
	 * Gets the health check of this service over HTTP.
	 *
	 * @param  port Server port.
	 * @return      The health check body.
	 */
	public String getHealthCheck(
			final Integer port) {
		return this.restTemplate.getForObject("http://localhost:" + port + "/health-check", String.class);
	}

	/**
	 * Records a statistics event.
	 *
	 * @throws BusinessException If the event is invalid.
	 */
	public void recordStatisticsEvent() throws BusinessException {
		this.statisticsEventServiceComponent.upsertStatisticsEvent(new StatisticsEvent());
	}

}
