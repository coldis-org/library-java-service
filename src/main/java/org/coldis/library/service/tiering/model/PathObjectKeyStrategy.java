package org.coldis.library.service.tiering.model;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default layout ({@code org.coldis.library.service.tiering.object-key-strategy=path}):
 * {@code <prefix><tableId>/<segment 1>/.../<segment n-1>/<segment n><extension>}. A consumer
 * plugs its own by setting the property to another name and declaring its
 * {@link ObjectKeyStrategy} bean.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.PREFIX + ".object-key-strategy",
		havingValue = PathObjectKeyStrategy.NAME,
		matchIfMissing = true
)
public class PathObjectKeyStrategy implements ObjectKeyStrategy {

	/** Property value selecting this layout. */
	public static final String NAME = "path";

	/**
	 * @see ObjectKeyStrategy#prefixOf(String, TieredTable, List)
	 */
	@Override
	public String prefixOf(
			final String prefix,
			final TieredTable table,
			final List<String> segments) {
		final StringBuilder key = new StringBuilder(prefix).append(table.getId()).append('/');
		for (final String segment : segments) {
			key.append(segment).append('/');
		}
		return key.toString();
	}

	/**
	 * @see ObjectKeyStrategy#keyOf(String, TieredTable, List, String)
	 */
	@Override
	public String keyOf(
			final String prefix,
			final TieredTable table,
			final List<String> segments,
			final String extension) {
		return this.prefixOf(prefix, table, segments.subList(0, segments.size() - 1)) + segments.get(segments.size() - 1) + extension;
	}

	/**
	 * @see ObjectKeyStrategy#lastSegmentOf(String, String)
	 */
	@Override
	public String lastSegmentOf(
			final String objectKey,
			final String extension) {
		final String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
		return fileName.endsWith(extension) ? fileName.substring(0, fileName.length() - extension.length()) : fileName;
	}

}
