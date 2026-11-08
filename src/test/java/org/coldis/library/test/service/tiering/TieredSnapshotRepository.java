package org.coldis.library.test.service.tiering;

import org.coldis.library.service.tiering.TieredRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Snapshot repository: JPA plus the tiered reads.
 */
@Repository
public interface TieredSnapshotRepository extends JpaRepository<TieredSnapshot, TieredSnapshotKey>, TieredRepository<TieredSnapshot, TieredSnapshotKey> {

}
