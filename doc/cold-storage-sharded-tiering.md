# Cold storage tiering, step 4: sharded partition archives (Boa Vista full tables)

Status: plan, 2026-11-09, branch `feature/cold-tiering-sharded-plan`. Extends
[`cold-storage-tiering.md`](cold-storage-tiering.md) (step 1, row tiering, built). Nothing here is
implemented; the first consumer is data-gathering's `boa_vista_report_full` and
`boa_vista_cadastro_positivo_report_full`.

## Summary

A third archive unit next to row tiering (one object per row) and window tiering (one file per
window, scan reads): **partition tiering with sharded, indexed objects**. A leaf partition of a
range-partitioned table (one month of a Boa Vista full delivery, ~240 M immutable rows, ~1.1 TB) is
exported **once**, as N shards by contiguous `tax_id` ranges, each shard a sequence of compressed
blocks of rows sorted by primary key plus a small index (first and last key, byte offset, length
per block). A single-record cold read is: manifest → shard (binary search on the recorded shard
boundaries) → block (binary search on the cached shard index) → one S3 **range GET** → decompress
one block → pick the row → `INSERT` it back into its month partition for a 7-day warm window. S3 is
the source of truth; Postgres is a warm cache with a strict last-access TTL.

No per-row archive writes: the archive guard is a **per-partition record** (`cold_tiering_partition_archive`:
table, partition, state, shards, row count, exported/verified/swapped at). Eviction of an exported
partition is a **partition swap**: a new empty table takes the rows touched inside the TTL, is
attached in the old one's place in one transaction, and the old partition is dropped (disk back at
once). Warm rows (rehydrated or copied at the swap) are evicted by the existing row-delete batch,
restricted to swapped partitions. The database keeps its monthly partitions; the sharding exists
only in S3.

Everything that is not the unit itself is shared with step 1: `ColdStorageRepository` (S3, folder,
memory), `ObjectKeyStrategy` through `ColdTable`, `ColumnValues` canonical segments, the registry,
`ColdTieringServiceComponent` as the read entry point, `ColdTieringBatchServiceComponent` and its
crons, the queued batch pattern (a batch enqueues, listeners do the work), the properties tree, the
`TieredRowKey`, `ColdRow`, `TieredHit` types, `HotTierRepository.rehydrate` and `touch`.

## Goals / non-goals

Goals:
- Single-record retrieval from an archived month in ~100–150 ms, for any CPF, any month, from the
  same component methods the online tables use (`findByIdTiered`, `findNewestTiered`), plus the
  three shapes the full tables need and the online ones do not: first snapshot in an interval,
  timeline of a tax id, batch by `(tax_id IN …, cached_at = …)`.
- A month leaves Postgres as one DDL operation after its export is **verified** (counts per shard,
  sample reads), never before, and never while a row of it is inside its TTL.
- Rehydrated rows live in the month's own partition and expire by last access (7 days to start,
  3 later), through the batch that already exists.
- Export throughput bounded by the database's read rate, not by S3 request latency: ~2,000 PUTs a
  month per table instead of 240 M.
- Formats carry a version so blocks, indexes and manifests can change without re-exporting what is
  already in S3.
- Backward compatible: the online tables (row tiering) keep every class, property and behaviour.

Non-goals:
- Repartitioning the hot tables (hash sub-partitions, a default partition); the swap keeps the
  month layout the consumer documents in `doc/boa-vista-base-full.md`.
- Analytics over the shards (Athena, DuckDB). The block format is line-delimited JSON; a Parquet
  export of the same months stays a consumer concern.
- Creating partitions ahead (`_plus` handling), moving the `_legacy` rows, a Bloom filter per
  shard (a later improvement, see *Open questions*).
- The read gate, the delivery pipeline and the validator's rules: the consumer keeps them, and only
  their repository calls change.

## The consumer today (what the design must fit)

`supersim-service-data-gathering-pom`, `origin/master`:

