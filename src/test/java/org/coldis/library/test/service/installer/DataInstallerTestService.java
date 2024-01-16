package org.coldis.library.test.service.installer;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test service.
 */
@RestController
@RequestMapping(path = { "test/data-installer" })
public class DataInstallerTestService {

	/**
	 * Test repository.
	 */
	@Autowired
	private DataInstallerTestRepository testRepository;

	/**
	 * Finds the test entity.
	 *
	 * @param  property1 Test property 1.
	 * @param  property2 Test property 2.
	 * @return           The test entity.
	 */
	@RequestMapping(method = { RequestMethod.GET }, path = "search")
	public DataInstallerTestEntity searchByTest1AndTest2(@RequestParam final Integer property1,
			@RequestParam final Integer property2) {
		return this.testRepository.findById(new DataInstallerTestEntityKey(property1, property2)).orElse(null);
	}

	/**
	 * Finds the test entity by its properties.
	 *
	 * @param  property1 Test property 1.
	 * @param  property2 Test property 2.
	 * @return           The test entity.
	 */
	@RequestMapping(method = { RequestMethod.GET }, path = "{property1}/{property2}")
	public DataInstallerTestEntity findByTest1AndTest2(@PathVariable final Integer property1,
			@PathVariable final Integer property2) {
		return this.testRepository.findById(new DataInstallerTestEntityKey(property1, property2)).orElse(null);
	}

	/**
	 * Updates the test entity.
	 *
	 * @param property1  Test property 1.
	 * @param property2  Test property 2.
	 * @param testEntity Entity data.
	 */
	@Transactional
	@RequestMapping(method = { RequestMethod.PUT }, path = { "{property1}/{property2}" })
	public void update(@PathVariable final Integer property1, @PathVariable final Integer property2,
			@RequestBody final DataInstallerTestEntity testEntity) {
		// Finds the entity.
		final DataInstallerTestEntity retrievedEntity = this.testRepository.findById(new DataInstallerTestEntityKey(property1, property2))
				.orElse(null);
		// Updates the entity properties.
		BeanUtils.copyProperties(testEntity, retrievedEntity, "createdAt", "updatedAt");
		// Saves the entity.
		retrievedEntity.setUpdatedAt(null);
		this.testRepository.save(retrievedEntity);
	}

	/**
	 * Creates the test entity.
	 *
	 * @param testEntity Test entity.
	 */
	@Transactional
	@RequestMapping(method = { RequestMethod.POST })
	public void create(@RequestBody final DataInstallerTestEntity testEntity) {
		this.testRepository.save(testEntity);
	}

}
