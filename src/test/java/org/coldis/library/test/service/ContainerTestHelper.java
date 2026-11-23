package org.coldis.library.test.service;

import org.coldis.library.test.SpringTestHelper;
import org.coldis.library.test.TestHelper;
import org.testcontainers.containers.GenericContainer;

/**
 * Container test helper.
 */
public class ContainerTestHelper extends SpringTestHelper {

	/**
	 * Seconds a container waits, after the last test class of a fork released
	 * it, before it stops. The shared Spring context of a fork keeps the
	 * containers' ports for the whole fork, so a container stopped while the
	 * fork still runs other classes (e.g. long ones without containers) breaks
	 * every later class of that fork. Long enough to outlast those gaps; the
	 * containers still go when the fork's JVM exits.
	 */
	public static final long CONTAINER_STOP_DELAY_SECONDS = 300;

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
