package org.coldis.library.service.transaction;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.util.StringValueResolver;

/**
 * Extended transaction auto-configuration.
 */
@Configuration
@ConditionalOnClass(value = AnnotationTransactionAspect.class)
public class ExtendedTransactionAutoConfiguration {

	/**
	 * Sets the embedded value resolver of the transaction aspect's attribute source, when the source takes one: the
	 * annotation source, or a source that wraps it and passes the resolver on.
	 *
	 * @param transactionAspectSupport Transaction aspect.
	 * @param embeddedValueResolver    Embedded value resolver.
	 */
	public ExtendedTransactionAutoConfiguration(final AnnotationTransactionAspect transactionAspectSupport, final StringValueResolver embeddedValueResolver) {
		super();
		if (transactionAspectSupport.getTransactionAttributeSource() instanceof final EmbeddedValueResolverAware resolverAwareSource) {
			resolverAwareSource.setEmbeddedValueResolver(embeddedValueResolver);
		}
	}

}
