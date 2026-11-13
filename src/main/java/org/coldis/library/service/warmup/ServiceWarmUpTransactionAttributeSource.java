package org.coldis.library.service.warmup;

import java.lang.reflect.Method;

import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.transaction.interceptor.DelegatingTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.util.StringValueResolver;

/**
 * Transaction attribute source of the AspectJ transaction aspect that makes, on warm-up threads, the transactions of
 * the service's {@code @Transactional} methods read-only, whatever their propagation, so that they still run and the
 * database refuses their writes. Transactions started elsewhere (repositories, transaction templates) are refused when
 * read-write (see {@link ServiceWarmUpTransactionExecutionListener}). Other threads get the wrapped source's attributes
 * unchanged.
 */
public class ServiceWarmUpTransactionAttributeSource implements TransactionAttributeSource, EmbeddedValueResolverAware {

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
	 * Passes the resolver on to the wrapped source, when it takes one, so that wrapping does not change how its
	 * placeholders resolve.
	 *
	 * @see org.springframework.context.EmbeddedValueResolverAware#setEmbeddedValueResolver(org.springframework.util.StringValueResolver)
	 */
	@Override
	public void setEmbeddedValueResolver(
			final StringValueResolver resolver) {
		if (this.transactionAttributeSource instanceof final EmbeddedValueResolverAware resolverAwareSource) {
			resolverAwareSource.setEmbeddedValueResolver(resolver);
		}
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
