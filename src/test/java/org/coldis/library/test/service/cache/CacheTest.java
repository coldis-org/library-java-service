package org.coldis.library.test.service.cache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
public class CacheTest {

	/**
	 * Expiration.
	 */
	@Value("${org.coldis.configuration.cache.millis-expiration}")
	private Long expiration;

	/**
	 * Cached service.
	 */
	@Autowired
	private CachedService cachedService;

	/**
	 * Tests the local cache.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testCentralCache() throws Exception {
		final Integer attr1 = this.cachedService.getFromCentralCache1();
		Assertions.assertEquals(attr1, this.cachedService.getFromCentralCache1());
		Assertions.assertEquals(attr1, this.cachedService.getFromCentralCache1());
		Assertions.assertEquals(attr1, this.cachedService.getFromCentralCache1());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromCentralCache1());
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromCentralCache1());
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromCentralCache1());

		final Integer attr2 = this.cachedService.getFromCentralCache2().getAttribute();
		Assertions.assertEquals(attr2, this.cachedService.getFromCentralCache2().getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromCentralCache2().getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromCentralCache2().getAttribute());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromCentralCache2().getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromCentralCache2().getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromCentralCache2().getAttribute());

		final Integer attr3 = this.cachedService.getFromCentralCache3().intValue();
		Assertions.assertEquals(attr3, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromCentralCache3().intValue());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromCentralCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromCentralCache3().intValue());

		final Integer attr4 = this.cachedService.getFromCentralCache4().getAttribute().intValue();
		Assertions.assertEquals(attr4, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromCentralCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromCentralCache4().getAttribute().intValue());

	}

	/**
	 * Tests the local cache.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testLocalCache() throws Exception {
		final Integer attr1 = this.cachedService.getFromLocalCache1();
		Assertions.assertEquals(attr1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1, this.cachedService.getFromLocalCache1());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromLocalCache1());
		Assertions.assertEquals(attr1 + 1, this.cachedService.getFromLocalCache1());

		final Integer attr2 = this.cachedService.getFromLocalCache2().getAttribute();
		Assertions.assertEquals(attr2, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2, this.cachedService.getFromLocalCache2().getAttribute());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromLocalCache2().getAttribute());
		Assertions.assertEquals(attr2 + 1, this.cachedService.getFromLocalCache2().getAttribute());

		final Integer attr3 = this.cachedService.getFromLocalCache3().intValue();
		Assertions.assertEquals(attr3, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3, this.cachedService.getFromLocalCache3().intValue());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromLocalCache3().intValue());
		Assertions.assertEquals(attr3 + 1, this.cachedService.getFromLocalCache3().intValue());

		final Integer attr4 = this.cachedService.getFromLocalCache4().getAttribute().intValue();
		Assertions.assertEquals(attr4, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Thread.sleep(this.expiration );
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromLocalCache4().getAttribute().intValue());
		Assertions.assertEquals(attr4 + 1, this.cachedService.getFromLocalCache4().getAttribute().intValue());

	}

}
