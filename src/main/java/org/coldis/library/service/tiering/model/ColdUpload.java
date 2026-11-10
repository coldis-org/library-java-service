package org.coldis.library.service.tiering.model;

/**
 * A streaming upload of one cold object, fed part by part (S3 multipart, a temporary file, a
 * buffer). {@link #complete()} publishes the object; {@link #abort()} discards it.
 */
public interface ColdUpload {

	/**
	 * Appends a part. Every part but the last must respect the storage's minimum part size.
	 *
	 * @param content Part bytes.
	 */
	void part(
			byte[] content);

	/**
	 * Publishes the object.
	 *
	 * @return The stored object.
	 */
	ColdObject complete();

	/**
	 * Discards the upload (best effort).
	 */
	void abort();

}
