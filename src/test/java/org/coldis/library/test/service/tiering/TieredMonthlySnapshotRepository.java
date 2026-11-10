package org.coldis.library.test.service.tiering;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Monthly snapshot repository.
 */
@Repository
public interface TieredMonthlySnapshotRepository extends JpaRepository<TieredMonthlySnapshot, TieredMonthlySnapshotKey> {

	/**
	 * Rows with the moment in a range.
	 *
	 * @param  from From (inclusive).
	 * @param  to   To (exclusive).
	 * @return      Rows.
	 */
	List<TieredMonthlySnapshot> findByCachedAtGreaterThanEqualAndCachedAtLessThan(
			LocalDateTime from,
			LocalDateTime to);

}
