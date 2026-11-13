package org.coldis.library.service.transaction;

import org.coldis.library.service.warmup.ServiceWarmUpTransactionAttributeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.util.StringValueResolver;

/**
 * Extended transaction auto-configuration.
 */
@Configuration
@ConditionalOnClass(value = AnnotationTransactionAspect.class)
public class ExtendedTransactionAutoConfiguration {

	/**
	 * Sets the embedded value resolver of the transaction aspect's attribute source.
	 *
	 * @param transactionAspectSupport Transaction aspect.
	 * @param embeddedValueResolver    Embedded value resolver.
	 */
	public ExtendedTransactionAutoConfiguration(final AnnotationTransactionAspect transactionAspectSupport, final StringValueResolver embeddedValueResolver) {
		super();
		// A service with the warm-up on wraps the source (see ServiceWarmUpAutoConfiguration), before or after this runs.
		final TransactionAttributeSource currentSource = transactionAspectSupport.getTransactionAttributeSource();
		final TransactionAttributeSource annotationSource = (currentSource instanceof final ServiceWarmUpTransactionAttributeSource warmUpSource
				? warmUpSource.getTransactionAttributeSource()
				: currentSource);
		if (annotationSource instanceof final AbstractFallbackTransactionAttributeSource fallbackSource) {
			fallbackSource.setEmbeddedValueResolver(embeddedValueResolver);
		}
	}

}
