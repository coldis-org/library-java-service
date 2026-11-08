package org.coldis.library.service.tiering;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * In-memory cold tier for tests ({@code org.coldis.library.service.tiering.storage=memory}).
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringProperties.PREFIX + ".storage",
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
	 * Removes an object.
	 *
	 * @param key Object key.
	 */
	public void remove(
			final String key) {
		this.objects.remove(key);
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
	 * @see ColdStorageRepository#list(String)
	 */
	@Override
	public List<String> list(
			final String prefix) {
		return this.objects.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
	}

}
