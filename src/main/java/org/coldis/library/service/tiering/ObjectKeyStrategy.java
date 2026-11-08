package org.coldis.library.service.tiering;

import java.util.List;

/**
 * Derives the cold-tier object key of a row from its primary key, so a read is a {@code GET} by
 * exact key and a "newest on or before" read is a {@code LIST} on the other key columns' prefix.
 * Segments are the canonical (URL-safe, order-preserving) forms of the key values
 * ({@link ColumnValues#toSegment(String, String)}), in key column order; the last key column is
 * the file name. A strategy may transform segments (hash them with a keyed HMAC, say) as long as
 * {@link #lastSegmentOf(String, String)} gives the file name back.
 */
public interface ObjectKeyStrategy {

	/**
	 * The object key of a row.
	 *
	 * @param  prefix    Configured key prefix.
	 * @param  table     Table.
	 * @param  segments  Canonical segments of every key column.
	 * @param  extension Codec extension.
	 * @return           The object key.
	 */
	String keyOf(
			String prefix,
			TieredTable table,
			List<String> segments,
			String extension);

	/**
	 * The listing prefix of the rows sharing the leading key columns.
	 *
	 * @param  prefix   Configured key prefix.
	 * @param  table    Table.
	 * @param  segments Canonical segments of the leading key columns (all but the last).
	 * @return          The listing prefix.
	 */
	String prefixOf(
			String prefix,
			TieredTable table,
			List<String> segments);

	/**
	 * The last segment (file name without extension) of an object key, as {@link #keyOf} built it.
	 *
	 * @param  objectKey Object key.
	 * @param  extension Codec extension.
	 * @return           The last segment.
	 */
	String lastSegmentOf(
			String objectKey,
			String extension);

}
