package org.coldis.library.service.health;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.model.Typable;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValue;
import org.coldis.library.persistence.keyvalue.KeyValueRepository;
import org.coldis.library.persistence.keyvalue.KeyValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository health check service.
 */
@Service
@ConditionalOnClass(value = KeyValueRepository.class)
public class RepositoryHealthCheckService {

	/**
	 * Health check repository.
	 */
	@Autowired(required = false)
	private KeyValueService keyValueService;

	/**
	 * Touches the health check repository.
	 *
	 * @return                   The health check value.
	 * @throws BusinessException If the health check does not work.
	 */
	@Transactional(
			propagation = Propagation.REQUIRED,
			timeoutString = "${org.coldis.library.service.transaction.longer-timeout}"
	)
	public HealthCheckValue touch() throws BusinessException {
		KeyValue<Typable> healthCheckKeyValue = null;
		try {
			healthCheckKeyValue = this.keyValueService.findById(HealthCheckService.HEALTH_CHECK_KEY, LockBehavior.NO_LOCK, false);
		}
		catch (final BusinessException exception) {
			healthCheckKeyValue = this.keyValueService.lock(HealthCheckService.HEALTH_CHECK_KEY, LockBehavior.WAIT_AND_LOCK);
			healthCheckKeyValue.setValue(new HealthCheckValue());
			this.keyValueService.update(HealthCheckService.HEALTH_CHECK_KEY, healthCheckKeyValue.getValue());
		}
		return (HealthCheckValue) healthCheckKeyValue.getValue();
	}

}
