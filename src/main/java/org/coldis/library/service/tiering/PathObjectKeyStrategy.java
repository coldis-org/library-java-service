package org.coldis.library.service.tiering;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default layout: {@code <prefix><tableId>/<segment 1>/.../<segment n-1>/<segment n><extension>}.
 */
@Component
@ConditionalOnMissingBean(
		value = ObjectKeyStrategy.class,
		ignored = PathObjectKeyStrategy.class
)
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class PathObjectKeyStrategy implements ObjectKeyStrategy {

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
