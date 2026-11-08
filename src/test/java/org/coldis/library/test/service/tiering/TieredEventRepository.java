package org.coldis.library.test.service.tiering;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Event repository: plain JPA; the tiered reads are on the tiering component.
 */
@Repository
public interface TieredEventRepository extends JpaRepository<TieredEvent, TieredEventKey> {

}
