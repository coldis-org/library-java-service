package org.coldis.library.service.tiering.repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.tiering.model.BlockCodec;
import org.coldis.library.service.tiering.model.BlockEntry;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.service.tiering.model.ColdUpload;
import org.coldis.library.service.tiering.model.ObjectKeyStrategy;
import org.coldis.library.service.tiering.model.PartitionManifest;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.PayloadCodec;
import org.coldis.library.service.tiering.model.ShardIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cold access to a partition's sharded objects: manifest and shard indexes (gzip JSON, cached in
 * memory for a TTL), blocks by range read, rows by key prefix.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringPropertyNames.ENABLED,
		matchIfMissing = false
)
public class PartitionTieredRepository {

	/** Manifest file segment. */
	public static final String MANIFEST_SEGMENT = "manifest";

	/** Index extension. */
	public static final String INDEX_EXTENSION = ".index.json.gz";

	/** Blocks extension. */
	public static final String BLOCKS_EXTENSION = ".blocks";

	/** Mapper. */
	private static final ObjectMapper OBJECT_MAPPER = ObjectMapperHelper.createMapper();

	/** Cached value. */
	private record Cached(Long expiresAt, Object value) {

	}

	/** Object prefix. */
	@Value(ColdTieringPropertyNames.OBJECT_PREFIX_PLACEHOLDER)
	private String objectPrefix;

	/** Index cache TTL. */
	@Value("${" + ColdTieringPropertyNames.PREFIX + ".index-cache.ttl:PT5M}")
	private Duration indexCacheTtl;

	/** Cold storage. */
	@Autowired
	private ColdStorageRepository coldStorageRepository;

	/** Payload codec (manifests and indexes). */
	@Autowired
	private PayloadCodec payloadCodec;

	/** Block codecs by name. */
	@Autowired
	private List<BlockCodec> blockCodecs;

	/** Key strategy. */
	@Autowired
	private ObjectKeyStrategy objectKeyStrategy;

	/** Cache. */
	private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

	/** Range reads performed (tests). */
	private Long rangeReads = 0L;

	/**
	 * Shard file name.
	 *
	 * @param  shard Shard.
	 * @return       Name.
	 */
	public static String shardName(
			final Integer shard) {
		return String.format("%04d", shard);
	}

	/**
	 * Reads a cached value or loads it.
	 *
	 * @param  <Type> Type.
	 * @param  key    Cache key.
	 * @param  loader Loader (empty result is not cached).
	 * @return        Value.
	 */
	@SuppressWarnings("unchecked")
	private <Type> Optional<Type> cached(
			final String key,
			final Supplier<Optional<Type>> loader) {
		final long now = System.currentTimeMillis();
		Cached entry = this.cache.get(key);
		if ((entry == null) || (entry.expiresAt() < now)) {
			final Optional<Type> loaded = loader.get();
			entry = loaded.map(value -> new Cached(now + this.indexCacheTtl.toMillis(), value)).orElse(null);
			if (entry == null) {
				this.cache.remove(key);
			}
			else {
				this.cache.put(key, entry);
			}
			if ((this.cache.size() % 256) == 0) {
				this.cache.values().removeIf(cachedValue -> cachedValue.expiresAt() < now);
			}
		}
		return Optional.ofNullable(entry).map(cachedValue -> (Type) cachedValue.value());
	}

	/**
	 * Parses JSON.
	 *
	 * @param  <Type> Type.
	 * @param  json   JSON.
	 * @param  type   Type.
	 * @return        Value.
	 */
	private static <Type> Type fromJson(
			final String json,
			final Class<Type> type) {
		try {
			return PartitionTieredRepository.OBJECT_MAPPER.readValue(json, type);
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.cold.format.invalid"), exception);
		}
	}

	/**
	 * Writes JSON.
	 *
	 * @param  value Value.
	 * @return       JSON.
	 */
	private static String toJson(
			final Object value) {
		try {
			return PartitionTieredRepository.OBJECT_MAPPER.writeValueAsString(value);
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.cold.format.invalid"), exception);
		}
	}

