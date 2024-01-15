package org.coldis.library.service.controller;

import org.springframework.http.MediaType;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CSV message converter.
 */
public class JsonMessageConverter extends AbstractJackson2HttpMessageConverter {

	/**
	 * Default constructor.
	 *
	 * @param objectMapper Object mapper.
	 */
	public JsonMessageConverter(final ObjectMapper objectMapper) {
		super(objectMapper, MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
	}

}
