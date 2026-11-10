package org.coldis.library.service.tiering.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.coldis.library.service.tiering.model.ColdObject;
import org.coldis.library.service.tiering.model.ColdUpload;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Cold storage on S3, through the consumer's {@link S3Client}. Integrity in transit is the SDK's
 * checksums; keys are the derived object keys. Range reads and multipart uploads serve partition
 * tiering.
 */
public class S3ColdStorageRepository implements ColdStorageRepository {

	/** S3 multipart minimum part size (5 MiB). */
	private static final Integer MINIMUM_PART_SIZE = 5 * 1024 * 1024;

	/** Client. */
	private final S3Client s3Client;

	/** Bucket. */
	private final String bucket;

	/**
	 * Constructor.
	 *
	 * @param s3Client Client.
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
	 * @see ColdStorageRepository#loadRange(String, Long, Integer)
	 */
	@Override
	public Optional<byte[]> loadRange(
			final String key,
			final Long offset,
			final Integer length) {
		Optional<byte[]> content;
		try {
			content = Optional.of(this.s3Client.getObjectAsBytes(
					GetObjectRequest.builder().bucket(this.bucket).key(key).range("bytes=" + offset + "-" + ((offset + length) - 1)).build()).asByteArray());
		}
		catch (final NoSuchKeyException exception) {
			content = Optional.empty();
		}
		return content;
	}

	/**
	 * @see ColdStorageRepository#head(String)
	 */
	@Override
	public Optional<ColdObject> head(
			final String key) {
		Optional<ColdObject> object;
		try {
			object = Optional.of(new ColdObject(key, this.s3Client.headObject(HeadObjectRequest.builder().bucket(this.bucket).key(key).build()).contentLength()));
		}
		catch (final NoSuchKeyException exception) {
			object = Optional.empty();
		}
		return object;
	}

	/**
	 * @see ColdStorageRepository#minimumPartSize()
	 */
	@Override
	public Integer minimumPartSize() {
		return S3ColdStorageRepository.MINIMUM_PART_SIZE;
	}

	/**
	 * @see ColdStorageRepository#beginUpload(String, Map)
	 */
	@Override
	public ColdUpload beginUpload(
			final String key,
			final Map<String, String> metadata) {
		final String uploadId = this.s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(this.bucket).key(key)
				.contentType(metadata.get(ColdStorageRepository.CONTENT_TYPE_METADATA)).metadata(metadata).build()).uploadId();
		return new ColdUpload() {

			/** Completed parts. */
			private final List<CompletedPart> parts = new ArrayList<>();

			/** Bytes. */
			private Long size = 0L;

			@Override
			public void part(
					final byte[] content) {
				final int number = this.parts.size() + 1;
				final String etag = S3ColdStorageRepository.this.s3Client.uploadPart(UploadPartRequest.builder().bucket(S3ColdStorageRepository.this.bucket)
						.key(key).uploadId(uploadId).partNumber(number).build(), RequestBody.fromBytes(content)).eTag();
				this.parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
				this.size += content.length;
			}

			@Override
			public ColdObject complete() {
				S3ColdStorageRepository.this.s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(S3ColdStorageRepository.this.bucket)
						.key(key).uploadId(uploadId).multipartUpload(CompletedMultipartUpload.builder().parts(this.parts).build()).build());
				return new ColdObject(key, this.size);
			}

			@Override
			public void abort() {
				S3ColdStorageRepository.this.s3Client
						.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(S3ColdStorageRepository.this.bucket).key(key).uploadId(uploadId).build());
			}
		};
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
