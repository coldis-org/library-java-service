package org.coldis.library.service.serialization;

import org.apache.fory.BaseFory;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.coldis.library.serialization.OptimizedSerializationHelper;
import org.coldis.library.serialization.OptimizedSerializationHelper.RegistrationScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Optimized serialization auto configuration. Exposes three Fory beans so
 * callers can pick the right registration scope for their use case:
 *
 * <ul>
 *   <li>{@code javaOptimizedSerializer} — {@link RegistrationScope#ALL},
 *       {@link Primary}. Every scanned class registers under its FQN. Default
 *       for in-process use such as deep cloning, and the safest by-type
 *       {@code @Autowired BaseFory} target.</li>
 *   <li>{@code javaModelOptimizedSerializer} — {@link RegistrationScope#MODELS},
 *       intended for inbound JMS paths and consumer JVMs that read messages
 *       directly into Model types. Registers Models under their shared
 *       typeName when available; paired DTOs are skipped.</li>
 *   <li>{@code javaDtoOptimizedSerializer} — {@link RegistrationScope#DTOS},
 *       opt-in for outbound paths where the producer holds DTOs. Registers
 *       DTOs under the same shared typeName a Model-prioritized peer reads
 *       from.</li>
 * </ul>
 */
@Configuration
@ConditionalOnClass({ Fory.class })
public class OptmizedSerializationAutoConfiguration {

	/** Type packages to scan. */
	@Value(value = "#{'${org.coldis.configuration.base-package}'.split(',')}")
	private String[] typePackages;

	/**
	 * All-scoped optimized serializer — every scanned class registers under its
	 * FQN. Suitable for deep cloning and other in-process round-trips where the
	 * shared-typeName mechanism would only add collision risk. Marked
	 * {@link Primary} so by-type {@code @Autowired BaseFory} injections land on
	 * the safest default (works for any class), and consumers that need a
	 * specific scope opt in via the {@code @Qualifier}.
	 */
	@Bean
	@Primary
	@Qualifier(value = "javaOptimizedSerializer")
	public BaseFory javaOptimizedSerializer(
			@Value("${org.coldis.configuration.service.optimized-serializer.java.min-pool-size:3}")
			final Integer minPoolSize,
			@Value("${org.coldis.configuration.service.optimized-serializer.java.max-pool-size:30}")
			final Integer maxPoolSize) {
		return OptimizedSerializationHelper.createAllSerializer(true, minPoolSize, maxPoolSize, Language.JAVA, this.typePackages);
	}

	/**
	 * Model-scoped optimized serializer — Models register under shared typeName,
	 * paired DTOs are skipped.
	 */
	@Bean
	@Qualifier(value = "javaModelOptimizedSerializer")
	public BaseFory javaModelOptimizedSerializer(
			@Value("${org.coldis.configuration.service.optimized-serializer.java.min-pool-size:3}")
			final Integer minPoolSize,
			@Value("${org.coldis.configuration.service.optimized-serializer.java.max-pool-size:30}")
			final Integer maxPoolSize) {
		return OptimizedSerializationHelper.createModelSerializer(true, minPoolSize, maxPoolSize, Language.JAVA, this.typePackages);
	}

	/**
	 * Dto-scoped optimized serializer — paired Models are skipped, DTOs register
	 * under the shared typeName a Model-prioritized peer reads from.
	 */
	@Bean
	@Qualifier(value = "javaDtoOptimizedSerializer")
	public BaseFory javaDtoOptimizedSerializer(
			@Value("${org.coldis.configuration.service.optimized-serializer.java.min-pool-size:3}")
			final Integer minPoolSize,
			@Value("${org.coldis.configuration.service.optimized-serializer.java.max-pool-size:30}")
			final Integer maxPoolSize) {
		return OptimizedSerializationHelper.createDtoSerializer(true, minPoolSize, maxPoolSize, Language.JAVA, this.typePackages);
	}

}
