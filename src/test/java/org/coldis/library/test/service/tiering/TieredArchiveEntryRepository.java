package org.coldis.library.test.service.tiering;

import org.coldis.library.service.tiering.TieredRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Archive entry repository: JPA plus the tiered reads.
 */
@Repository
public interface TieredArchiveEntryRepository extends JpaRepository<TieredArchiveEntry, Long>, TieredRepository<TieredArchiveEntry, Long> {

}
