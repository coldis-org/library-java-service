package org.coldis.library.service.controller;

import org.apache.commons.lang3.ArrayUtils;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.ServiceConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON mapper auto configuration.
 */
@Configuration
@ConditionalOnClass({ ObjectMapper.class, Jackson2ObjectMapperBuilder.class })
public class JsonMapperAutoConfiguration {

	/**
	 * JSON type packages.
	 */
	@Value(value = "#{'${org.coldis.configuration.base-package}'.split(',')}")
	private String[] jsonTypePackages;

	/**
	 * Creates the JSON object mapper.
	 *
	 * @param  builder JSON object mapper builder.
	 * @return         The JSON object mapper.
	 */
	@Primary
	@Qualifier(value = "jsonMapper")
	@Bean(name = { "objectMapper", "jsonMapper" })
	public ObjectMapper createJsonMapper(
			final Jackson2ObjectMapperBuilder builder) {
		ObjectMapper objectMapper = builder.build();
		objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		objectMapper.registerModule(ObjectMapperHelper.getDateTimeModule());
		objectMapper = ObjectMapperHelper.addSubtypesFromPackage(objectMapper, ArrayUtils.add(this.jsonTypePackages, ServiceConfiguration.BASE_PACKAGE));
		return objectMapper;
	}

}
