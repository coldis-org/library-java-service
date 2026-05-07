package org.coldis.library.service.serialization;

import java.util.List;

import org.apache.fory.BaseFory;
import org.apache.fory.Fory;
import org.coldis.library.serialization.CompositeObjectCloner;
import org.coldis.library.serialization.ForyCloner;
import org.coldis.library.serialization.JavaSerializationCloner;
import org.coldis.library.serialization.ObjectCloner;
import org.coldis.library.serialization.ObjectMapperCloner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Auto configuration for the default {@link ObjectCloner} composite. Uses
 * Fory first (fastest, in-memory copy), JSON round-trip via Jackson as a
 * fallback for types Fory cannot handle, and standard Java serialization
 * as a last resort.
 */
@Configuration
@ConditionalOnClass({ Fory.class, ObjectMapper.class })
public class ObjectClonerAutoConfiguration {

	/**
	 * Creates the composite object cloner.
	 *
	 * @param  fory       Fory serializer.
	 * @param  jsonMapper JSON mapper.
	 * @return            The composite cloner.
	 */
	@Bean
	@Qualifier(value = "defaultCloner")
	public CompositeObjectCloner defaultCloner(
			@Qualifier("javaCloneOptimizedSerializer")
			final BaseFory fory,
			@Qualifier("jsonMapper")
			final ObjectMapper jsonMapper) {
		return new CompositeObjectCloner(List.of(new ForyCloner(fory), new ObjectMapperCloner(jsonMapper), new JavaSerializationCloner()));
	}

}
