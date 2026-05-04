package org.coldis.library.service.serialization;

import org.apache.fory.BaseFory;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.coldis.library.serialization.OptimizedSerializationHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optimized serialization auto configuration.
 */
@Configuration
@ConditionalOnClass({ Fory.class })
public class OptmizedSerializationAutoConfiguration {

	/**
	 * JSON type packages.
	 */
	@Value(value = "#{'${org.coldis.configuration.base-package}'.split(',')}")
	private String[] typePackages;

	/**
	 * Creates the generic object mapper.
	 *
	 * @param  builder JSON object mapper builder.
	 * @return         The generic object mapper.
	 */
	@Bean
	@Qualifier(value = "javaOptimizedSerializer")
	public BaseFory javaOptimizedSerializer(
			@Value("${org.coldis.configuration.service.optimized-serializer.java.min-pool-size:3}")
			final Integer minPoolSize,
			@Value("${org.coldis.configuration.service.optimized-serializer.java.max-pool-size:30}")
			final Integer maxPoolSize) {
		final BaseFory serializer = OptimizedSerializationHelper.createSerializer(true, minPoolSize, maxPoolSize, Language.JAVA, this.typePackages);
		return serializer;
	}

}
