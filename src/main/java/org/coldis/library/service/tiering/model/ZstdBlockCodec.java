package org.coldis.library.service.tiering.model;

import com.github.luben.zstd.Zstd;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Zstandard block codec (zstd-jni, optional: the consumer adds the dependency and sets
 * {@code block-codec=zstd}). Frames carry the original size so decompression needs no index
 * field.
 */
@Component
@ConditionalOnClass(Zstd.class)
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.PREFIX + ".block-codec",
		havingValue = ZstdBlockCodec.NAME
)
public class ZstdBlockCodec implements BlockCodec {

	/** Name. */
	public static final String NAME = "zstd";

	/** Compression level. */
	private static final int LEVEL = 3;

	/**
	 * @see BlockCodec#name()
	 */
	@Override
	public String name() {
		return ZstdBlockCodec.NAME;
	}

	/**
	 * @see BlockCodec#compress(byte[])
	 */
	@Override
	public byte[] compress(
			final byte[] content) {
		return Zstd.compress(content, ZstdBlockCodec.LEVEL);
	}

	/**
	 * @see BlockCodec#decompress(byte[])
	 */
	@Override
	public byte[] decompress(
			final byte[] frame) {
		return Zstd.decompress(frame, (int) Zstd.decompressedSize(frame));
	}

}
