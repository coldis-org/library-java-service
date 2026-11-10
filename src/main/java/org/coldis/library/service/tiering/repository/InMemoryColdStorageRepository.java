package org.coldis.library.service.tiering.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

import java.io.ByteArrayOutputStream;

import org.coldis.library.service.tiering.model.ColdObject;
import org.coldis.library.service.tiering.model.ColdUpload;
import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory cold tier ({@code org.coldis.library.service.tiering.storage=memory}): library-provided
 * support for tests, kept on purpose beside the folder one.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.STORAGE,
		havingValue = "memory"
)
public class InMemoryColdStorageRepository implements ColdStorageRepository {

	/** Objects by key, in key order. */
	private final ConcurrentSkipListMap<String, byte[]> objects = new ConcurrentSkipListMap<>();

	/**
	 * Drops every object.
	 */
	public void reset() {
		this.objects.clear();
	}

	/**
	 * Replaces the content of an object (corruption in tests).
	 *
	 * @param key     Object key.
	 * @param content New content.
	 */
	public void overwrite(
			final String key,
			final byte[] content) {
		this.objects.put(key, content);
	}


	/**
	 * Number of objects.
	 *
	 * @return The count.
	 */
	public Integer count() {
		return this.objects.size();
	}

	/**
	 * @see ColdStorageRepository#store(String, byte[], Map)
	 */
	@Override
	public ColdObject store(
			final String key,
			final byte[] content,
			final Map<String, String> metadata) {
		this.objects.put(key, content.clone());
		return new ColdObject(key, (long) content.length);
	}

	/**
	 * @see ColdStorageRepository#load(String)
	 */
	@Override
	public Optional<byte[]> load(
			final String key) {
		return Optional.ofNullable(this.objects.get(key)).map(byte[]::clone);
	}

	/**
	 * @see ColdStorageRepository#delete(String)
	 */
	@Override
	public void delete(
			final String key) {
		this.objects.remove(key);
	}

	/**
	 * @see ColdStorageRepository#list(String)
	 */
	@Override
	public List<String> list(
			final String prefix) {
		return this.objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
	}


	/**
	 * @see ColdStorageRepository#loadRange(String, Long, Integer)
	 */
	@Override
	public Optional<byte[]> loadRange(
			final String key,
			final Long offset,
			final Integer length) {
		return Optional.ofNullable(this.objects.get(key))
				.map(content -> java.util.Arrays.copyOfRange(content, offset.intValue(), (int) Math.min(content.length, offset + length)));
	}

	/**
	 * @see ColdStorageRepository#head(String)
	 */
	@Override
	public Optional<ColdObject> head(
			final String key) {
		return Optional.ofNullable(this.objects.get(key)).map(content -> new ColdObject(key, (long) content.length));
	}

	/**
	 * @see ColdStorageRepository#beginUpload(String, Map)
	 */
	@Override
	public ColdUpload beginUpload(
			final String key,
			final Map<String, String> metadata) {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		return new ColdUpload() {

			@Override
			public void part(
					final byte[] content) {
				buffer.writeBytes(content);
			}

			@Override
			public ColdObject complete() {
				return InMemoryColdStorageRepository.this.store(key, buffer.toByteArray(), metadata);
			}

			@Override
			public void abort() {
				buffer.reset();
			}
		};
	}

}
