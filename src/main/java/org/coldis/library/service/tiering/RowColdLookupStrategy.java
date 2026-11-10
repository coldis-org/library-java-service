package org.coldis.library.service.tiering;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.service.tiering.model.ArchiveUnit;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.repository.TieredRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cold lookups of row-tiered tables: one object per row at a derived key, "newest on or before"
 * by listing the prefix. First-in-interval and timeline reads have no cold side for this unit.
 */
@Component
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class RowColdLookupStrategy implements ColdLookupStrategy {

	/** Repository. */
	@Autowired
	private TieredRepository tieredRepository;

	/**
	 * Canonical segment of a key's last column.
	 *
	 * @param  table Table.
	 * @param  key   Key.
	 * @return       Segment.
	 */
	private static String lastSegmentOf(
			final TieredTable table,
			final TieredRowKey key) {
		return ColumnValues.toSegment(table.getColumnTypes().get(table.getLastKeyColumn()), key.values().get(table.getLastKeyColumn()));
	}

	/**
	 * @see ColdLookupStrategy#unit()
	 */
	@Override
	public ArchiveUnit unit() {
		return ArchiveUnit.ROW;
	}

	/**
	 * @see ColdLookupStrategy#findCold(TieredTable, TieredRowKey)
	 */
	@Override
	public Optional<ColdRow> findCold(
			final TieredTable table,
			final TieredRowKey key) {
		return this.tieredRepository.loadCold(table, key);
	}

	/**
	 * @see ColdLookupStrategy#findNewestCold(TieredTable, Map, LocalDateTime, Optional)
	 */
	@Override
	public Optional<ColdRow> findNewestCold(
			final TieredTable table,
			final Map<String, String> prefix,
			final LocalDateTime asOf,
			final Optional<TieredRowKey> hotCandidate) {
		final Map<String, Object> keyPrefix = new LinkedHashMap<>();
		prefix.forEach((
				column,
				value) -> keyPrefix.put(column, ColumnValues.parse(table.getColumnTypes().get(column), value)));
		return this.tieredRepository.findNewestColdKey(table, keyPrefix, asOf)
				.filter(coldKey -> hotCandidate.isEmpty()
						|| (RowColdLookupStrategy.lastSegmentOf(table, hotCandidate.get()).compareTo(RowColdLookupStrategy.lastSegmentOf(table, coldKey)) < 0))
				.flatMap(coldKey -> this.tieredRepository.loadCold(table, coldKey));
	}

	/**
	 * @see ColdLookupStrategy#findFirstCold(TieredTable, Map, LocalDateTime, LocalDateTime, Optional)
	 */
	@Override
	public Optional<ColdRow> findFirstCold(
			final TieredTable table,
			final Map<String, String> prefix,
			final LocalDateTime after,
			final LocalDateTime upTo,
			final Optional<TieredRowKey> hotCandidate) {
		return Optional.empty();
	}

	/**
	 * @see ColdLookupStrategy#isArchived(TieredTable, TieredRowKey)
	 */
	@Override
	public Boolean isArchived(
			final TieredTable table,
			final TieredRowKey key) {
		return this.tieredRepository.loadCold(table, key).isPresent();
	}

	/**
	 * @see ColdLookupStrategy#findTimelineCold(TieredTable, Map, LocalDateTime)
	 */
	@Override
	public List<ColdRow> findTimelineCold(
			final TieredTable table,
			final Map<String, String> prefix,
			final LocalDateTime asOf) {
		return List.of();
	}

}
