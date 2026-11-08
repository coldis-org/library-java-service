package org.coldis.library.service.tiering.repository;

import java.util.List;
import org.coldis.library.service.tiering.ColdObject;
import java.util.Map;
import java.util.Optional;

/**
 * Cold tier object store. Callers hand over and get back the encoded bytes; naming is decided
 * above this interface ({@code ObjectKeyStrategy}). Integrity in transit is the store's (the
 * S3 SDK's transfer checksums).
 */
public interface ColdStorageRepository {

	/** Metadata entry carrying the content type (the S3 store sets it on the object). */
	String CONTENT_TYPE_METADATA = "content-type";

	/**
	 * Stores an object, overwriting any object at the key.
	 *
	 * @param  key      Object key.
	 * @param  content  Content.
	 * @param  metadata Metadata stored with the object.
	 * @return          The stored object.
	 */
	ColdObject store(
			String key,
			byte[] content,
			Map<String, String> metadata);

	/**
	 * Loads an object.
	 *
	 * @param  key Object key.
	 * @return     The content, or empty when there is no such object.
	 */
	Optional<byte[]> load(
			String key);

	/**
	 * Removes an object; nothing happens when there is none.
	 *
	 * @param key Object key.
	 */
	void delete(
			String key);

	/**
	 * Every object key under a prefix, in key order (every page).
	 *
	 * @param  prefix Prefix.
	 * @return        The keys.
	 */
	List<String> list(
			String prefix);

}
