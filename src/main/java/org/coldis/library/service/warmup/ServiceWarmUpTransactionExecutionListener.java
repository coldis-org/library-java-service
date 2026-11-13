package org.coldis.library.service.warmup;

import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Refuses, on warm-up threads, every new transaction that is not read-only, whoever starts it: a repository, a
 * {@code TransactionTemplate} or another transaction manager. Transactions that join an existing one never begin, so
 * the read-only transaction around each call and the service's {@code @Transactional} methods (read-only on warm-up
 * threads, see {@link ServiceWarmUpTransactionAttributeSource}) pass. Writes made outside any transaction (auto-commit)
 * are not covered.
 */
public class ServiceWarmUpTransactionExecutionListener implements TransactionExecutionListener {

	/**
	 * Name of the side effect refused.
	 */
	static final String READ_WRITE_TRANSACTION = "read-write transaction";

	/**
	 * @see org.springframework.transaction.TransactionExecutionListener#beforeBegin(org.springframework.transaction.TransactionExecution)
	 */
	@Override
	public void beforeBegin(
			final TransactionExecution transaction) {
		if (!transaction.isReadOnly()) {
			ServiceWarmUpContext.rejectDuringWarmUp(ServiceWarmUpTransactionExecutionListener.READ_WRITE_TRANSACTION);
		}
	}

}
