package org.coldis.library.test.service.tiering;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.List;

import org.coldis.library.service.tiering.ColdTieringProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.ShardStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The documented production defaults resolve from empty properties.
 */
public class ColdTieringPropertiesUnitTest {

	@Test
	@DisplayName("Partition tiering defaults: hold P5D, sweep 1024 blocks every second over P3D, export P2D, equi-depth 1000 shards, _legacy excluded")
	public void testDefaults() {
		final TableProperties resolved = new ColdTieringProperties().of(new PartitionTieredTable("unit", "unit_table", List.of("owner_id", "cached_at")));
		Assertions.assertEquals(Duration.ofDays(5), resolved.getPartitionSweep().getHoldAfterLoad());
		Assertions.assertEquals(1024L, resolved.getPartitionSweep().getChunkSize());
		Assertions.assertEquals(Duration.ofSeconds(1), resolved.getPartitionSweep().getDelayBetweenChunks());
		Assertions.assertEquals(Duration.ofDays(3), resolved.getPartitionSweep().getFinishWithin());
		Assertions.assertFalse(resolved.getPartitionSweep().getEnabled());
		Assertions.assertEquals(Duration.ofDays(2), resolved.getExport().getFinishWithin());
		Assertions.assertEquals(Duration.ofDays(2), resolved.getExport().getQuietPeriod());
		Assertions.assertEquals("*_legacy", resolved.getExport().getExcludePartitions());
		Assertions.assertEquals(ShardStrategy.EQUI_DEPTH, resolved.getExport().getShardStrategy());
		Assertions.assertEquals(1000, resolved.getExport().getShards());
		Assertions.assertEquals(1024 * 1024, resolved.getExport().getBlockSize());
		Assertions.assertEquals(50_000, resolved.getExport().getVerifySampleEvery());
		Assertions.assertFalse(resolved.getExport().getEnabled());
		Assertions.assertEquals("cold-tiering/", resolved.getPrefix());
		Assertions.assertEquals(Duration.ofDays(15), resolved.getTtl());
		Assertions.assertEquals(10_000L, resolved.getBackfill().getMaxPending());
		Assertions.assertEquals(10_000L, resolved.getExport().getMaxPending());
	}

	/**
	 * Binds the shipped {@code service.properties} to the properties class.
	 *
	 * @return             Bound properties.
	 * @throws IOException On a missing file.
	 */
	private static ColdTieringProperties shipped() throws IOException {
		final Properties file = new Properties();
		try (InputStream input = ColdTieringPropertiesUnitTest.class.getResourceAsStream("/service.properties")) {
			file.load(input);
		}
		final Map<String, Object> values = new LinkedHashMap<>();
		file.forEach((
				name,
				value) -> values.put(name.toString(), value));
		return new Binder(new MapConfigurationPropertySource(values)).bind(ColdTieringProperties.PREFIX, Bindable.of(ColdTieringProperties.class)).get();
	}

	@Test
	@DisplayName("The shipped service.properties agree with the Java fallbacks (crons, sizes, periods)")
	public void testShippedDefaults() throws IOException {
		final ColdTieringProperties properties = ColdTieringPropertiesUnitTest.shipped();
		final PartitionTieredTable table = new PartitionTieredTable("unit", "unit_table", List.of("owner_id", "cached_at"));
		final TableProperties shipped = properties.of(table);
		final TableProperties fallback = new ColdTieringProperties().of(table);
		Assertions.assertEquals("cold-tiering/", properties.getPrefix());
		Assertions.assertEquals(fallback.getTtl(), shipped.getTtl());
		Assertions.assertEquals(fallback.getTouchThreshold(), shipped.getTouchThreshold());
		Assertions.assertEquals(fallback.getSafetyMargin(), shipped.getSafetyMargin());
		Assertions.assertEquals("0 0 * * * *", shipped.getEviction().getCron());
		Assertions.assertEquals(2000L, shipped.getEviction().getChunkSize());
		Assertions.assertEquals("0 30 * * * *", shipped.getBackfill().getCron());
		Assertions.assertEquals(fallback.getBackfill().getChunkSize(), shipped.getBackfill().getChunkSize());
		Assertions.assertEquals(fallback.getBackfill().getMaxPending(), shipped.getBackfill().getMaxPending());
		Assertions.assertEquals(fallback.getExport().getMaxPending(), shipped.getExport().getMaxPending());
		Assertions.assertEquals("0 15 * * * *", shipped.getExport().getCron());
		Assertions.assertEquals(fallback.getExport().getCron(), shipped.getExport().getCron());
		Assertions.assertEquals(fallback.getExport().getFinishWithin(), shipped.getExport().getFinishWithin());
		Assertions.assertEquals(fallback.getExport().getQuietPeriod(), shipped.getExport().getQuietPeriod());
		Assertions.assertEquals(fallback.getExport().getExcludePartitions(), shipped.getExport().getExcludePartitions());
		Assertions.assertEquals(fallback.getExport().getShardStrategy(), shipped.getExport().getShardStrategy());
		Assertions.assertEquals(fallback.getExport().getShards(), shipped.getExport().getShards());
		Assertions.assertEquals(fallback.getExport().getShardDigits(), shipped.getExport().getShardDigits());
		Assertions.assertEquals(fallback.getExport().getBlockSize(), shipped.getExport().getBlockSize());
		Assertions.assertEquals(fallback.getExport().getPageSize(), shipped.getExport().getPageSize());
		Assertions.assertEquals(fallback.getExport().getKeyPageSize(), shipped.getExport().getKeyPageSize());
		Assertions.assertEquals(fallback.getExport().getPartSize(), shipped.getExport().getPartSize());
		Assertions.assertEquals(fallback.getExport().getVerifySampleEvery(), shipped.getExport().getVerifySampleEvery());
		Assertions.assertEquals("0 0 22 * * *", shipped.getPartitionSweep().getCron());
		Assertions.assertEquals(fallback.getPartitionSweep().getCron(), shipped.getPartitionSweep().getCron());
		Assertions.assertEquals(fallback.getPartitionSweep().getChunkSize(), shipped.getPartitionSweep().getChunkSize());
		Assertions.assertEquals(fallback.getPartitionSweep().getDelayBetweenChunks(), shipped.getPartitionSweep().getDelayBetweenChunks());
		Assertions.assertEquals(fallback.getPartitionSweep().getFinishWithin(), shipped.getPartitionSweep().getFinishWithin());
		Assertions.assertEquals(fallback.getPartitionSweep().getHoldAfterLoad(), shipped.getPartitionSweep().getHoldAfterLoad());
		Assertions.assertFalse(shipped.getEnabled());
		Assertions.assertFalse(shipped.getExport().getEnabled());
		Assertions.assertFalse(shipped.getPartitionSweep().getEnabled());
	}

}
