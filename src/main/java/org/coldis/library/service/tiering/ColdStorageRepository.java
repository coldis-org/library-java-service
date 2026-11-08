package org.coldis.library.service.tiering;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cold tier object store. Callers hand over and get back the encoded bytes; naming is decided
 * above this interface ({@link ObjectKeyStrategy}). Integrity in transit is the store's (the
 * S3 SDK's transfer checksums).
 */
public interface ColdStorageRepository {

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
	 * Every object key under a prefix, in key order (every page).
	 *
	 * @param  prefix Prefix.
	 * @return        The keys.
	 */
	List<String> list(
			String prefix);

}
