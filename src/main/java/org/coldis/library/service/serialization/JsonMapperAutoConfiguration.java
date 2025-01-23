package org.coldis.library.service.serialization;

import org.apache.commons.lang3.ArrayUtils;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.ServiceConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON mapper auto configuration.
 */
@Configuration
@DependsOn(value = { "standardJacksonObjectMapperBuilderCustomizer" })
@ConditionalOnClass({ ObjectMapper.class, Jackson2ObjectMapperBuilder.class })
public class JsonMapperAutoConfiguration {

	/**
	 * JSON type packages.
	 */
	@Value(value = "#{'${org.coldis.configuration.base-package}'.split(',')}")
	private String[] jsonTypePackages;

	/**
	 * Creates the generic object mapper.
	 *
	 * @param  builder JSON object mapper builder.
	 * @return         The generic object mapper.
	 */
	public ObjectMapper genericMapper(
			final Jackson2ObjectMapperBuilder builder) {
		final ObjectMapper objectMapper = builder.build();
		ObjectMapperHelper.configureMapper(objectMapper, ArrayUtils.add(this.jsonTypePackages, ServiceConfiguration.BASE_PACKAGE));
		return objectMapper;
	}

	/**
	 * Creates the JSON object mapper.
	 *
	 * @param  builder JSON object mapper builder.
	 * @return         The JSON object mapper.
	 */
	@Bean
	@Primary
	@Qualifier(value = "jsonMapper")
	public ObjectMapper jsonMapper(
			@Autowired
			final Jackson2ObjectMapperBuilder builder) {
		return this.genericMapper(builder);
	}

	/**
	 * Creates the JSON object mapper.
	 *
	 * @param  builder JSON object mapper builder.
	 * @return         The JSON object mapper.
	 */
	@Bean
	@Qualifier(value = "thinJsonMapper")
	public ObjectMapper thinJsonMapper(
			@Autowired
			final Jackson2ObjectMapperBuilder builder) {
		final ObjectMapper objectMapper = this.genericMapper(builder);
		objectMapper.setDefaultPropertyInclusion(Include.NON_NULL);
		objectMapper.configure(SerializationFeature.INDENT_OUTPUT, false);
		return objectMapper;
	}

}
