package org.coldis.library.service.transaction;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource;
import org.springframework.util.StringValueResolver;

/**
 * Extended transaction auto-configuration.
 */
@Configuration
public class ExtendedTransactionAutoConfiguration {

	/**
	 * Embedded value resolver.
	 *
	 * @param  beanFactory Bean factory.
	 * @return             Embedded value resolver.
	 */
	public ExtendedTransactionAutoConfiguration(final AnnotationTransactionAspect transactionAspectSupport, final StringValueResolver embeddedValueResolver) {
		super();
		if (transactionAspectSupport.getTransactionAttributeSource() instanceof AbstractFallbackTransactionAttributeSource) {
			((AbstractFallbackTransactionAttributeSource) transactionAspectSupport.getTransactionAttributeSource())
					.setEmbeddedValueResolver(embeddedValueResolver);
		}
	}

}
