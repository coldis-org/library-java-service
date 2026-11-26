package org.coldis.library.service.throttle;

import javax.sql.DataSource;

import org.coldis.library.service.throttle.repository.SynchronizedThrottleRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Synchronized throttle repository auto-configuration: creates the repository
 * only when the throttle and its persistence are enabled (the defaults) and a
 * data source exists. Without the repository, the throttle is local only. It
 * is an auto-configuration (listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports})
 * so the data source condition is evaluated after the data source
 * auto-configuration.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(
		name = { SynchronizedThrottleProperties.ENABLED_PROPERTY, SynchronizedThrottleProperties.PERSISTENCE_ENABLED_PROPERTY },
		havingValue = "true",
		matchIfMissing = true
)
public class SynchronizedThrottleRepositoryAutoConfiguration {

	/**
	 * Creates the synchronized throttle repository.
	 *
	 * @param  dataSource Data source.
	 * @param  properties Synchronized throttle properties (table name and query
	 *                        timeout).
	 * @return            The repository.
	 */
	@Bean
	@ConditionalOnMissingBean
	public SynchronizedThrottleRepository synchronizedThrottleRepository(
			final DataSource dataSource,
			final SynchronizedThrottleProperties properties) {
		return new SynchronizedThrottleRepository(dataSource, properties.getTableName(), properties.getQueryTimeout());
	}

}
