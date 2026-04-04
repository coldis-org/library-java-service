package org.coldis.library.service.statistics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Statistics context configuration repository. */
@Repository
public interface StatisticsContextConfigurationRepository
    extends JpaRepository<StatisticsContextConfiguration, String> {}