	/**
	 * Codec by name.
	 *
	 * @param  name Name.
	 * @return      Codec.
	 */
	public BlockCodec blockCodec(
			final String name) {
		return this.blockCodecs.stream().filter(codec -> codec.name().equals(name)).findFirst()
				.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.block.codec.unknown", new Object[] { name })));
	}

	/**
	 * Manifest key.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @return           Key.
	 */
	public String manifestKey(
			final PartitionTieredTable table,
			final String partition) {
		return this.objectKeyStrategy.keyOf(this.objectPrefix, table, List.of(partition, PartitionTieredRepository.MANIFEST_SEGMENT), this.payloadCodec.extension());
	}

	/**
	 * Index key.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  shard     Shard.
	 * @return           Key.
	 */
	public String indexKey(
			final PartitionTieredTable table,
			final String partition,
			final Integer shard) {
		return this.objectKeyStrategy.keyOf(this.objectPrefix, table, List.of(partition, PartitionTieredRepository.shardName(shard)),
				PartitionTieredRepository.INDEX_EXTENSION);
	}

	/**
	 * Blocks key.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  shard     Shard.
	 * @return           Key.
	 */
	public String blocksKey(
			final PartitionTieredTable table,
			final String partition,
			final Integer shard) {
		return this.objectKeyStrategy.keyOf(this.objectPrefix, table, List.of(partition, PartitionTieredRepository.shardName(shard)),
				PartitionTieredRepository.BLOCKS_EXTENSION);
	}

	/**
	 * Loads an index object directly (no cache).
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  shard     Shard.
	 * @return           Index.
	 */
	public Optional<ShardIndex> loadIndex(
			final PartitionTieredTable table,
			final String partition,
			final Integer shard) {
		return this.coldStorageRepository.load(this.indexKey(table, partition, shard))
				.map(content -> PartitionTieredRepository.fromJson(this.payloadCodec.decode(content), ShardIndex.class));
	}

	/**
	 * Manifest of a partition (cached).
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @return           Manifest.
	 */
	public Optional<PartitionManifest> manifest(
			final PartitionTieredTable table,
			final String partition) {
		final String key = this.manifestKey(table, partition);
		return this.cached(key, () -> this.coldStorageRepository.load(key)
				.map(content -> PartitionTieredRepository.fromJson(this.payloadCodec.decode(content), PartitionManifest.class)).filter(manifest -> {
					if (manifest.formatVersion() > PartitionManifest.FORMAT_VERSION) {
						throw new IntegrationException(new SimpleMessage("coldTiering.cold.format.version", new Object[] { manifest.formatVersion() }));
					}
					return true;
				}));
	}

	/**
	 * Index of a shard (cached).
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  shard     Shard.
	 * @return           Index.
	 */
	public Optional<ShardIndex> index(
			final PartitionTieredTable table,
			final String partition,
			final Integer shard) {
		return this.cached(this.indexKey(table, partition, shard), () -> this.loadIndex(table, partition, shard).filter(index -> {
			if (index.formatVersion() > ShardIndex.FORMAT_VERSION) {
				throw new IntegrationException(new SimpleMessage("coldTiering.cold.format.version", new Object[] { index.formatVersion() }));
			}
			return true;
		}));
	}

	/**
	 * Drops the cached manifest and indexes of a partition.
	 *
	 * @param table     Table.
	 * @param partition Partition.
	 */
	public void forget(
			final PartitionTieredTable table,
			final String partition) {
		final String prefix = this.objectKeyStrategy.prefixOf(this.objectPrefix, table, List.of(partition));
		this.cache.keySet().removeIf(key -> key.startsWith(prefix));
	}

	/**
	 * Drops every cached manifest and index (tests).
	 */
	public void forgetAll() {
		this.cache.clear();
	}

