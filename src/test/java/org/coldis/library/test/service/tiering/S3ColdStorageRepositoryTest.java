package org.coldis.library.test.service.tiering;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.coldis.library.service.tiering.model.ColdObject;
import org.coldis.library.service.tiering.model.ColdUpload;
import org.coldis.library.service.tiering.repository.ColdStorageRepository;
import org.coldis.library.service.tiering.repository.S3ColdStorageRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;

/**
 * The S3 adapter against an S3-compatible container (LocalStack, pinned by digest): multipart
 * upload with minimum-size parts and a short last part, range reads at the start, middle and
 * end, head on present and missing keys, abort, store/load/list/delete. No Spring context.
 */
public class S3ColdStorageRepositoryTest {

	/** LocalStack image, pinned by digest. */
	private static final DockerImageName IMAGE = DockerImageName
			.parse("localstack/localstack@sha256:b279c01f4cfb8f985a482e4014cabc1e2697b9d7a6c8c8db2e40f4d9f93687c7").asCompatibleSubstituteFor("localstack/localstack");

	/** Bucket. */
	private static final String BUCKET = "cold-tiering-test";

	/** LocalStack's dummy credentials: any value is accepted by the container, nothing real. */
	private static final String LOCALSTACK_DUMMY_CREDENTIAL = "test";

	/** Container. */
	private static GenericContainer<?> container;

	/** Client. */
	private static S3Client client;

	/** Repository. */
	private static S3ColdStorageRepository repository;

