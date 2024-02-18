package org.coldis.library.service.template;

import java.io.StringWriter;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * Templating service.
 */
@Service
public class TemplatingService {

	/**
	 * Default charset.
	 */
	public static final String DEFAULT_CHARSET = "UTF-8";

	/**
	 * String velocity repository.
	 */
	private final StringResourceRepository stringVelocityRepository = StringResourceLoader.getRepository();

	/** String velocity engine. */
	private VelocityEngine stringVelocityEngine;

	/**
	 * String velocity repository.
	 */
	@Bean
	@Qualifier(value = "stringVelocityRepository")
	public StringResourceRepository stringVelocityRepository() {
		return this.stringVelocityRepository;
	}

	/**
	 * String velocity engine.
	 *
	 * @return String velocity engine.
	 */
	@Bean
	@Qualifier(value = "stringVelocityEngine")
	public VelocityEngine stringVelocityEngine(
			final StringResourceRepository stringVelocityRepository) {
		this.stringVelocityEngine = new VelocityEngine();
		this.stringVelocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
		this.stringVelocityEngine.setProperty("resource.loader.string.class", StringResourceLoader.class.getName());
		this.stringVelocityEngine.setProperty("resource.loader.string.cache", true);
		this.stringVelocityEngine.setProperty("resource.loader.string.modification_check_interval", 60);
		this.stringVelocityEngine.init();
		return this.stringVelocityEngine;
	}

	/**
	 * Add string template to the repository.
	 *
	 * @param key      Template key.
	 * @param template Template content.
	 */
	public void addStringTemplate(
			final String key,
			final String template,
			final String charset) {
		this.stringVelocityRepository.putStringResource(key, template, charset);
	}

	/**
	 * Add string template to the repository.
	 *
	 * @param key      Template key.
	 * @param template Template content.
	 */
	public void addStringTemplate(
			final String key,
			final String template) {
		this.addStringTemplate(key, template, TemplatingService.DEFAULT_CHARSET);
	}

	/**
	 * Apply string template.
	 *
	 * @param  key     Template key.
	 * @param  context Template context.
	 * @param  charset Template charset.
	 * @return         Template result.
	 */
	public String applyStringTemplate(
			final String key,
			final VelocityContext context,
			final String charset) {
		try (StringWriter templateWriter = new StringWriter()) {
			this.stringVelocityEngine.mergeTemplate(key, charset, context, templateWriter);
			return templateWriter.toString();
		}
		// Throws an invalid template exception.
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("template.invalid"), exception);
		}
	}

	/**
	 * Apply string template.
	 *
	 * @param  key     Template key.
	 * @param  context Template context.
	 * @return         Template result.
	 */
	public String applyStringTemplate(
			final String key,
			final VelocityContext context) {
		return this.applyStringTemplate(key, context, TemplatingService.DEFAULT_CHARSET);
	}

}
