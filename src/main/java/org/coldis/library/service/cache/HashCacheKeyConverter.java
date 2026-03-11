package org.coldis.library.service.cache;

import java.security.MessageDigest;
import java.util.HexFormat;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.core.convert.converter.Converter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Hash cache key converter.
 */
class HashCacheKeyConverter implements Converter<Object, String> {

	/**
	 * Object mapper.
	 */
	private final ObjectMapper objectMapper;

	/** Message digest algorithm. */
	private final String algorithm;

	/** Constructor. */
	public HashCacheKeyConverter(final ObjectMapper objectMapper, final String algorithm) {
		this.objectMapper = objectMapper;
		this.algorithm = algorithm;
		try {
			MessageDigest.getInstance(algorithm);
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("Could not create message digest."), exception);
		}
	}

	/**
	 * @see org.springframework.core.convert.converter.Converter#convert(java.lang.Object)
	 */
	@Override
	public String convert(
			final Object source) {
		try {
			String key = null;
			if (source != null) {
				final byte[] payload = (source instanceof SimpleKey ? source.toString().getBytes() : this.objectMapper.writeValueAsBytes(source));
				final byte[] digest = MessageDigest.getInstance(this.algorithm).digest(payload);
				key = HexFormat.of().formatHex(digest);
			}
			return key;
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("Could not convert cache key."), exception);
		}

	}

}