package org.coldis.library.test.service.tiering;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;
import java.util.List;
import java.util.Map;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.tiering.model.BlockEntry;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.EquiDepthShardCutter;
import org.coldis.library.service.tiering.model.GzipBlockCodec;
import org.coldis.library.service.tiering.model.LeafPartition;
import org.coldis.library.service.tiering.model.PartitionManifest;
import org.coldis.library.service.tiering.model.ShardIndex;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.repository.InMemoryColdStorageRepository;
import org.coldis.library.service.tiering.repository.ShardedBlockReader;
import org.coldis.library.service.tiering.repository.ShardedBlockWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Block format: writer and reader round trip, block search, CRC, key order, shard boundaries,
 * partition bounds.
 */
public class ShardedBlockFormatUnitTest {

	/** Codec. */
	private static final GzipBlockCodec CODEC = new GzipBlockCodec();

	/**
	 * A bound table.
	 *
	 * @return Table.
	 */
	private static TieredTable table() {
		final TieredTable table = new TieredTable("unit", "unit_table", List.of("owner_id", "cached_at")).withVersioned();
		table.bindColumnTypes(Map.of("owner_id", "text", "cached_at", ColumnValues.TIMESTAMP, "updated_at", ColumnValues.TIMESTAMP));
		return table;
	}

	/**
	 * Key segments of an owner and day.
	 *
	 * @param  owner Owner.
	 * @param  day   Day.
	 * @return       Segments.
	 */
	private static List<String> key(
			final String owner,
			final Integer day) {
		return List.of(owner, String.format("2026-01-%02dT00:00:00.000000", day));
	}

	/**
	 * Row JSON of an owner and day.
	 *
	 * @param  owner Owner.
	 * @param  day   Day.
	 * @return       Line.
	 */
	private static String line(
			final String owner,
			final Integer day) {
		return "{\"owner_id\":\"" + owner + "\",\"cached_at\":\"2026-01-" + String.format("%02d", day) + "T00:00:00\",\"data\":{\"marker\":\"" + owner + "-" + day
				+ "\"},\"updated_at\":\"2026-01-" + String.format("%02d", day) + "T00:00:00\"}";
	}

	@Test
	@DisplayName("Writer cuts blocks, reader finds rows by prefix, a gap costs no block, CRC and order are enforced")
	public void testWriterReaderRoundTrip() {
		final InMemoryColdStorageRepository storage = new InMemoryColdStorageRepository();
		final ShardedBlockWriter writer = new ShardedBlockWriter(ShardedBlockFormatUnitTest.CODEC, storage.beginUpload("shard.blocks", Map.of()), 60, 300, 1);
		for (final String owner : List.of("a01", "a02", "a03", "a05", "a06", "a07", "a08")) {
			writer.add(ShardedBlockFormatUnitTest.key(owner, 1), ShardedBlockFormatUnitTest.line(owner, 1));
			if ("a05".equals(owner)) {
				writer.add(ShardedBlockFormatUnitTest.key(owner, 2), ShardedBlockFormatUnitTest.line(owner, 2));
			}
		}
		final List<BlockEntry> blocks = writer.finish();
		Assertions.assertTrue(blocks.size() >= 3, "several blocks: " + blocks.size());
		Assertions.assertEquals(8L, writer.getRows());
		final byte[] object = storage.load("shard.blocks").orElseThrow();
		Assertions.assertEquals(writer.getSizeBytes(), (long) object.length);
		final ShardIndex index = new ShardIndex(ShardIndex.FORMAT_VERSION, "unit", "p", "g1", 0, "now", ShardedBlockFormatUnitTest.CODEC.name(),
				List.of("owner_id", "cached_at"), 8L, writer.getSizeBytes(), blocks);
		// Every row is found through its block(s) by owner prefix.
		int found = 0;
		for (final String owner : List.of("a01", "a02", "a03", "a05", "a06", "a07", "a08")) {
			for (final BlockEntry entry : index.blocksFor(List.of(owner))) {
				final byte[] frame = storage.loadRange("shard.blocks", entry.offset(), entry.length()).orElseThrow();
				for (final ColdRow row : ShardedBlockReader.rows(ShardedBlockFormatUnitTest.table(), ShardedBlockFormatUnitTest.CODEC, entry, frame)) {
					if (row.key().values().get("owner_id").equals(owner)) {
						found++;
						Assertions.assertTrue(row.rowJson().contains("\"" + owner + "-"));
					}
				}
			}
		}
		Assertions.assertEquals(8, found);
		// Gaps and outside keys: no candidate block.
		Assertions.assertTrue(index.blocksFor(List.of("a00")).isEmpty());
		Assertions.assertTrue(index.blocksFor(List.of("a99")).isEmpty());
		final List<BlockEntry> gap = index.blocksFor(List.of("a04"));
		Assertions.assertTrue(gap.size() <= 1);
		// A corrupted frame fails the CRC.
		final BlockEntry first = blocks.get(0);
		final byte[] corrupted = storage.loadRange("shard.blocks", first.offset(), first.length()).orElseThrow();
		corrupted[corrupted.length - 1] ^= 0x55;
		Assertions.assertThrows(IntegrationException.class, () -> ShardedBlockReader.rows(ShardedBlockFormatUnitTest.table(), ShardedBlockFormatUnitTest.CODEC, first, corrupted));
		// Keys out of order fail the shard.
		final ShardedBlockWriter unordered = new ShardedBlockWriter(ShardedBlockFormatUnitTest.CODEC, storage.beginUpload("other.blocks", Map.of()), 120, 300, 1);
		unordered.add(ShardedBlockFormatUnitTest.key("b02", 1), ShardedBlockFormatUnitTest.line("b02", 1));
		Assertions.assertThrows(IntegrationException.class, () -> unordered.add(ShardedBlockFormatUnitTest.key("b01", 1), ShardedBlockFormatUnitTest.line("b01", 1)));
	}

