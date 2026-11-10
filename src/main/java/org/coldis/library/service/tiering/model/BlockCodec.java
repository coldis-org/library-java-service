package org.coldis.library.service.tiering.model;

/**
 * Compression of one block of rows (an independently decompressible frame).
 */
public interface BlockCodec {

	/**
	 * Codec name, recorded in every shard index.
	 *
	 * @return Name.
	 */
	String name();

	/**
	 * Compresses a block.
	 *
	 * @param  content Block bytes.
	 * @return         Frame.
	 */
	byte[] compress(
			byte[] content);

	/**
	 * Decompresses a frame, refusing one that expands past the bound (the size recorded in the
	 * index).
	 *
	 * @param  frame     Frame bytes.
	 * @param  maxLength Maximum block bytes.
	 * @return           Block bytes.
	 */
	byte[] decompress(
			byte[] frame,
			Integer maxLength);

}
