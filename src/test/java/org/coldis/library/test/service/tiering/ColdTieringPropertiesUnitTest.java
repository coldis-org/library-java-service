package org.coldis.library.test.service.tiering;

import java.time.Duration;
import java.util.List;

import org.coldis.library.service.tiering.ColdTieringProperties;
import org.coldis.library.service.tiering.ColdTieringProperties.TableProperties;
import org.coldis.library.service.tiering.model.PartitionTieredTable;
import org.coldis.library.service.tiering.model.ShardStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
	}

}