	@Test
	@DisplayName("A prefix spanning several blocks is found in every one of them; a bigger frame is refused")
	public void testPrefixSpanningBlocks() {
		final InMemoryColdStorageRepository storage = new InMemoryColdStorageRepository();
		final ShardedBlockWriter writer = new ShardedBlockWriter(ShardedBlockFormatUnitTest.CODEC, storage.beginUpload("span.blocks", Map.of()), 40, 300, 1);
		writer.add(ShardedBlockFormatUnitTest.key("a01", 1), ShardedBlockFormatUnitTest.line("a01", 1));
		for (int day = 1; day <= 12; day++) {
			writer.add(ShardedBlockFormatUnitTest.key("a02", day), ShardedBlockFormatUnitTest.line("a02", day));
		}
		writer.add(ShardedBlockFormatUnitTest.key("a03", 1), ShardedBlockFormatUnitTest.line("a03", 1));
		final List<BlockEntry> blocks = writer.finish();
		Assertions.assertTrue(blocks.size() >= 4, "blocks: " + blocks.size());
		final ShardIndex index = new ShardIndex(ShardIndex.FORMAT_VERSION, "unit", "p", "g1", 0, "now", ShardedBlockFormatUnitTest.CODEC.name(),
				List.of("owner_id", "cached_at"), 14L, writer.getSizeBytes(), blocks);
		final List<BlockEntry> candidates = index.blocksFor(List.of("a02"));
		Assertions.assertTrue(candidates.size() >= 3, "candidate blocks: " + candidates.size());
		int found = 0;
		for (final BlockEntry entry : candidates) {
			final byte[] frame = storage.loadRange("span.blocks", entry.offset(), entry.length()).orElseThrow();
			for (final ColdRow row : ShardedBlockReader.rows(ShardedBlockFormatUnitTest.table(), ShardedBlockFormatUnitTest.CODEC, entry, frame)) {
				found += "a02".equals(row.key().values().get("owner_id")) ? 1 : 0;
			}
		}
		Assertions.assertEquals(12, found);
		Assertions.assertEquals(1, index.blocksFor(List.of("a01")).size());
		Assertions.assertEquals(1, index.blocksFor(List.of("a03")).size());
		// A frame expanding past the recorded size is refused.
		final BlockEntry first = blocks.get(0);
		final BlockEntry smaller = new BlockEntry(first.first(), first.last(), first.offset(), first.length(), first.uncompressedLength() - 1, first.rows(), first.crc32c());
		final byte[] frame = storage.loadRange("span.blocks", first.offset(), first.length()).orElseThrow();
		Assertions.assertThrows(IntegrationException.class, () -> ShardedBlockReader.rows(ShardedBlockFormatUnitTest.table(), ShardedBlockFormatUnitTest.CODEC, smaller, frame));
	}

	@Test
	@DisplayName("Equi-depth cutter cuts at key changes, prefix boundaries and shardOf, partition bounds parse")
	public void testBoundaries() {
		final EquiDepthShardCutter cutter = new EquiDepthShardCutter(3L);
		for (final String segment : List.of("a", "a", "a", "a", "b", "c", "c", "d", "e", "f", "g")) {
			cutter.offer(segment);
		}
		// "a" repeats past the target, so the cut waits for "b"; then every 3 rows at a key change.
		Assertions.assertEquals(List.of("", "b", "d", "g"), cutter.getBoundaries());
		Assertions.assertEquals(11L, cutter.getRows());
		Assertions.assertEquals(0, PartitionManifest.shardOf(cutter.getBoundaries(), "a"));
		Assertions.assertEquals(2, PartitionManifest.shardOf(cutter.getBoundaries(), "d"));
		Assertions.assertEquals(3, PartitionManifest.shardOf(cutter.getBoundaries(), "zzz"));
		final List<String> prefixes = EquiDepthShardCutter.prefixBoundaries(2);
		Assertions.assertEquals(100, prefixes.size());
		Assertions.assertEquals("", prefixes.get(0));
		Assertions.assertEquals("01", prefixes.get(1));
		Assertions.assertEquals(42, PartitionManifest.shardOf(prefixes, "4299999"));
		final LeafPartition month = LeafPartition.of("t_2026_01", "FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2026-02-01 00:00:00')");
		Assertions.assertFalse(month.isOpenEnded());
		Assertions.assertTrue(month.contains(java.time.LocalDateTime.of(2026, 1, 15, 0, 0)));
		Assertions.assertFalse(month.contains(java.time.LocalDateTime.of(2026, 2, 1, 0, 0)));
		Assertions.assertTrue(LeafPartition.of("t_plus", "FOR VALUES FROM ('2026-04-01 00:00:00') TO (MAXVALUE)").isOpenEnded());
		Assertions.assertTrue(LeafPartition.of("t_default", "DEFAULT").isOpenEnded());
		Assertions.assertEquals(0, BlockEntry.compareKeys(List.of("a"), List.of("a", "x")));
		Assertions.assertTrue(BlockEntry.compareKeys(List.of("a", "b"), List.of("a", "c")) < 0);
		// CRC32C check value (RFC 3720): "123456789" -> 0xE3069283; and the JDK implementation agrees.
		final byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
		final CRC32C jdk = new CRC32C();
		jdk.update(check);
		Assertions.assertEquals("e3069283", ShardedBlockWriter.crc32c(check));
		Assertions.assertEquals(Long.toHexString(jdk.getValue()), ShardedBlockWriter.crc32c(check));
	}

}
