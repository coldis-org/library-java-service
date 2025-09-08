package org.coldis.library.test.service;

import org.coldis.library.test.SpringTestHelper;
import org.coldis.library.test.TestHelper;
import org.testcontainers.containers.GenericContainer;

/**
 * Container test helper.
 */
public class ContainerTestHelper extends SpringTestHelper {

	/**
	 * Redis container.
	 */
	public static GenericContainer<?> REDIS_CONTAINER = TestHelper.createRedisContainer();

	/**
	 * Postgres container.
	 */
	public static GenericContainer<?> POSTGRES_CONTAINER = TestHelper.createPostgresContainer();

	/**
	 * Artemis container.
	 */
	public static GenericContainer<?> ARTEMIS_CONTAINER = TestHelper.createArtemisContainer();

}
