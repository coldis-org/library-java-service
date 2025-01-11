package org.coldis.library.service.serialization;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Message converter auto configuration.
 */
@Configuration
public class MessageConverterAutoConfiguration implements WebMvcConfigurer {

	/**
	 * JSON mapper.
	 */
	@Autowired(required = false)
	private ObjectMapper jsonMapper;

	/**
	 * CSV mapper.
	 */
	@Autowired(required = false)
	@Qualifier(value = "csvMapper")
	private ObjectMapper csvMapper;

	/**
	 * @see org.springframework.web.servlet.config.annotation.WebMvcConfigurer#configureMessageConverters(java.util.List)
	 */
	@Override
	public void configureMessageConverters(
			final List<HttpMessageConverter<?>> converters) {
		WebMvcConfigurer.super.configureMessageConverters(converters);
		// If there is a JSON mapper.
		if (this.jsonMapper != null) {
			// Adds it to the converters list.
			final MappingJackson2HttpMessageConverter messageConverter = new MappingJackson2HttpMessageConverter(this.jsonMapper);
			messageConverter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, new MediaType("application", "*+json")));
			converters.add(messageConverter);
		}
		// If there is a CSV mapper.
		if (this.csvMapper != null) {
			// Adds it to the converters list.
			final MappingJackson2HttpMessageConverter messageConverter = new MappingJackson2HttpMessageConverter(this.csvMapper);
			messageConverter.setSupportedMediaTypes(List.of(new MediaType("text", "csv"), new MediaType("application", "csv")));
			converters.add(messageConverter);
		}
	}

}
