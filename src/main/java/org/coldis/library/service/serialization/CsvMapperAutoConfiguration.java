package org.coldis.library.service.serialization;

import org.apache.commons.lang3.ArrayUtils;
import org.coldis.library.serialization.csv.CsvHelper;
import org.coldis.library.service.ServiceConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.dataformat.csv.CsvMapper;

/**
 * CSV mapper auto configuration.
 */
@Configuration
@ConditionalOnClass(value = CsvMapper.class)
public class CsvMapperAutoConfiguration implements WebMvcConfigurer {

	/**
	 * JSON type packages.
	 */
	@Value(value = "#{'${org.coldis.configuration.base-package}'.split(',')}")
	private String[] jsonTypePackages;

	/**
	 * Creates a CSV mapper.
	 *
	 * @return CSV mapper.
	 */
	@Bean(name = { "csvMapper" })
	@Qualifier(value = "csvMapper")
	public CsvMapper createCsvMapper() {
		return CsvHelper.getObjectMapper(ArrayUtils.add(this.jsonTypePackages, ServiceConfiguration.BASE_PACKAGE));
	}

}