	@BeforeAll
	public static void beforeAll() {
		S3ColdStorageRepositoryTest.container = new GenericContainer<>(S3ColdStorageRepositoryTest.IMAGE).withExposedPorts(4566).withEnv("SERVICES", "s3")
				.waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200));
		S3ColdStorageRepositoryTest.container.start();
		final URI endpoint = URI.create("http://" + S3ColdStorageRepositoryTest.container.getHost() + ":" + S3ColdStorageRepositoryTest.container.getMappedPort(4566));
		S3ColdStorageRepositoryTest.client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3ColdStorageRepositoryTest.LOCALSTACK_DUMMY_CREDENTIAL, S3ColdStorageRepositoryTest.LOCALSTACK_DUMMY_CREDENTIAL)))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				// LocalStack 3.8 rejects the SDK's default per-request checksum next to the CRC32C the
				// repository declares; real S3 accepts both, so only the test client narrows it.
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED).build();
		S3ColdStorageRepositoryTest.client.createBucket(CreateBucketRequest.builder().bucket(S3ColdStorageRepositoryTest.BUCKET).build());
		S3ColdStorageRepositoryTest.repository = new S3ColdStorageRepository(S3ColdStorageRepositoryTest.client, S3ColdStorageRepositoryTest.BUCKET);
	}

	@AfterAll
	public static void afterAll() {
		if (S3ColdStorageRepositoryTest.client != null) {
			S3ColdStorageRepositoryTest.client.close();
		}
		if (S3ColdStorageRepositoryTest.container != null) {
			S3ColdStorageRepositoryTest.container.stop();
		}
	}

	/**
	 * Deterministic random bytes.
	 *
	 * @param  length Length.
	 * @param  seed   Seed.
	 * @return        Bytes.
	 */
	private static byte[] bytes(
			final Integer length,
			final Long seed) {
		final byte[] content = new byte[length];
		new Random(seed).nextBytes(content);
		return content;
	}

	@Test
	@DisplayName("Multipart upload, range reads, head, abort, store, load, list and delete")
	public void testStorage() {
		final ColdStorageRepository storage = S3ColdStorageRepositoryTest.repository;
		final Integer minimum = storage.minimumPartSize();
		Assertions.assertEquals(5 * 1024 * 1024, minimum);
		// Two minimum-size parts and a short last part.
		final byte[] first = S3ColdStorageRepositoryTest.bytes(minimum, 1L);
		final byte[] second = S3ColdStorageRepositoryTest.bytes(minimum, 2L);
		final byte[] last = S3ColdStorageRepositoryTest.bytes(1_234, 3L);
		final ColdUpload upload = storage.beginUpload("t/a/0001.blocks", Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA, "application/octet-stream", "table", "t"));
		upload.part(first);
		upload.part(second);
		upload.part(last);
		final ColdObject object = upload.complete();
		final long total = (2L * minimum) + last.length;
		Assertions.assertEquals(total, object.sizeBytes());
		Assertions.assertEquals(total, storage.head("t/a/0001.blocks").orElseThrow().sizeBytes());
		Assertions.assertTrue(storage.head("t/a/missing.blocks").isEmpty());
		// Ranges: start, across the first part boundary, end.
		Assertions.assertArrayEquals(Arrays.copyOfRange(first, 0, 100), storage.loadRange("t/a/0001.blocks", 0L, 100).orElseThrow());
		final byte[] middle = storage.loadRange("t/a/0001.blocks", (long) minimum - 10, 20).orElseThrow();
		Assertions.assertArrayEquals(Arrays.copyOfRange(first, minimum - 10, minimum), Arrays.copyOfRange(middle, 0, 10));
		Assertions.assertArrayEquals(Arrays.copyOfRange(second, 0, 10), Arrays.copyOfRange(middle, 10, 20));
		Assertions.assertArrayEquals(Arrays.copyOfRange(last, last.length - 50, last.length), storage.loadRange("t/a/0001.blocks", total - 50, 50).orElseThrow());
		Assertions.assertTrue(storage.loadRange("t/a/missing.blocks", 0L, 10).isEmpty());
		// Abort leaves no object and no pending upload.
		final ColdUpload aborted = storage.beginUpload("t/a/0002.blocks", Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA, "application/octet-stream"));
		aborted.part(first);
		aborted.abort();
		Assertions.assertTrue(storage.head("t/a/0002.blocks").isEmpty());
		Assertions.assertTrue(S3ColdStorageRepositoryTest.client
				.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(S3ColdStorageRepositoryTest.BUCKET).prefix("t/a/0002").build()).uploads().isEmpty());
		// An upload completed with no part (an empty shard) is an empty object, not a refused completion.
		final ColdUpload emptyUpload = storage.beginUpload("t/a/0003.blocks", Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA, "application/octet-stream"));
		Assertions.assertEquals(0L, emptyUpload.complete().sizeBytes());
		Assertions.assertDoesNotThrow(emptyUpload::abort, "abort after completion is a no-op");
		Assertions.assertDoesNotThrow(upload::abort);
		Assertions.assertEquals(total, storage.head("t/a/0001.blocks").orElseThrow().sizeBytes());
		Assertions.assertEquals(0L, storage.head("t/a/0003.blocks").orElseThrow().sizeBytes());
		Assertions.assertEquals(0, storage.load("t/a/0003.blocks").orElseThrow().length);
		Assertions.assertTrue(S3ColdStorageRepositoryTest.client
				.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(S3ColdStorageRepositoryTest.BUCKET).prefix("t/a/0003").build()).uploads().isEmpty());
		storage.delete("t/a/0003.blocks");
		// Store, load, list, delete.
		storage.store("t/b/row.json.gz", last, Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA, "application/gzip"));
		Assertions.assertArrayEquals(last, storage.load("t/b/row.json.gz").orElseThrow());
		Assertions.assertEquals(Optional.empty(), storage.load("t/b/none.json.gz"));
		Assertions.assertEquals(List.of("t/a/0001.blocks", "t/b/row.json.gz"), storage.list("t/"));
		storage.delete("t/b/row.json.gz");
		Assertions.assertTrue(storage.load("t/b/row.json.gz").isEmpty());
		Assertions.assertEquals(List.of("t/a/0001.blocks"), storage.list("t/"));
	}

}