	/**
	 * Writes a shard index.
	 *
	 * @param  table Table.
	 * @param  index Index.
	 * @return       Key.
	 */
	public String writeIndex(
			final PartitionTieredTable table,
			final ShardIndex index) {
		final String key = this.indexKey(table, index.partition(), index.shard());
		this.coldStorageRepository.store(key, this.payloadCodec.encode(PartitionTieredRepository.toJson(index)),
				Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA, this.payloadCodec.contentType(), "table", table.getId(), "partition", index.partition(),
						"export-started-at", index.exportStartedAt()));
		this.cache.remove(key);
		return key;
	}

	/**
	 * Writes a manifest.
	 *
	 * @param  table    Table.
	 * @param  manifest Manifest.
	 * @return          Key.
	 */
	public String writeManifest(
			final PartitionTieredTable table,
			final PartitionManifest manifest) {
		final String key = this.manifestKey(table, manifest.partition());
		this.coldStorageRepository.store(key, this.payloadCodec.encode(PartitionTieredRepository.toJson(manifest)),
				Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA, this.payloadCodec.contentType(), "table", table.getId(), "partition", manifest.partition()));
		this.forget(table, manifest.partition());
		return key;
	}

	/**
	 * Starts a blocks upload.
	 *
	 * @param  table           Table.
	 * @param  partition       Partition.
	 * @param  shard           Shard.
	 * @param  exportStartedAt Export start.
	 * @return                 Upload.
	 */
	public ColdUpload beginBlocks(
			final PartitionTieredTable table,
			final String partition,
			final Integer shard,
			final String exportStartedAt) {
		return this.coldStorageRepository.beginUpload(this.blocksKey(table, partition, shard), Map.of(ColdStorageRepository.CONTENT_TYPE_METADATA,
				"application/octet-stream", "table", table.getId(), "partition", partition, "export-started-at", exportStartedAt));
	}

	/**
	 * Storage minimum part size.
	 *
	 * @return Bytes.
	 */
	public Integer minimumPartSize() {
		return this.coldStorageRepository.minimumPartSize();
	}

	/**
	 * Rows of a shard whose key starts with the prefix, through the given index: one range read
	 * per candidate block. On a corrupt block the cached index is dropped once and the read
	 * retried (a re-export may have moved the offsets).
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  index     Shard index.
	 * @param  prefix    Canonical key segments.
	 * @return           Matching rows, in key order.
	 */
	public List<ColdRow> readMatching(
			final PartitionTieredTable table,
			final String partition,
			final ShardIndex index,
			final List<String> prefix) {
		final List<ColdRow> rows = new ArrayList<>();
		final BlockCodec codec = this.blockCodec(index.blockCodec());
		final String blocksKey = this.blocksKey(table, partition, index.shard());
		for (final BlockEntry entry : index.blocksFor(prefix)) {
			final byte[] frame = this.coldStorageRepository.loadRange(blocksKey, entry.offset(), entry.length())
					.orElseThrow(() -> new IntegrationException(new SimpleMessage("coldTiering.cold.blocks.missing", new Object[] { table.getId() })));
			synchronized (this) {
				this.rangeReads++;
			}
			for (final ColdRow row : ShardedBlockReader.rows(table, codec, entry, frame)) {
				if (BlockEntry.compareKeys(ShardedBlockReader.segmentsOf(table, row.key().values()), prefix) == 0) {
					rows.add(row);
				}
			}
		}
		return rows;
	}

	/**
	 * Rows of a partition whose key starts with the prefix (the shard from the manifest's
	 * boundaries, the blocks from the cached index). Empty when the partition has no manifest.
	 *
	 * @param  table     Table.
	 * @param  partition Partition.
	 * @param  prefix    Canonical key segments (shard key first).
	 * @return           Matching rows.
	 */
	public List<ColdRow> readMatching(
			final PartitionTieredTable table,
			final String partition,
			final List<String> prefix) {
		List<ColdRow> rows = List.of();
		final Optional<PartitionManifest> manifest = this.manifest(table, partition);
		if (manifest.isPresent()) {
			final Integer shard = manifest.get().shardOf(prefix.get(0));
			final Optional<ShardIndex> index = this.index(table, partition, shard);
			if (index.isPresent()) {
				try {
					rows = this.readMatching(table, partition, index.get(), prefix);
				}
				catch (final IntegrationException exception) {
					this.forget(table, partition);
					rows = this.index(table, partition, shard).map(fresh -> this.readMatching(table, partition, fresh, prefix)).orElse(List.of());
				}
			}
		}
		return rows;
	}

	/**
	 * Range reads performed so far (tests).
	 *
	 * @return Count.
	 */
	public synchronized Long getRangeReads() {
		return this.rangeReads;
	}

}
