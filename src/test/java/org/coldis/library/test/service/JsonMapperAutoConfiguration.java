package org.coldis.library.test.service;

import org.coldis.library.serialization.ObjectMapperHelper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON mapper auto configuration.
 */
@Configuration
@ConditionalOnClass({ ObjectMapper.class, Jackson2ObjectMapperBuilder.class })
public class JsonMapperAutoConfiguration {

	/**
	 * Creates the JSON object mapper.
	 *
	 * @param  builder JSON object mapper builder.
	 * @return         The JSON object mapper.
	 */
	@Primary
	@Bean(name = { "objectMapper", "jsonMapper" })
	public ObjectMapper jsonObjectMapper(
			final Jackson2ObjectMapperBuilder builder) {
		// Creates the object mapper.
		ObjectMapper objectMapper = builder.createXmlMapper(false).build();
		// Registers the date/time module.
		objectMapper.registerModule(ObjectMapperHelper.getDateTimeModule());
		// Registers the subtypes from the base packages.
		objectMapper = ObjectMapperHelper.addSubtypesFromPackage(objectMapper, "org.coldis");
		// Returns the configured object mapper.
		return objectMapper;
	}

}
