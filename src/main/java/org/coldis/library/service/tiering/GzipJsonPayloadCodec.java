package org.coldis.library.service.tiering;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default codec: the payload text, UTF-8, gzip.
 */
@Component
@ConditionalOnMissingBean(
		value = PayloadCodec.class,
		ignored = GzipJsonPayloadCodec.class
)
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class GzipJsonPayloadCodec implements PayloadCodec {

	/**
	 * @see PayloadCodec#encode(String)
	 */
	@Override
	public byte[] encode(
			final String payloadText) {
		final ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutput = new GZIPOutputStream(compressedOutput)) {
			gzipOutput.write(payloadText.getBytes(StandardCharsets.UTF_8));
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.codec.encode.error"), exception);
		}
		return compressedOutput.toByteArray();
	}

	/**
	 * @see PayloadCodec#decode(byte[])
	 */
	@Override
	public String decode(
			final byte[] content) {
		try (GZIPInputStream gzipInput = new GZIPInputStream(new ByteArrayInputStream(content))) {
			return new String(gzipInput.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.codec.decode.error"), exception);
		}
	}

	/**
	 * @see PayloadCodec#extension()
	 */
	@Override
	public String extension() {
		return ".json.gz";
	}

	/**
	 * @see PayloadCodec#contentType()
	 */
	@Override
	public String contentType() {
		return "application/gzip";
	}

}
