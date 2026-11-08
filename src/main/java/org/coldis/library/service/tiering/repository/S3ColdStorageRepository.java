package org.coldis.library.service.tiering.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.service.tiering.model.ColdObject;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * S3 cold tier, on the consumer's {@link S3Client} bean (see {@link ColdTieringS3Configuration}).
 * The SDK's transfer checksums cover the integrity of every PUT and GET.
 */
public class S3ColdStorageRepository implements ColdStorageRepository {

	/** S3 client. */
	private final S3Client s3Client;

	/** Bucket. */
	private final String bucket;

	/**
	 * Constructor.
	 *
	 * @param s3Client S3 client.
	 * @param bucket   Bucket.
	 */
	public S3ColdStorageRepository(
			final S3Client s3Client,
			final String bucket) {
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	/**
	 * @see ColdStorageRepository#store(String, byte[], Map)
	 */
	@Override
	public ColdObject store(
			final String key,
			final byte[] content,
			final Map<String, String> metadata) {
		this.s3Client.putObject(PutObjectRequest.builder().bucket(this.bucket).key(key).contentType(metadata.get(ColdStorageRepository.CONTENT_TYPE_METADATA))
				.metadata(metadata).build(), RequestBody.fromBytes(content));
		return new ColdObject(key, (long) content.length);
	}

	/**
	 * @see ColdStorageRepository#load(String)
	 */
	@Override
	public Optional<byte[]> load(
			final String key) {
		Optional<byte[]> content;
		try {
			content = Optional.of(this.s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(this.bucket).key(key).build()).asByteArray());
		}
		catch (final NoSuchKeyException exception) {
			content = Optional.empty();
		}
		return content;
	}

	/**
	 * @see ColdStorageRepository#delete(String)
	 */
	@Override
	public void delete(
			final String key) {
		this.s3Client.deleteObject(DeleteObjectRequest.builder().bucket(this.bucket).key(key).build());
	}

	/**
	 * @see ColdStorageRepository#list(String)
	 */
	@Override
	public List<String> list(
			final String prefix) {
		final List<String> keys = new ArrayList<>();
		for (final ListObjectsV2Response page : this.s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(this.bucket).prefix(prefix).build())) {
			for (final S3Object object : page.contents()) {
				keys.add(object.key());
			}
		}
		return keys;
	}

}
