package org.coldis.library.service.tiering;

/**
 * Encodes a row payload (its text as stored) into the bytes kept in the cold tier, and back.
 */
public interface PayloadCodec {

	/**
	 * Encodes a payload.
	 *
	 * @param  payloadText Payload as stored (JSON text, usually).
	 * @return             The bytes to store.
	 */
	byte[] encode(
			String payloadText);

	/**
	 * Decodes stored bytes.
	 *
	 * @param  content Stored bytes.
	 * @return         The payload text.
	 */
	String decode(
			byte[] content);

	/**
	 * Object name extension, with the dot.
	 *
	 * @return The extension.
	 */
	String extension();

	/**
	 * Content type of the stored bytes.
	 *
	 * @return The content type.
	 */
	String contentType();

}
