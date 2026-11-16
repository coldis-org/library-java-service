package org.coldis.library.service.jms;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.messaginghub.pooled.jms.pool.PooledConnection;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryFactory;
import org.springframework.boot.autoconfigure.jms.JmsPoolConnectionFactoryProperties;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;

/**
 * Pooled JMS connection factory that exposes pool usage as Micrometer gauges.
 *
 * Why the numbers are read the way they are:
 *
 * - pooled-jms builds its commons-pool2 pools with setJmxEnabled(false), so
 * CommonsObjectPool2Metrics cannot bind to them.
 *
 * - JmsPoolConnectionFactory.getNumConnections() returns only connectionsPool.getNumIdle(), so it
 * drops as load rises. The connection pool is read through the protected getConnectionsPool()
 * instead, which exposes idle, active and waiter counts separately.
 *
 * - maxConnections maps to setMaxTotalPerKey, and the key is PooledConnectionKey(userName,
 * password). The ceiling is therefore per credential, not global, which is why the total is not
 * reported as a single number.
 *
 * - The blocking ceiling is per connection: blockIfSessionPoolIsFull and maxSessionsPerConnection
 * are applied to each PooledConnection's own session pool. A thread blocks when one connection
 * exhausts its sessions, so the reported numerator is the busiest connection rather than the sum.
 *
 * - The session pool's own waiter count would be the most precise saturation signal, but
 * PooledConnection.sessionPool is private with no accessor. Reaching it would mean reflecting into a
 * private field of the library, which fails silently on upgrade — the busiest-connection ratio
 * against maxSessionsPerConnection answers the same question without that coupling.
 *
 * Sessions are what saturates: block-if-full with block-if-full-timeout=-1 means a thread that finds
 * the session pool exhausted blocks indefinitely, with no error and no health check impact.
 */
public class MeteredJmsPoolConnectionFactory extends JmsPoolConnectionFactory implements BeanNameAware, InitializingBean {

	/** Connections currently loaned out. */
	public static final String CONNECTIONS_ACTIVE = "jms.pool.connections.active";

	/** Threads blocked waiting for a connection. */
	public static final String CONNECTIONS_WAITERS = "jms.pool.connections.waiters";

	/** Connection ceiling, per credential. */
	public static final String CONNECTIONS_MAX_PER_KEY = "jms.pool.connections.max.per.key";

	/** Sessions loaned out by the busiest single connection. */
	public static final String SESSIONS_ACTIVE_MAX = "jms.pool.sessions.active.max";

	/** Session ceiling of a single connection, which is where blocking happens. */
	public static final String SESSIONS_MAX_PER_CONNECTION = "jms.pool.sessions.max.per.connection";

	/** Tag identifying which connection factory the gauge belongs to. */
	public static final String FACTORY_TAG = "factory";

	/**
	 * Connections created by this factory. A service declares one factory per messaging domain it
	 * talks to, so gauges are per factory and never aggregated across pools.
	 */
	private final Set<PooledConnection> trackedConnections = ConcurrentHashMap.newKeySet();

	/** Meter registry, or null when metrics are not available. */
	private final MeterRegistry meterRegistry;

	/** Bean name, used as the gauge tag. */
	private String beanName;

