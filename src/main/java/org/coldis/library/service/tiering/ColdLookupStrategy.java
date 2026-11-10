package org.coldis.library.service.tiering;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.service.tiering.model.ArchiveUnit;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;

/**
 * Cold lookups of one archive unit. {@link ColdTieringServiceComponent} runs the hot side and the
 * rehydration; the strategy only finds cold rows, so the component never tests the unit nor
 * casts the definition. Key prefixes are formatted column values, validated by the component.
 */
public interface ColdLookupStrategy {

	/**
	 * Archive unit served.
	 *
	 * @return Unit.
	 */
	ArchiveUnit unit();

	/**
	 * Cold row by full key.
	 *
	 * @param  table Table.
	 * @param  key   Key.
	 * @return       Row.
	 */
	Optional<ColdRow> findCold(
			TieredTable table,
			TieredRowKey key);

	/**
	 * Newest cold row of a key prefix on or before a moment, and strictly newer than the hot
	 * candidate when there is one.
	 *
	 * @param  table        Table.
	 * @param  prefix       Formatted key prefix.
	 * @param  asOf         Inclusive upper bound, or null.
	 * @param  hotCandidate Newest hot row's key, when any.
	 * @return              Row.
	 */
	Optional<ColdRow> findNewestCold(
			TieredTable table,
			Map<String, String> prefix,
			LocalDateTime asOf,
			Optional<TieredRowKey> hotCandidate);

	/**
	 * First cold row of a key prefix in {@code (after, upTo]}, and strictly older than the hot
	 * candidate when there is one.
	 *
	 * @param  table        Table.
	 * @param  prefix       Formatted key prefix.
	 * @param  after        Exclusive lower bound, or null.
	 * @param  upTo         Inclusive upper bound, or null.
	 * @param  hotCandidate First hot row's key, when any.
	 * @return              Row.
	 */
	Optional<ColdRow> findFirstCold(
			TieredTable table,
			Map<String, String> prefix,
			LocalDateTime after,
			LocalDateTime upTo,
			Optional<TieredRowKey> hotCandidate);

	/**
	 * Every cold row of a key prefix on or before a moment (the hot rows win per key in the
	 * component).
	 *
	 * @param  table  Table.
	 * @param  prefix Formatted key prefix.
	 * @param  asOf   Inclusive upper bound, or null.
	 * @return        Rows.
	 */
	List<ColdRow> findTimelineCold(
			TieredTable table,
			Map<String, String> prefix,
			LocalDateTime asOf);

}