| Piece | Where | Relevant facts |
|---|---|---|
| Entities | `model/person/boavista/BoaVistaReportFull` (`@IdClass BoaVistaSnapshotKey`: `taxId`, `version`, `cachedAt`), `BoaVistaCadastroPositivoReportFull` (`taxId`, `cachedAt`; plus `origin`) | `AbstractTimestampableExpirableEntity`: `created_at`, `updated_at` (`timestamptz`), `expired`, `expired_at`; `data` JSONB (~19 KiB of JSON per row, ~3 KiB after TOAST); `cached_at` is `timestamp` without zone, always a day start (`dtProcessamento`) |
| Tables | Flyway `V1__baseline_schema.sql`, `scripts/migration/create_boa_vista_full_partitioned_tables.sql` | `PARTITION BY RANGE (cached_at)`; 46 leaf partitions each: `_legacy` (MINVALUE → 2025-01-01), `_2025_01` … `_2028_08`, `_plus` (2028-09-01 → MAXVALUE, must stay empty); **the primary key is the only index**, `(tax_id, version, cached_at)` and `(tax_id, cached_at)`: it leads with the shard key, so a per-shard range scan is an index range scan, in key order; no `ANALYZE` has ever completed (`n_live_tup = 0`) |
| Sizes (prod, 2026-11-09) | | `boa_vista_cadastro_positivo_report_full` 3.5 TB (2026-08/09/10 ≈ 1.1 TB each, 2025 months 1.4–21 GB), `boa_vista_report_full` 1.4 TB, same shape. ~240 M rows per monthly delivery |
| Loader | `component/…/boavista/full/BoaVistaFullApplyExecutor` (6 workers, pages of tax ids, `insertAllIfAbsent` + `lockAllByTaxIds` + update of `data`), `BoaVistaFullCoordinator.recordApplyWorkerFinished` → `ApplyDone` when the last worker finishes → `alertDeliveryFinished(cohortId, month, applied)`; progress in `key_value` `bvsfull-apply-progress-<id>` (`BoaVistaFullApplyProgress`: `cachedAt`, `shards`, `totalRows` from the parquet footers, `finishedAt`, `verdict`) | The one place that knows "delivery complete". Rows are immutable once the delivery is loaded; a reprocess rewrites the same keys |
| Reads | `BoaVistaFullReadServiceComponent` (`readReportSnapshot`, `readReportTimeline`, `readCadastroPositivoSnapshot`), behind the read gate (`BoaVistaFullReadGateServiceComponent`: a ceiling per tax id bucket, a fallback interval) | Query shapes: `findFirstByTaxIdOrderByCachedAtDesc`, `findFirstByTaxIdAndCachedAtLessThanEqualOrderByCachedAtDesc`, `findFirstByTaxIdAndCachedAtGreaterThanAndCachedAtLessThanEqualOrderByCachedAtAsc` (fallback), `findByTaxIdOrderByCachedAtDesc` and `…LessThanEqual…Desc` (timeline), `findAllByTaxIdInAndCachedAt` (the validator's 500 k sample, `BoaVistaFullValidator`) |
| Online tiering (step 2, done) | `BoaVistaOnlineTieringConfiguration` (two `TieredTable` beans, `coldTieringS3Client`), `BoaVistaOnlineReportTieredRepository`, Flyway `V3__boa_vista_online_cold_tiering.sql` (+ `.sql.conf`), `application.properties` `org.coldis.library.service.tiering.*`, prefix `boa-vista/online/` | Shows the consumer-side shape to follow |

## Design

### Shape

```
consumer                                       library (org.coldis.library.service.tiering)
────────                                       ─────────────────────────────────────────────
PartitionTieredTable bean per table ─────────► TieredTableRegistry (+ partitioned-table checks)
loader, when a delivery is loaded:
  tiering.partitionLoaded(tableId, moment) ──► PartitionTieringServiceComponent: upsert the partition
                                                 record (state LOADED, loaded_at)
                                               ColdTieringBatchServiceComponent: EXPORT batch (cron per
                                                 table): GET = next LOADED/STALE partition past its quiet
                                                 period, EXECUTE = enqueue its shards to cold-tiering/export
                                               export listener (N per instance): exportShard → .blocks,
                                                 .index; last shard of a partition → manifest, count and
                                                 sample verification → VERIFIED
tiering.findByIdTiered / findNewestTiered /    ColdTieringServiceComponent dispatches on the table's
  findFirstTiered / findTimelineTiered /         archive unit: ROW → TieredRepository (as today),
  findAllByKeysTiered (new)                      PARTITION → PartitionTieredRepository (manifest, index,
                                                 range GET, block decode) then HotTierRepository.rehydrate
                                               ColdTieringBatchServiceComponent: PARTITION_EVICTION batch
                                                 (VERIFIED and loaded_at + ttl + margin < now → swap),
                                                 EVICTION batch (row delete, swapped partitions only)
                                               ── repository package ──
                                               PartitionHotTierRepository (JDBC: leaf partitions and
                                                 bounds from the catalog, keyset page per shard, counts,
                                                 swap DDL, warm-row delete per partition)
                                               PartitionArchiveRepository (JDBC on
                                                 cold_tiering_partition_archive)
                                               ColdStorageRepository + range GET and multipart upload
```

Packages and direction as in step 1: `tiering` (policy) → `tiering.repository` (access) →
`tiering.model` (definitions, formats, SPIs). The new classes carry the `Partition` prefix (the unit)
and `Sharded` only where the S3 layout is meant (`ShardedBlockWriter`, `ShardedBlockReader`).

### Public API additions

Model (`tiering.model`):

| Type | Role |
|---|---|
| `ArchiveUnit { ROW, PARTITION }` | On `TieredTable` (`getArchiveUnit()`, `ROW` for every existing definition). The component and the batches dispatch on it |
| `PartitionTieredTable extends TieredTable` | `new PartitionTieredTable(id, tableName, keyColumns)` `.withShardKeyColumn("tax_id")` (must be the first key column) `.withPartitionColumn("cached_at")` (must be a key column, and the table's range partition key); inherits `withWatermarkColumn`, `withLastAccessedColumn`, `withArchivedAtColumn`, `withEntity`, `withTtlClock` (only `LAST_ACCESS`), `withRehydration` (only `INSERT`); `isVersioned()` is forced true (the partition column is the version) |
| `ShardBoundaries` | Immutable list of lower bounds (canonical segments of the shard key), `shardOf(segment)` by binary search, `rangeOf(shard)`; built by a `ShardBoundaryStrategy` and **recorded in the manifest**, so readers never recompute them |
| `ShardBoundaryStrategy` SPI | `boundariesFor(table, partition, sampler)`; built-ins `PrefixShardBoundaryStrategy(digits)` (default, 3 → 1,000 shards `000`…`999`, keys shorter than the prefix go to the first shard) and `EquiDepthShardBoundaryStrategy(shards)` (lower bounds from `percentile_disc` over a sample; for a skewed key). Selected by `table.<id>.export.shard-strategy` |
| `BlockCodec` SPI | `compress(byte[])`, `decompress(byte[], expectedLength)`, `name()`; built-ins `gzip` (JDK) and `zstd` (`com.github.luben:zstd-jni`, `<optional>`, `@ConditionalOnClass`); the index names the codec, so a partition exported with one is readable after the default changes |
| `PartitionManifest`, `ShardIndex`, `BlockEntry` | Records of the formats below, with `formatVersion`; Jackson, through the existing `GzipJsonPayloadCodec` for the bytes |
| `PartitionArchive` | The DB record: `tableId`, `partitionName`, `rangeFrom`, `rangeTo`, `state`, timestamps, `shardCount`, `rowCount`, `sizeBytes`, `formatVersion`, `manifestKey` |
| `PartitionArchiveState { LOADED, EXPORTING, EXPORTED, VERIFIED, STALE, SWAPPED }` | The guard. Only `VERIFIED` may be swapped; only `VERIFIED`, `SWAPPED` (and `STALE`, see *Reprocess*) may be read cold; a row may be deleted by the row-delete batch only in a `SWAPPED` partition |
| `TieredRowKey`, `ColdRow`, `TieredHit` | Unchanged, reused |

Repository (`tiering.repository`):

| Type | Role |
|---|---|
| `ColdStorageRepository` (extended, **default methods throwing `UnsupportedOperationException`** so a consumer's own implementation keeps compiling) | `Optional<byte[]> loadRange(key, offset, length)`; `Optional<ColdObject> head(key)` (size, metadata); `ColdUpload beginUpload(key, metadata)` with `part(byte[])`, `complete() → ColdObject`, `abort()`. S3: `GetObjectRequest.range("bytes=a-b")`, `HeadObject`, multipart upload (parts ≥ 5 MB, `ChecksumAlgorithm.CRC32C` on every part); folder: `RandomAccessFile`, parts appended to the temporary file, atomic move on complete; memory: array slices |
| `PartitionHotTierRepository` | `leafPartitions(table)` from `pg_inherits` + `pg_get_expr(relpartbound)` (name validated by `TieredTable.IDENTIFIER` and by membership in the catalog, never by string building); `nextPage(partition, shardRange, lastKey, pageSize)` (the keyset query below); `countInRange(partition, shardRange)`; `countWrittenAfter(partition, moment)`; `createWarmTable`, `copyWarmRows`, `swap`, `dropDetached`, `renameWarm` (the swap steps); `evictExpired(partition, before, chunk)` (per partition, `FROM ONLY`) |
| `PartitionArchiveRepository` | CRUD on `cold_tiering_partition_archive` with `SELECT … FOR UPDATE` on state transitions |
| `PartitionTieredRepository` | `manifest(table, partition)` (cached), `shardIndex(table, partition, shard)` (cached), `readRow(table, partition, key)`, `readRows(table, partition, keys)` (grouped by shard and block, one range GET per block, adjacent blocks coalesced into one GET), `readNewest(table, partition, shardKeyValue, asOf)`; composes `ColdStorageRepository`, `BlockCodec`, `ObjectKeyStrategy` |
| `ShardedBlockWriter`, `ShardedBlockReader` | Format code only: lines in, frames out, index entries; frames in, lines out; CRC32C check |

Policy (`tiering`):

| Type | Role |
|---|---|
| `PartitionTieringServiceComponent` | `partitionLoaded(tableId, momentInPartition)` (the consumer's signal); `exportShard(ShardExportRequest)` (JMS listener on `cold-tiering/export`, `export.concurrency`); `finishPartition(table, partition)` (manifest, verification, state); `swap(table, partition)`; `markStale(tableId, momentInPartition)` |
| `ColdTieringServiceComponent` (extended) | `findByIdTiered` and `findNewestTiered` dispatch on `ArchiveUnit`; new `findFirstTiered(Entity.class, keyPrefix, after, upTo)`, `findTimelineTiered(Entity.class, keyPrefix, asOf) → List<TieredHit>`, `findAllByKeysTiered(Entity.class, Collection<id>) → List<TieredHit>`; `afterWrite` on a `PARTITION` table only touches (no upload request); `delete(key)` on a `PARTITION` table deletes the hot row only and throws if the partition is archived (a cold row cannot be deleted alone from a shard; business deletes do not exist on these tables) |
| `ColdTieringBatchServiceComponent` (extended) | `TieringBatch.EXPORT` and `TieringBatch.PARTITION_EVICTION`; the row `EVICTION` on a `PARTITION` table iterates `SWAPPED` partitions; `BACKFILL` is rejected at registration for `PARTITION` tables (the export is the backfill) |
| `ColdTieringSchema` (extended) | `partitionArchiveStatements()` (the record table), `lifecycleColumnsStatement(table)` (no eviction index for `PARTITION` tables, see *Schema*) |
| `TieredTableRegistry` (extended) | For a `PARTITION` table: the table is range-partitioned on the partition column (`pg_partitioned_table`), the shard key is the first key column and the first column of the primary key index of the parent, the partition column is a key column; `LAST_ACCESS` + `INSERT` only; `backfill.enabled=false` |

Properties, under `org.coldis.library.service.tiering.`:

| Property | Default | Meaning |
|---|---|---|
| `export.concurrency` | `1` | Listeners per instance on `cold-tiering/export`; one shard in flight per listener, so memory ≈ listeners × (`part-size` + one block + one page) |
| `block-codec` | `gzip` | `gzip` or `zstd` (needs zstd-jni on the consumer's classpath) for new exports; reads follow the index |
| `index-cache.ttl` / `index-cache.cache-manager` | `PT5M` / — | Process-local TTL cache of manifests and shard indexes (bounded by the TTL, not by a size: an entry is ~40 KB, so 5 minutes at 10 cold reads/s bounds it near 120 MB worst case, far less with repeated shards); when a `CacheManager` bean name is given, a shared second level (Redis in the consumers) with that manager's TTL |
| `table.<id>.export.{enabled,cron,finish-within}` | `false`, `0 15 * * * *`, `P2D` | The export batch (enqueues shards) |
| `table.<id>.export.quiet-period` | `P2D` | Heuristic eligibility: a partition with no `loaded_at` signal is exported when its newest `updated_at` is older than this and its range end is in the past (backfill of existing months, `_legacy`) |
| `table.<id>.export.shard-strategy` / `shard-digits` / `shards` | `prefix` / `3` / `1000` | Boundaries for new exports |
| `table.<id>.export.block-size` / `page-size` / `part-size` | `1MB` (compressed, 256 KB–4 MB) / `2000` rows / `16MB` | Block cut when the compressed frame reaches the size; rows per keyset page; multipart part |
| `table.<id>.export.verify-sample-every` | `50000` | One row in every N written is read back through the cold path after the shard completes |
| `table.<id>.partition-eviction.{enabled,cron}` | `false`, `0 0 3 * * *` | The swap batch; after `ttl + safety-margin` from `loaded_at` (or from `exported_at` when the partition had no signal) |
| `table.<id>.partition-eviction.lock-timeout` / `lock-retries` / `drop-delay` | `PT5S` / `10` / `PT0S` | `SET LOCAL lock_timeout` for the swap transaction, retries with backoff, and how long the detached old partition is kept before `DROP` (0 = at once, the developer's choice; `P1D` keeps a rollback window at the cost of the disk) |
| `table.<id>.ttl` / `touch-threshold` / `safety-margin` | inherited (`P15D` / `P1D` / `P2D`) | For the full tables the consumer sets `ttl=P7D`, `safety-margin=P1D` |

### Cold format

Object keys through `ObjectKeyStrategy` (`PathObjectKeyStrategy` shown), segments `[partitionName,
file]`, so a custom layout (hashed segments, another prefix shape) applies to both units:

```
<prefix><tableId>/<partition>/manifest.json.gz                    PartitionManifest
<prefix><tableId>/<partition>/<shard %04d>.index.json.gz          ShardIndex
<prefix><tableId>/<partition>/<shard %04d>.blocks                 frames, back to back
e.g. boa-vista/full/boa-vista-report-full/boa_vista_report_full_2026_08/0042.blocks
```

**`.blocks`**: a concatenation of independently decompressible frames. A frame is the `BlockCodec`
compression of `N` lines, each line the `CAST(row_to_json(part) AS text)` of one row, `\n`
separated, UTF-8, in primary-key order. No header inside the object; all structure is in the
index, so a block is readable with one range GET and one `decompress`. Object metadata:
`format-version`, `table`, `partition`, `shard`, `block-codec`, `rows`, `content-type
application/octet-stream`.

**`.index.json.gz`** (`ShardIndex`, gzip JSON through the existing codec):

```json
{ "formatVersion": 1, "tableId": "boa-vista-report-full", "partition": "boa_vista_report_full_2026_08",
  "shard": 42, "blockCodec": "gzip",
  "keyColumns": ["tax_id", "version", "cached_at"], "rows": 240113, "sizeBytes": 612345678,
  "blocks": [
    { "first": ["04200000012", "6B%2C6H", "2026-08-05T00:00:00.000000"],
      "last":  ["04200000987", "6B%2C6H", "2026-08-05T00:00:00.000000"],
      "offset": 0, "length": 1048231, "rows": 412, "crc32c": "a1b2c3d4" },
    { "first": ["04200000990", …], "last": [...], "offset": 1048231, "length": 1047990, "rows": 409, "crc32c": "…" }
  ] }
```

Keys are the canonical segments of `ColumnValues.toSegment` (fixed width, URL-encoded text), so the
string order is the key order and a lookup is a `String` binary search. `first`/`last` per block let
a miss between two blocks cost no GET, and let the reader fetch the next block when a tax id's rows
run across a boundary (`last` of block *i* has the same shard-key segment as the looked-up key).
At 1 MB blocks a shard of 240 k rows has ~600 blocks, an index ~35–40 KB compressed; 1,000 shards
≈ 40 MB of index per partition and table, loaded one shard at a time on demand, never all at once.
`crc32c` is checked after every range GET (in-transit integrity of a range GET is not covered by the
SDK's object checksum).

**`manifest.json.gz`** (`PartitionManifest`): `formatVersion`, `tableId`, `partition`, `rangeFrom`,
`rangeTo` (the bounds as `pg_get_expr` prints them), `keyColumns`, `shardStrategy` (`{type, digits}`
or `{type, shards}`), `boundaries` (the lower bound of every shard: the authority for `shardOf`),
`shards[]` (`shard`, `rows`, `blocks`, `sizeBytes`, `indexKey`, `blocksKey`, `etag`), `rows`,
`exportStartedAt`, `exportedAt`, `verification` (`sampledRows`, `verifiedAt`). It is written
**last**, after every shard; a reader that finds no manifest treats the partition as not archived.
The DB record copies the totals and the state; the manifest holds the layout.

**Versioning**: `formatVersion` in the three objects and in the metadata. The reader accepts a set
of versions (`1` at first); a new block or index layout bumps it, new exports write it, old
partitions stay readable. A change of shard boundaries or block size needs no version bump: the
manifest and the index describe themselves.

### Shard boundaries

Default `prefix`, 3 digits → 1,000 shards of ~240 k rows, ~0.6 GB each (compressed) at the full
tables' row size. Verify the distribution once before the first export, on one big partition:

```sql
SELECT substr(tax_id, 1, 3) AS shard, count(*) FROM ONLY boa_vista_report_full_2026_10 GROUP BY 1 ORDER BY 2 DESC;
```

(An index-only range count per prefix is also what the export's verification runs, so the cost is
known: minutes.) CPF numbers are allocated sequentially with a regional ninth digit, so a 3-digit
prefix is expected near uniform; if the largest shard exceeds ~3× the median, switch the table to
`equi-depth` (lower bounds from `percentile_disc(k/N) WITHIN GROUP (ORDER BY tax_id)` over a
`TABLESAMPLE SYSTEM (1)` of the partition). Either way the boundaries go into the manifest and the
reader does not care which strategy produced them. Hash sharding is not offered: it breaks the
contiguity that makes a batch read group neighbouring keys into one block.

### Export

Eligibility (GET of the `EXPORT` batch, one partition at a time per table): leaf partitions of the
table from the catalog, excluding `_plus`-like open-ended ranges (`MAXVALUE`), whose record is
`LOADED` or `STALE`, or that have no record and pass the heuristic (`quiet-period`). The consumer's
signal (`partitionLoaded`) is preferred and exact; the heuristic exists for the months already in
the tables and for `_legacy`. Nothing is exported while the partition is still being written: a
`LOADED` record carries the loader's `loaded_at`; the heuristic requires `max(updated_at) < now -
quiet-period` (a cheap index-free check on the partition is `SELECT 1 … WHERE updated_at > :since
LIMIT 1`, a sequential scan with an early exit; acceptable once per hour per candidate partition).

Row count of a delivery, to size and to verify: exact, `SELECT count(*) FROM ONLY <partition>`
(minutes to tens of minutes on 1.1 TB without a visibility map; run once, outside a request);
from the loader, `BoaVistaFullApplyProgress.totalRows` (the parquet footers) and `applied`; the
sum of the per-shard index range counts the verification computes anyway (exact, incremental, and
the figure the manifest records). `pg_stat_user_tables.n_live_tup` is useless here until `ANALYZE`
runs.

EXECUTE of the batch: set the record `EXPORTING` (`export_started_at`, boundaries computed and
stored in a provisional manifest key), then enqueue one `ShardExportRequest(tableId, partition,
shard, from, to, exportStartedAt)` per shard to `cold-tiering/export` (the batch cursor is the shard
number; chunk and delay as the backfill's). The listeners do the work, N per instance, so
parallelism is `export.concurrency × instances`, independent of the batch.

`exportShard`, one shard, no long transaction:

1. Skip if the shard's index object exists with metadata `export-started-at` equal to the
   request's (a redelivered or re-enqueued shard after a restart): idempotent.
2. Keyset pages, each its own short read-only transaction (no cursor held across S3 calls, no
   `long-timeout` risk):
   ```sql
   SELECT tax_id, version, cached_at, CAST(row_to_json(part) AS text)
   FROM ONLY <partition> part
   WHERE tax_id >= :from AND tax_id < :to                         -- :to absent on the last shard
     AND (tax_id, version, cached_at) > (:lastTaxId, :lastVersion, :lastCachedAt)
   ORDER BY tax_id, version, cached_at
   LIMIT :pageSize
   ```
   The row-value comparison and the `ORDER BY` match the primary key, so each page is one index
   range scan with heap fetches (TOAST included) and no sort. **Collation**: the varchar index
   orders by the database collation; the index and the manifest compare as Java strings (code
   point order). For 11-digit CPFs the two agree; the writer still asserts that every key is
   strictly greater than the previous one in code-point order and fails the shard otherwise. If a
   table's PK did not lead with the shard key, the registry fails the boot: the fix is an index
   `(shard key, remaining key)` built `CONCURRENTLY` on every partition, not a per-shard sort of
   240 k × 20 KiB rows.
3. Lines are appended to the current block buffer and compressed when the compressed size reaches
   `block-size`; frames are appended to a part buffer; a part is uploaded when it reaches
   `part-size` (multipart). Memory per listener: a page (2,000 × ~20 KiB ≈ 40 MB uncompressed:
   lower `page-size` if heap is tight), one block, one part. The index grows in memory (~600
   entries).
4. `complete()` the blocks object, write the index with `export-started-at` in its metadata,
   update the record's shard progress (`shards_done`, `rows`, `size_bytes`; one `UPDATE … FOR
   UPDATE`, no row lock on the hot table at any point).
5. Verification of the shard, in the same listener: `countInRange(partition, from, to)` (index
   range count) must equal the rows written; every `verify-sample-every`-th key seen (kept in a
   small list) is read back through `PartitionTieredRepository.readRow` and compared byte for byte
   with the line written. A mismatch fails the shard (exception → retry → DLQ) and the partition
   never reaches `VERIFIED`.
6. The listener that completes the last shard writes the manifest, re-checks
   `countWrittenAfter(partition, export_started_at) = 0` (nothing changed under the export) and
   sets `VERIFIED` (`exported_at`, `verified_at`, totals). If rows changed: `STALE` (see
   *Reprocess*).

Resumability: a restart loses the in-flight shards only; their messages are redelivered
(`abort()` of a dangling multipart is done by a bucket lifecycle rule for incomplete uploads, 1 day,
plus a best-effort abort on failure). A batch run restarted from scratch re-enqueues every shard;
step 1 skips the ones done. Crash between the blocks `complete()` and the index write → the shard is
redone (the blocks key is overwritten). Two listeners on the same shard (a duplicate message)
write the same bytes to the same keys; the record's progress is updated under `FOR UPDATE`, so the
shard counts once.

Throughput, to measure on one shard first and extrapolate (the plan's estimate, prod figures):
the database read is the bound. Per month and table ≈ 240 M rows × ~19 KiB JSON ≈ 4.3 TiB of text
out of Postgres (TOAST decompression plus `row_to_json` serialisation, CPU on the database). At
~300 MB/s aggregate from 8 listeners that is ~4 h; gzip at ~50 MB/s per core would add ~3 h of
service CPU unless spread across 8+ cores, zstd (~400 MB/s per core) makes compression
negligible, which is why the codec is an SPI. Expect 4–8 h per big month per table with
`export.concurrency=4` on 2 instances, and plan the first backfill month at night.
S3 requests: 1,000 shards × (~40 parts + complete + index) + manifest ≈ 42,000 PUT-class requests
≈ US$ 0.21 per month and table. Storage: JSON compresses 6–8× (gzip) → ~0.5–0.7 TB per big
month and table (vs 1.1 TB on EBS); the existing 4.9 TB of both tables ≈ 2–2.5 TB in S3 ≈ US$
50–60 a month at Standard, a tenth of that at Glacier Instant Retrieval (range GET works there,
retrievals cost US$ 0.03/GB: an open question for months older than, say, 6).

### Read path

Entry points on `ColdTieringServiceComponent`, dispatching on `ArchiveUnit.PARTITION`:

| Method | Hot | Cold | Result |
|---|---|---|---|
| `findByIdTiered(Entity, id)` | JPQL by key (the parent, pruned to one partition by `cached_at`) | partition of `cached_at` → record readable? → `readRow` | rehydrate, hot read again, touch |
| `findNewestTiered(Entity, {tax_id[, version]}, asOf)` | `findNewestHot` (one probe per partition index, 46) | readable partitions with `rangeFrom <= asOf`, **newest first, only those whose range starts after the hot candidate's `cached_at`**; stop at the first hit | the newer of hot and cold; cold → rehydrate |
| `findFirstTiered(Entity, {tax_id}, after, upTo)` (new) | `findFirstHot` in the interval | readable partitions intersecting `(after, upTo]`, oldest first, only those before the hot candidate | same |
| `findTimelineTiered(Entity, {tax_id}, asOf)` (new) | all hot rows of the prefix | every readable partition `<= asOf` not already represented by a hot row of that partition, probed in parallel (bounded executor, `index-cache` warm) | hot ∪ cold, newest first; cold rows rehydrated |
| `findAllByKeysTiered(Entity, ids)` (new) | one JPQL `IN` per partition | the missing keys grouped by partition → shard → block; adjacent blocks coalesced into one range GET; parallel with a bounded executor | hits in input order; cold rows rehydrated in one batched `INSERT` per partition |

"Readable" = record state `VERIFIED`, `SWAPPED` or `STALE` with a manifest. A partition with
another state or no record is **hot by definition** (every row of it is still in Postgres), so a
hot miss on such a partition is a true miss and costs no GET: the design never has a partition
that is neither hot nor archived, because the swap is gated on `VERIFIED`. The guard is explicit:
`swap` refuses any other state, and a monitoring query (below) flags a partition whose live row
count is below its manifest count while its state is not `SWAPPED`.

One cold read: `manifest` (cache hit after the first read of the partition on the instance) →
`boundaries.shardOf(segment)` → `shardIndex` (cache; else one GET of ~40 KB) → binary search on
`first` → `last` check (a gap is a miss without a GET) → one range GET of ≤ `block-size` bytes →
CRC32C → decompress → scan the lines for the key (Jackson streaming parser reading the key fields
only; a line is ~19 KiB, a block ~400 lines) → `ColdRow`. Latency budget, same region: S3 range GET
TTFB 30–70 ms + 1 MB transfer ~10 ms + decompress 5–10 ms (gzip) + `json_populate_record` INSERT
~5 ms + the touch request: **~100–150 ms p50, ~250 ms p99** for a key hit, ~0 ms extra for a key
miss in a gap, ~70 ms for a miss inside a block. A cold index fetch adds one GET (~40 ms). The hot
hit path is unchanged (one JPQL, one touch when stale).

The hot entry is always tried first, even for `findByIdTiered`, so a read during an export, or of
a `STALE` partition, serves the database's current row and never an older cold copy.

Rehydration: `HotTierRepository.rehydrate` as built: `INSERT INTO <parent> SELECT * FROM
json_populate_record(NULL::<parent>, :json) ON CONFLICT DO NOTHING`, then `archived_at =
updated_at`, `last_accessed_at = now`. The `INSERT` goes through the parent and Postgres routes it
by `cached_at` into the month partition, which **always exists**: the swap replaces the month's
table, it never leaves the range unattached (one transaction, below), and `_plus` catches a range
beyond the created months. If routing fails anyway (a partition dropped by hand), the error is
logged and the `ColdRow` is still served in the `TieredHit` (the read must not fail because the
cache is unavailable; the consumer maps the cold row like the online `NONE` case). Rehydrated rows
land as archived (`archived_at = updated_at`), so the row-delete batch can evict them after the TTL.

Touch: unchanged (`cold-tiering/touch`, `GREATEST`, `touch-threshold`).

Races: *read during export*: the partition is fully hot, the read hits hot; the export's pages are
each consistent and the rows are immutable, so a concurrent read changes nothing. *Read during a
swap*: the read waits on the parent's lock for the milliseconds of the DDL, then sees the warm
partition; a rehydration `INSERT` that started before the swap either committed into the old
partition before the lock (then it is caught up into the warm table under the lock) or runs after
and routes into the warm one. *Two instances rehydrate the same key*: `ON CONFLICT DO NOTHING`.
*Index cache stale after a re-export*: a re-export changes offsets; the CRC32C check fails on a
stale index → the reader drops the cached index and manifest for the partition and retries once.

### Eviction

**Partition swap** (`PARTITION_EVICTION` batch, GET = the next `VERIFIED` partition with
`loaded_at (or exported_at) + ttl + safety-margin < now`, EXECUTE = `swap`), per partition
`P` of parent `T`, warm table `P_warm`, bounds `[from, to)` from the catalog, `cutoff = now - ttl`:

```
Tx1 (REQUIRED, long-timeout)
  CREATE TABLE P_warm (LIKE P INCLUDING ALL);                 -- columns, NOT NULL, the PK index
  ALTER TABLE P_warm ADD CONSTRAINT P_warm_range CHECK (cached_at >= from AND cached_at < to);
                                                             -- so ATTACH validates by constraint, no scan
  INSERT INTO P_warm SELECT * FROM ONLY P
    WHERE COALESCE(last_accessed_at, updated_at) >= cutoff    -- warm: read or written inside the TTL
       OR updated_at > exported_at;                           -- never archived (should be none on VERIFIED)
  UPDATE P_warm SET archived_at = updated_at WHERE updated_at <= exported_at AND archived_at IS NULL;
  copy_started_at := the transaction's start
Tx2 (REQUIRES_NEW, SET LOCAL lock_timeout = lock-timeout; retried lock-retries times with backoff)
  ALTER TABLE T DETACH PARTITION P;                           -- ACCESS EXCLUSIVE on T, milliseconds
  ALTER TABLE T ATTACH PARTITION P_warm FOR VALUES FROM (from) TO (to);
  INSERT INTO P_warm SELECT * FROM P                          -- catch-up: rows that arrived in P
    WHERE updated_at >= copy_started_at OR last_accessed_at >= copy_started_at
  ON CONFLICT (key) DO UPDATE SET last_accessed_at = GREATEST(P_warm.last_accessed_at, EXCLUDED.last_accessed_at),
                                  archived_at = EXCLUDED.archived_at, data = EXCLUDED.data, updated_at = EXCLUDED.updated_at;
  record := SWAPPED (swapped_at, warm_rows, detached_name)
Tx3 (after drop-delay; REQUIRES_NEW)
  sanity: count(*) FROM P  <=  manifest.rows + warm catch-up (every row of P is in S3 or in P_warm);
  DROP TABLE P;
  ALTER TABLE P_warm RENAME TO P;  ALTER TABLE P RENAME CONSTRAINT P_warm_range TO P_range;
```

Why this shape: the detach and the attach are one transaction, so the month's range is never
missing from the parent (a rehydration or a loader `INSERT` for that month never fails), and the
catch-up runs under the same lock, so nothing written between the copy and the swap is lost. The
expensive part (the copy of the warm rows) runs before the lock; the locked part touches rows
written in the last minutes only. `DETACH … CONCURRENTLY` is not used: it needs two transactions
and cannot be paired with the attach atomically. The lock is `ACCESS EXCLUSIVE` on the parent: it
waits for every running statement on the parent (the loader's page transactions are short; a long
analytical query or a replica's long read delays it), hence `lock_timeout` with retries and a
3 a.m. cron; a `lock_timeout` on a replica-side conflict shows up as the primary waiting, bounded
the same way. Physical replicas replay the DDL; `max_standby_streaming_delay` applies to long
replica queries on the parent during the swap. There is no logical replication of these tables.
Indexes: `LIKE … INCLUDING ALL` creates the PK index on `P_warm`; on `ATTACH`, Postgres attaches
it as a partition of the parent's partitioned PK index because its definition matches. No foreign
keys exist on these tables. Disk: the `DROP` returns heap, index and TOAST at once (the
developer's requirement; `drop-delay` > 0 trades that for a rollback window). Flyway: the swap is
runtime DDL by the service's role (which must own the tables; verify in prod), not a migration;
`V1`'s `CREATE TABLE IF NOT EXISTS <partition>` stays valid because the names are preserved by the
final rename. Naming: after the swap, `P` is the warm table and may be swapped again if it grows,
though in practice its rows leave by the row-delete batch.

Failure handling: Tx1 fails → `DROP TABLE IF EXISTS P_warm`, retry next run. Tx2 fails (lock
timeout after the retries, constraint error) → `P_warm` is dropped, the record stays `VERIFIED`,
next run. Tx3 fails → the record is `SWAPPED`, `P` detached and still on disk; the next run
repeats Tx3 (the sanity count is cheap on a detached table? it is a full count of 240 M rows:
use the manifest count and `pg_class.reltuples` after `ANALYZE P`, or skip the count when the
previous attempt passed it). Rollback before Tx3: `DETACH P_warm; ATTACH P` in one transaction
(the library offers `unswap(table, partition)` for operators, not scheduled). Rollback after Tx3:
none needed, the month is served from S3; that is the design.

**Row delete** (the existing `EVICTION` batch): for a `PARTITION` table, GET iterates the
`SWAPPED` partitions and EXECUTE runs `DELETE FROM ONLY <partition> WHERE ctid = ANY(ARRAY(SELECT
ctid FROM ONLY <partition> WHERE archived_at IS NOT NULL AND archived_at >= updated_at AND
COALESCE(last_accessed_at, updated_at) < :before LIMIT :chunk FOR UPDATE SKIP LOCKED))`, per
partition (no partitioned-table `ctid` problem: `ONLY` one leaf). A swapped partition holds
thousands of rows, so a sequential scan per chunk is fine and **no eviction index is created** on
the full tables (building a partial index concurrently on 46 × 2 partitions of up to 1.1 TB would
be a migration project of its own). Rows with `archived_at IS NULL` in a swapped partition are
rows written after the export (a reprocess): never deleted by this batch; counted by monitoring.

Guards, restated: swap only `VERIFIED`; delete rows only in `SWAPPED` partitions and only when
`archived_at >= updated_at`; never export a partition with an open-ended bound; never touch a
partition whose name is not in the catalog's children of the table.

### Reprocess of an archived delivery

A delivery reprocessed after its month is `VERIFIED` rewrites rows with a newer `updated_at`;
after `SWAPPED` it re-inserts the whole population into the warm partition. The consumer must call
`markStale(tableId, moment)` before a reprocess (the loader's import endpoint does it when the
partition record exists); the library also detects it (`countWrittenAfter > 0` at verification,
rows with `archived_at IS NULL` in a swapped partition). A `STALE` partition stays readable cold
(the hot row wins when both exist, so a reprocessed row is served from the database), is not
swapped, and is re-exported by the next export run once the loader signals `partitionLoaded`
again; the re-export rewrites every shard and the manifest and sets `VERIFIED`, and the swap can
follow. A reprocess after a swap re-grows the partition to full size until that happens: the
operator sees it in the Slack message of the export batch and in the monitoring query.

### Schema

Consumer-owned (Flyway). `ColdTieringSchema.partitionArchiveStatements()`:

```sql
CREATE TABLE IF NOT EXISTS cold_tiering_partition_archive (
  table_id           varchar     NOT NULL,
  partition_name     varchar     NOT NULL,
  range_from         text,
  range_to           text,
  state              varchar(16) NOT NULL,
  loaded_at          timestamptz,
  export_started_at  timestamptz,
  exported_at        timestamptz,
  verified_at        timestamptz,
  swapped_at         timestamptz,
  format_version     integer,
  shard_count        integer,
  shards_done        integer,
  row_count          bigint,
  size_bytes         bigint,
  warm_rows          bigint,
  manifest_key       text,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  PRIMARY KEY (table_id, partition_name)
);
```

and `lifecycleColumnsStatement(table)` for a `PARTITION` table:

```sql
ALTER TABLE boa_vista_report_full ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ,
                                  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
```

Adding nullable columns without a default to a partitioned table propagates to every partition
as a catalog change (no rewrite, milliseconds, but it takes `ACCESS EXCLUSIVE` on each: run it
outside the loader's window). No eviction index (above), so the migration runs inside a
transaction and needs no `.sql.conf`. `create-schema=true` runs both at startup in tests. The
registry validates, for a `PARTITION` table, the lifecycle columns, the partitioning
(`pg_partitioned_table.partstrat = 'r'`, the partition column), the PK's leading column, and that
`cold_tiering_partition_archive` exists.

## Consumer changes (data-gathering)

| Change | Where |
|---|---|
| Two `PartitionTieredTable` beans: `boa-vista-report-full` (`tax_id, version, cached_at`; shard key `tax_id`; partition column `cached_at`; `LAST_ACCESS`, `INSERT`, entity + `BoaVistaSnapshotKey`), `boa-vista-cadastro-positivo-report-full` (`tax_id, cached_at`) | `BoaVistaOnlineTieringConfiguration` → renamed `BoaVistaTieringConfiguration` (keeps `coldTieringS3Client`) |
| Reads: `readReportSnapshot` → `findNewestTiered(BoaVistaReportFull.class, {tax_id}, bounds.asOf())` and, on empty with a fallback, `findFirstTiered(…, bounds.asOf(), bounds.fallbackUpTo())`; `readReportTimeline` → `findTimelineTiered`; `readCadastroPositivoSnapshot` the same; the validator's `findAllByTaxIdInAndCachedAt` → `findAllByKeysTiered`. The six JPA finders stay for tests and tooling but nothing in the read path calls them | `BoaVistaFullReadServiceComponent`, `BoaVistaFullValidator` |
| Delivery signal: when `recordApplyWorkerFinished` returns `ApplyDone`, call `partitionLoaded` for both table ids with the delivery's `cachedAt` (next to `alertDeliveryFinished`); the import endpoint (`POST …/deliveries/{id}/import`) calls `markStale` for both tables when the month already has a record | `BoaVistaFullApplyExecutor.finishWorker`, `BoaVistaFullCoordinator` |
| Flyway `V4__boa_vista_full_cold_tiering.sql`: the record table and the two `ALTER TABLE … ADD COLUMN`s (transactional, no `.sql.conf`) | `src/main/resources/db/migration` |
| Properties: `table.boa-vista-report-full.*` and `…cadastro-positivo-report-full.*`: `enabled`, `cold-read-enabled`, `ttl=P7D`, `safety-margin=P1D`, `touch-threshold=P1D`, `export.enabled`, `export.cron`, `partition-eviction.enabled`, every flag from an env var defaulting to `false`; `export.concurrency=${…:4}`; `block-codec=zstd` with `zstd-jni` added to the service pom (or `gzip`, open question 1); `index-cache.cache-manager=minutesExpirationCentralCacheManager` (the Redis manager the read gate uses, 11 min) | `application.properties`, `pom.xml` |
| S3 prefix: the online tables use `boa-vista/online/`; the full tables need their own. Either a per-table prefix (`table.<id>.prefix`, a small library addition) or the global prefix `boa-vista/` with the table id as the next segment, which the online tables would then need to migrate. Decision: add `table.<id>.prefix` (falls back to the global) and set `boa-vista/full/` on the two tables | library property, `application.properties` |
| QC endpoint on `BoaVistaFullDeliveryService`: `GET …/deliveries/archive` listing the partition records; `POST …/archive/{partition}/export` and `/swap` to run on demand (scope `VENDOR_REPORT_GATHER`) | `service/…/full` |
| Docs: `doc/boa-vista-base-full.md` gains the tiering section; this plan's consumer part becomes `doc/boa-vista-full-report-tiering.md` | `doc/` |

Rollout, every flag off at deploy:

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy; V4 adds the record table and the columns | leave them |
| 2 | Run the shard distribution query on `_2026_10`; pick `prefix` or `equi-depth` | — |
| 3 | `export.enabled` on `boa-vista-report-full`, run on demand for `boa_vista_report_full_2025_03` (~2 GB): measure rows/s, S3 bytes, verify; then the big `_2026_08` at night | delete the objects; records back to `LOADED` |
| 4 | `cold-read-enabled` on: no behaviour change yet (every partition is still hot, cold reads only happen on hot misses of `VERIFIED` partitions, which have none) | flag off |
| 5 | `partition-eviction.enabled`, swap the small 2025 month on demand; read a CPF of that month through the gate with an explicit `referenceDate`: rehydrated, served, touched | `unswap` before Tx3 |
| 6 | Backfill: export the remaining months of both tables (≈ 1–2 days of listener time at 4–8 h a big month), swap each after verification; disk goes from 4.9 TB to the current month plus warm rows | — |
| 7 | Next delivery: the loader's signal exports it on load; the swap follows 8 days later (`P7D + P1D`); then lower the TTL to `P3D` if the warm hit rate allows | raise the TTL |

Monitoring: the batch Slack messages (per partition: shards, rows, bytes, duration; a `STALE`
verdict); queue depth and oldest age of `cold-tiering/export`; `[COLD_TIERING_EXPORT]`,
`[COLD_TIERING_SWAP]`, `[COLD_TIERING_COLD_READ] table partition tier=cold|gap|miss latency`
log lines (no key values); counters when Micrometer lands (`cold.tiering.read{tier}`,
`cold.tiering.export.rows`, `cold.tiering.swap{result}`); and two queries for a dashboard:

```sql
-- partitions that should have been evicted (VERIFIED past TTL + margin) or are inconsistent
SELECT table_id, partition_name, state, loaded_at, verified_at, swapped_at, row_count
FROM cold_tiering_partition_archive WHERE state IN ('VERIFIED', 'STALE', 'EXPORTING');
-- rows written after the export inside swapped partitions (reprocess, or an unexpected writer)
SELECT count(*) FROM ONLY boa_vista_report_full_2026_08 WHERE archived_at IS NULL;
```

Alarm on: export queue age > 1 h; a `STALE` record older than a day; a swap failing its retries
twice; cold read p99 > 500 ms; any cold read on a partition in state `LOADED`/`EXPORTING` (should be
zero: it means a hot row went missing).

## Testing

Library, `src/test/java/org/coldis/library/test/service/tiering/`:
- `PartitionTieringServiceTest` on `ContainerTestHelper` (Postgres, Redis, Artemis), `storage=folder`,
  `create-schema=true`, crons `-`, `block-size=4KB`, `page-size=50`, `shard-strategy=prefix`,
  `shard-digits=1` (10 shards), `verify-sample-every=7`, `ttl=PT1H`. A consumer-side entity
  `TieredMonthlySnapshot` (`@IdClass` owner + `cached_at`, JSONB payload) whose table the test
  creates itself as `PARTITION BY RANGE (cached_at)` with three monthly partitions and a `_plus`
  (Hibernate cannot create a partitioned table: a `@Sql` DDL before the context, PK leading with
  `owner`). Cases: seed 3,000 rows over two months, `partitionLoaded` on one; run `EXPORT`: the
  folder holds `manifest`, 10 indexes, 10 `.blocks`, counts match, record `VERIFIED`, the sample
  reads passed, the other month untouched; `findByIdTiered` of a hot row costs no cold read
  (listener counter on the folder storage); clock +2 days → `PARTITION_EVICTION` swaps: rows
  touched within the TTL survive in the renamed partition, the old table is gone, a rehydration
  `INSERT` lands in the new partition; `findByIdTiered` of an evicted key → rehydrated with the
  original `updated_at`, `archived_at = updated_at`, a second read touches; `findNewestTiered`
  with a hot newer month costs no GET; `findFirstTiered` in an interval; `findTimelineTiered`
  mixes tiers; `findAllByKeysTiered` with keys across shards, blocks and a missing key; a key in a
  gap between blocks costs no block GET; clock +1 h → row `EVICTION` deletes the rehydrated rows of
  the swapped partition only; a write into the verified month → `STALE`, swap refused, re-export
  after `partitionLoaded`; a corrupted block byte → CRC failure → cache dropped, retry, then
  `coldTiering.cold.block.corrupt`; an index with `formatVersion=99` is refused; a redelivered
  shard request writes nothing; `_plus` is never exported; the registry rejects a table whose PK
  does not lead with the shard key, and `backfill.enabled` on a `PARTITION` table.
- `ShardedBlockFormatUnitTest` (plain JUnit): writer/reader round trip for gzip and zstd, block
  cut at the size, index `first`/`last`, CRC, a tax id spanning two blocks, binary search edge
  cases, `PrefixShardBoundaryStrategy` and `EquiDepthShardBoundaryStrategy` on synthetic keys,
  code-point order assertion.
- `FolderColdStorageRepository` range and multipart paths are exercised by the above; the S3
  adapter (`range`, multipart, `HeadObject`) is covered by the consumer's `s3-integration`
  profile test (`BoaVistaBatchS3IntegrationTest` already runs against the real bucket).

Consumer: no new integration test class. `BoaVistaFullPipelineTest` (the full-delivery anchor)
gains: after the apply, the two partition records are `LOADED`; `run(tableId, EXPORT)` → `VERIFIED`
on the in-memory storage; clock past the TTL → `run(…, PARTITION_EVICTION)` → the delivery's rows
are gone from the hot table (asserted through the client: a read still answers, now from cold and
rehydrated); `BoaVistaFullReadGateTest` keeps its cases (reads go through the same component).
`AbstractTestHelper.TRUNCATE_TABLES` gets `cold_tiering_partition_archive`; the test schema's
partitioned tables come from Flyway `V1` already (`spring.flyway.enabled=true` in tests).

## Risks

- **Database read cost of the export**: ~4 TiB of JSON serialised per big month and table, on
  the primary. Mitigations: measure on a small month first, `export.concurrency` low by day and
  high by night, run the backfill one table at a time; the keyset pages never hold a transaction
  across S3 calls.
- **`ACCESS EXCLUSIVE` on the parent for the swap**: every read of the table waits for the DDL;
  a long query delays it. `lock_timeout` + retries + a night cron bound it; the locked work is
  the catch-up of a minute's worth of rows.
- **Cold read latency on every first read of a CPF** after the month is swapped (the whole
  population is cold after 8 days, by design). ~100–150 ms per cold read on top of the request;
  the warm window keeps repeated CPFs hot. Measure the read QPS on the full tables before step 7.
- **The validator's 500 k sample** against an evicted month: ~55 % of the blocks of each shard
  hold a sampled key at 1 MB blocks (0.2 % of rows sampled) → ~330 k range GETs ≈ 330 GB read,
  ~20 min at 32-way parallelism, ~US$ 0.15 of requests, free egress inside the region. Workable,
  but the first cost of the design the consumer will feel (open question 3).
- **Collation vs code-point order**: asserted by the writer; a non-digit tax id would surface
  there, not in a wrong read.
- **Reprocess after a swap** re-grows a month to 1.1 TB until it is re-exported and re-swapped
  (above).
- **Heap on the export listeners**: pages of 2,000 × 19 KiB; lower `page-size` on small pods.

## Decisions (proposed, for the developer to confirm)

- Archive unit = leaf partition, read unit = block, S3-only sharding, manifest-recorded
  boundaries; no per-row archive marks except on warm rows (set in bulk at the swap and at
  rehydration), so the existing row-delete batch evicts them unchanged.
- One transaction for detach + attach + catch-up; `DETACH CONCURRENTLY` not used.
- No eviction index on the full tables; the row-delete batch works per swapped partition.
- Line-delimited `row_to_json` blocks, not Parquet: rehydration stays `json_populate_record`
  and the library keeps knowing nothing about the consumer's columns.
- `ColdStorageRepository` grows range reads and multipart uploads as default methods.

## Open questions

1. **Block codec default**: `gzip` (no new dependency, ~3 h of compression CPU per big month at
   one core) or `zstd` (`zstd-jni`, optional, near-free compression and ~2× faster decompression
   on the read path)? The plan assumes `zstd` on data-gathering.
2. **Index cache**: process-local TTL only, or the shared Redis level through
   `minutesExpirationCentralCacheManager` (adds a Redis round trip to every cold read, saves one
   40 KB GET per shard per instance per 5 minutes)? Start local; add Redis if S3 GET counts show it.
3. **The validator's sample** when the served month is already evicted: accept ~330 GB of range
   reads per validation, shrink the sample, or hold the swap of the newest `VERIFIED` month until
   the next delivery's validation has a verdict (`partition-eviction.hold-newest=true`)?
4. **`drop-delay`**: `PT0S` (disk back at once, no rollback after Tx3) or `P1D`?
5. **Reprocess policy**: `markStale` before a reprocess is a manual step today (the import
   endpoint can do it); is a reprocess of a month older than the TTL still a supported operation?
6. **DB role**: does the service's role own `boa_vista_*_full` and may it `CREATE TABLE`? The swap
   needs both.
7. **`_legacy`**: export and swap it like a month (its bounds are MINVALUE → 2025-01-01, 2024-12
   rows only), or leave it hot forever (small)?
8. **Storage class**: Standard for every month, or a lifecycle rule to Glacier Instant Retrieval
   after N months (range GET works; US$ 0.03/GB retrieval, ~1 MB per cold read)?
9. **Shard strategy** after the distribution query: `prefix` 3 digits, or `equi-depth` 1,000?
10. **Timeline rehydration**: `findTimelineTiered` rehydrates every cold row it serves (up to one
    per month, ~20 KiB each). Fine for a QC endpoint; confirm `/all` is not on a hot path.
11. **Micrometer** (open since step 1): build the counters with this step?