	/**
	 * Default constructor.
	 *
	 * @param meterRegistry Meter registry, or null to skip metrics entirely.
	 */
	public MeteredJmsPoolConnectionFactory(
			final MeterRegistry meterRegistry) {
		super();
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Creates the pooled connection factory, applying the same pool settings as
	 * {@link JmsPoolConnectionFactoryFactory}. The settings are applied here instead of delegating
	 * because that factory instantiates {@link JmsPoolConnectionFactory} directly, leaving no way to
	 * substitute this subclass.
	 *
	 * Keep in sync with
	 * {@link JmsPoolConnectionFactoryFactory#createPooledConnectionFactory(jakarta.jms.ConnectionFactory)}.
	 * MeteredJmsPoolConnectionFactoryUnitTest compares both results attribute by attribute, so a
	 * setting added by a Spring Boot upgrade fails the test instead of being silently dropped.
	 *
	 * The caller must resolve the pool properties through {@link JmsConfigurationHelper} first, so the
	 * ceilings are the merged ones rather than the Spring Boot defaults.
	 *
	 * @param  meterRegistry     Meter registry, or null to skip metrics.
	 * @param  poolProperties    Pool properties.
	 * @param  connectionFactory Connection factory to pool.
	 * @return                   The pooled connection factory.
	 */
	public static MeteredJmsPoolConnectionFactory create(
			final MeterRegistry meterRegistry,
			final JmsPoolConnectionFactoryProperties poolProperties,
			final ConnectionFactory connectionFactory) {
		final MeteredJmsPoolConnectionFactory pooledConnectionFactory = new MeteredJmsPoolConnectionFactory(meterRegistry);
		pooledConnectionFactory.setConnectionFactory(connectionFactory);
		pooledConnectionFactory.setBlockIfSessionPoolIsFull(poolProperties.isBlockIfFull());
		if (poolProperties.getBlockIfFullTimeout() != null) {
			pooledConnectionFactory.setBlockIfSessionPoolIsFullTimeout(poolProperties.getBlockIfFullTimeout().toMillis());
		}
		if (poolProperties.getIdleTimeout() != null) {
			pooledConnectionFactory.setConnectionIdleTimeout((int) poolProperties.getIdleTimeout().toMillis());
		}
		pooledConnectionFactory.setMaxConnections(poolProperties.getMaxConnections());
		pooledConnectionFactory.setMaxSessionsPerConnection(poolProperties.getMaxSessionsPerConnection());
		if (poolProperties.getTimeBetweenExpirationCheck() != null) {
			pooledConnectionFactory.setConnectionCheckInterval(poolProperties.getTimeBetweenExpirationCheck().toMillis());
		}
		pooledConnectionFactory.setUseAnonymousProducers(poolProperties.isUseAnonymousProducers());
		return pooledConnectionFactory;
	}

	/**
	 * @see org.springframework.beans.factory.BeanNameAware#setBeanName(java.lang.String)
	 */
	@Override
	public void setBeanName(
			final String beanName) {
		this.beanName = beanName;
	}

	/**
	 * Registers the gauges. Runs after {@link #setBeanName(String)}, so the tag is already resolved.
	 * getConnectionsPool() initialises the pool on first call, so it is safe here.
	 *
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() {
		if (this.meterRegistry != null) {
			final String factory = this.beanName == null ? "unknown" : this.beanName;
			this.register(MeteredJmsPoolConnectionFactory.CONNECTIONS_ACTIVE, factory, meteredFactory -> meteredFactory.getConnectionsPool().getNumActive());
			this.register(MeteredJmsPoolConnectionFactory.CONNECTIONS_WAITERS, factory, meteredFactory -> meteredFactory.getConnectionsPool().getNumWaiters());
			this.register(MeteredJmsPoolConnectionFactory.CONNECTIONS_MAX_PER_KEY, factory,
					meteredFactory -> meteredFactory.getConnectionsPool().getMaxTotalPerKey());
			this.register(MeteredJmsPoolConnectionFactory.SESSIONS_ACTIVE_MAX, factory, MeteredJmsPoolConnectionFactory::getMaxActiveSessions);
			this.register(MeteredJmsPoolConnectionFactory.SESSIONS_MAX_PER_CONNECTION, factory, JmsPoolConnectionFactory::getMaxSessionsPerConnection);
		}
	}

	/**
	 * Registers a single gauge tagged with the factory name.
	 *
	 * @param name    Metric name.
	 * @param factory Factory tag value.
	 * @param value   Value supplier.
	 */
	private void register(
			final String name,
			final String factory,
			final ToDoubleFunction<MeteredJmsPoolConnectionFactory> value) {
		Gauge.builder(name, this, value).tag(MeteredJmsPoolConnectionFactory.FACTORY_TAG, factory).register(this.meterRegistry);
	}

	/**
	 * @see org.messaginghub.pooled.jms.JmsPoolConnectionFactory#createPooledConnection(jakarta.jms.Connection)
	 */
	@Override
	protected PooledConnection createPooledConnection(
			final Connection connection) {
		final PooledConnection pooledConnection = super.createPooledConnection(connection);
		this.trackedConnections.add(pooledConnection);
		return pooledConnection;
	}

	/**
	 * Sessions loaned out by the busiest single connection, which is the one closest to blocking.
	 * Closed connections are dropped on read, which is also what keeps the tracking set from growing
	 * without bound.
	 *
	 * @return Loaned sessions on the busiest connection, or 0 when no connection exists.
	 */
	public int getMaxActiveSessions() {
		this.trackedConnections.removeIf(PooledConnection::isClosed);
		return this.trackedConnections.stream().mapToInt(PooledConnection::getNumActiveSessions).max().orElse(0);
	}

}
