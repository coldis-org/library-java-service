package org.coldis.library.service.controller;

import org.apache.fury.Fury;
import org.apache.fury.config.Language;
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
@ConditionalOnClass({ Fury.class })
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
	public Fury javaOptimizedSerializer() {
		final Fury serializer = OptimizedSerializationHelper.createSerializer(Language.JAVA, this.typePackages);
		return serializer;
	}

}
