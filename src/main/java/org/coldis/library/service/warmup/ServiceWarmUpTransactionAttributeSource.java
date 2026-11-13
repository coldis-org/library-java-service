package org.coldis.library.service.warmup;

import java.lang.reflect.Method;

import org.springframework.transaction.interceptor.DelegatingTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Transaction attribute source that makes every transaction read-only on warm-up threads, whatever its propagation, so
 * that the database refuses the writes of a warm-up call, even in a new transaction. Other threads get the wrapped
 * source's attributes unchanged.
 */
public class ServiceWarmUpTransactionAttributeSource implements TransactionAttributeSource {

	/**
	 * Transaction attribute source.
	 */
	private final TransactionAttributeSource transactionAttributeSource;

	/**
	 * Read-only view of a transaction attribute.
	 */
	private static final class ReadOnlyTransactionAttribute extends DelegatingTransactionAttribute {

		/**
		 * Serial.
		 */
		private static final long serialVersionUID = -6730428415362917201L;

		/**
		 * Constructor.
		 *
		 * @param transactionAttribute Transaction attribute.
		 */
		ReadOnlyTransactionAttribute(final TransactionAttribute transactionAttribute) {
			super(transactionAttribute);
		}

		/**
		 * @see org.springframework.transaction.support.DelegatingTransactionDefinition#isReadOnly()
		 */
		@Override
		public boolean isReadOnly() {
			return true;
		}

	}

	/**
	 * Constructor.
	 *
	 * @param transactionAttributeSource Transaction attribute source.
	 */
	public ServiceWarmUpTransactionAttributeSource(final TransactionAttributeSource transactionAttributeSource) {
		super();
		this.transactionAttributeSource = transactionAttributeSource;
	}

	/**
	 * Gets the wrapped transaction attribute source.
	 *
	 * @return The wrapped transaction attribute source.
	 */
	public TransactionAttributeSource getTransactionAttributeSource() {
		return this.transactionAttributeSource;
	}

	/**
	 * @see org.springframework.transaction.interceptor.TransactionAttributeSource#isCandidateClass(java.lang.Class)
	 */
	@Override
	public boolean isCandidateClass(
			final Class<?> targetClass) {
		return this.transactionAttributeSource.isCandidateClass(targetClass);
	}

	/**
	 * @see org.springframework.transaction.interceptor.TransactionAttributeSource#hasTransactionAttribute(java.lang.reflect.Method,
	 *      java.lang.Class)
	 */
	@Override
	public boolean hasTransactionAttribute(
			final Method method,
			final Class<?> targetClass) {
		return this.transactionAttributeSource.hasTransactionAttribute(method, targetClass);
	}

	/**
	 * @see org.springframework.transaction.interceptor.TransactionAttributeSource#getTransactionAttribute(java.lang.reflect.Method,
	 *      java.lang.Class)
	 */
	@Override
	public TransactionAttribute getTransactionAttribute(
			final Method method,
			final Class<?> targetClass) {
		final TransactionAttribute transactionAttribute = this.transactionAttributeSource.getTransactionAttribute(method, targetClass);
		return ((transactionAttribute != null) && !transactionAttribute.isReadOnly() && ServiceWarmUpContext.isWarmingUp()
				? new ReadOnlyTransactionAttribute(transactionAttribute)
				: transactionAttribute);
	}

}
