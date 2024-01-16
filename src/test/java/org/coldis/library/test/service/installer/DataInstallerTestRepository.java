package org.coldis.library.test.service.installer;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Test repository.
 */
@Repository
public interface DataInstallerTestRepository extends CrudRepository<DataInstallerTestEntity, DataInstallerTestEntityKey> {

}
