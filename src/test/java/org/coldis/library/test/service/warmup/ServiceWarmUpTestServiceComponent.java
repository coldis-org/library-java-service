package org.coldis.library.test.service.warmup;

import org.coldis.library.exception.BusinessException;
import org.coldis.library.persistence.LockBehavior;
import org.coldis.library.persistence.keyvalue.KeyValue;
import org.coldis.library.persistence.keyvalue.KeyValueServiceComponent;
import org.coldis.library.service.health.HealthCheckValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database writes that warm-up calls must not make, for the warm-up tests.
 */
@Component
public class ServiceWarmUpTestServiceComponent {

	/**
	 * Key-value service.
	 */
	@Autowired
	private KeyValueServiceComponent keyValueServiceComponent;

	/**
	 * Transaction manager.
	 */
	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * Saves a key through the repository, in a new transaction that a transaction template starts.
	 *
	 * @param key Key.
	 */
	public void saveInProgrammaticNewTransaction(
			final String key) {
		final TransactionTemplate transactionTemplate = new TransactionTemplate(this.transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transactionTemplate.executeWithoutResult(transactionStatus -> this.keyValueServiceComponent.getRepository().save(new KeyValue<>(key, new HealthCheckValue())));
	}

	/**
	 * Saves a key through the repository with any surrounding transaction suspended, so that the transaction template
	 * starts a new transaction even with the default propagation.
	 *
	 * @param key Key.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void saveWithTransactionSuspended(
			final String key) {
		final TransactionTemplate transactionTemplate = new TransactionTemplate(this.transactionManager);
		transactionTemplate.executeWithoutResult(transactionStatus -> this.keyValueServiceComponent.getRepository().save(new KeyValue<>(key, new HealthCheckValue())));
	}

	/**
	 * Loads a key and removes it through the repository in the caller's transaction, leaving the removal for the commit
	 * to write, as a change to a loaded entity is.
	 *
	 * @param key Key.
	 */
	@Transactional
	public void removeLoaded(
			final String key) {
		this.keyValueServiceComponent.getRepository().findById(key).ifPresent(keyValue -> this.keyValueServiceComponent.getRepository().delete(keyValue));
	}

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

}
