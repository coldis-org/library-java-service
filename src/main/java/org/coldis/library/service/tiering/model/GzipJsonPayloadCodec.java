package org.coldis.library.service.tiering.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default codec ({@code org.coldis.library.service.tiering.payload-codec=gzip-json}): the row's
 * JSON, UTF-8, gzip. A consumer plugs its own by setting the property to another name and
 * declaring its {@link PayloadCodec} bean.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.PREFIX + ".payload-codec",
		havingValue = GzipJsonPayloadCodec.NAME,
		matchIfMissing = true
)
public class GzipJsonPayloadCodec implements PayloadCodec {

	/** Property value selecting this codec. */
	public static final String NAME = "gzip-json";

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
