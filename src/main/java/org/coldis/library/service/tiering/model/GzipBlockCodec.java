package org.coldis.library.service.tiering.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gzip block codec (JDK, the default).
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.PREFIX + ".block-codec",
		havingValue = GzipBlockCodec.NAME,
		matchIfMissing = true
)
public class GzipBlockCodec implements BlockCodec {

	/** Name. */
	public static final String NAME = "gzip";

	/**
	 * @see BlockCodec#name()
	 */
	@Override
	public String name() {
		return GzipBlockCodec.NAME;
	}

	/**
	 * @see BlockCodec#compress(byte[])
	 */
	@Override
	public byte[] compress(
			final byte[] content) {
		final ByteArrayOutputStream output = new ByteArrayOutputStream(content.length / 4);
		try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
			gzip.write(content);
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.block.compress.error"), exception);
		}
		return output.toByteArray();
	}

	/**
	 * @see BlockCodec#decompress(byte[])
	 */
	@Override
	public byte[] decompress(
			final byte[] frame) {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(frame))) {
			return gzip.readAllBytes();
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.block.decompress.error"), exception);
		}
	}

}
