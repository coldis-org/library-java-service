# Cold storage tiering, step 4: sharded partition archives (Boa Vista full tables)

Status: **library implemented**, 2026-11-09, branch `feature/cold-tiering-sharded-plan`, package
`org.coldis.library.service.tiering` (plan revised the same day with the developer's decisions: no
partition swap, equi-depth shards, gzip default, time-based hold). Extends
[`cold-storage-tiering.md`](cold-storage-tiering.md) (step 1, row tiering, built). The consumer
side (data-gathering's `boa_vista_report_full` and `boa_vista_cadastro_positivo_report_full`) is
not started. *Implementation notes* at the end list where the code departs from the plan.

## Summary

A third archive unit next to row tiering (one object per row) and window tiering (one file per
window, scan reads): **partition tiering with sharded, indexed objects**. A leaf partition of a
range-partitioned table (one month of a Boa Vista full delivery, ~240 M immutable rows, ~1.1 TB) is
exported **once**, as N shards by contiguous `tax_id` ranges of equal row count (equi-depth
boundaries computed during the export and recorded in the partition's manifest), each shard a
sequence of compressed blocks of rows sorted by primary key plus a small index (first and last key,
byte offset, length per block). A single-record cold read is: manifest → shard (binary search on
the boundaries) → block (binary search on the cached shard index) → one S3 **range GET** →
decompress one block → pick the row → `INSERT` it back into its month partition for a 7-day warm
window. S3 is the source of truth; Postgres is a warm cache with a strict last-access TTL.

No per-row archive writes: the archive guard is a **per-partition record**
(`cold_tiering_partition_archive`: table, partition, state, shards, row count, exported/verified
at). Eviction is **row delete only**: one paced sweep per verified month in heap order deletes the
rows past their TTL and marks the ones it keeps, then the row-delete batch of step 1 evicts
rehydrated and touched rows as they expire. Every month keeps its single partition forever; disk
reclaim is out of scope (the database is replaced through a new replica later). The sharding exists
only in S3.

Everything that is not the unit itself is shared with step 1: `ColdStorageRepository` (S3, folder,
memory), `ObjectKeyStrategy` through `ColdTable`, `ColumnValues` canonical segments, the registry,
`ColdTieringServiceComponent` as the read entry point, `ColdTieringBatchServiceComponent` and its
crons, the queued batch pattern (a batch enqueues, listeners do the work), the properties tree, the
`TieredRowKey`, `ColdRow`, `TieredHit` types, `HotTierRepository.rehydrate`, `touch` and
`evictExpired`.

## Goals / non-goals

Goals:
- Single-record retrieval from an archived month in ~100–150 ms, for any CPF, any month, from the
  same component methods the online tables use (`findByIdTiered`, `findNewestTiered`), plus the
  three shapes the full tables need and the online ones do not: first snapshot in an interval,
  timeline of a tax id, batch by `(tax_id IN …, cached_at = …)`.
- A row leaves Postgres only when its month's export is **verified** (counts per shard, sample
  reads) and the row is past its TTL.
- Rehydrated rows live in the month's own partition and expire by last access (7 days to start,
  3 later), through the batch that already exists.
- Export throughput bounded by the database's read rate, not by S3 request latency: ~2,000 PUTs a
  month per table instead of 240 M.
- Formats carry a version so blocks, indexes and manifests can change without re-exporting what is
  already in S3.
- Backward compatible: the online tables (row tiering) keep every class, property and behaviour.

Non-goals:
- Disk reclaim (partition swap, `DROP`, `VACUUM FULL`, `pg_repack`): deleted rows free space that
  only inserts into the same partition reuse; the files keep their size until the database is
  replaced.
- Repartitioning the hot tables; creating partitions ahead (`_plus` handling); exporting `_legacy`
  (it stays hot, it is small).
- Analytics over the shards (Athena, DuckDB). The block format is line-delimited JSON; a Parquet
  export of the same months stays a consumer concern.
- The read gate, the delivery pipeline and the validator's rules: the consumer keeps them, and only
  their repository calls change.

## The consumer today (what the design must fit)

`supersim-service-data-gathering-pom`, `origin/master`:

| Piece | Where | Relevant facts |
|---|---|---|
| Entities | `model/person/boavista/BoaVistaReportFull` (`@IdClass BoaVistaSnapshotKey`: `taxId`, `version`, `cachedAt`), `BoaVistaCadastroPositivoReportFull` (`taxId`, `cachedAt`; plus `origin`) | `AbstractTimestampableExpirableEntity`: `created_at`, `updated_at` (`timestamptz`), `expired`, `expired_at`; `data` JSONB (~19 KiB of JSON per row, ~3 KiB after TOAST); `cached_at` is `timestamp` without zone, always a day start (`dtProcessamento`) |
| Tables | Flyway `V1__baseline_schema.sql`, `scripts/migration/create_boa_vista_full_partitioned_tables.sql` | `PARTITION BY RANGE (cached_at)`; 46 leaf partitions each: `_legacy` (MINVALUE → 2025-01-01), `_2025_01` … `_2028_08`, `_plus` (2028-09-01 → MAXVALUE, must stay empty); **the primary key is the only index**, `(tax_id, version, cached_at)` and `(tax_id, cached_at)`: it leads with the shard key, so a per-shard range scan is an index range scan, in key order; no `ANALYZE` has ever completed (`n_live_tup = 0`) |
| Sizes (prod, 2026-11-09) | | `boa_vista_cadastro_positivo_report_full` 3.5 TB (2026-08/09/10 ≈ 1.1 TB each, 2025 months 1.4–21 GB), `boa_vista_report_full` 1.4 TB, same shape. ~240 M rows per monthly delivery |
| Key distribution (1 % sample, cadastro positivo 2025-10) | | 998 of 1,000 three-digit prefixes present; rows per prefix min 1 / median 12 / max 124, CV 1.17; heaviest `038`, `047`, `020`, `046`, `058`, `013`. Prefix shards would be ~100× uneven, hence equi-depth |
| Loader | `component/…/boavista/full/BoaVistaFullApplyExecutor` (6 workers, pages of tax ids, `insertAllIfAbsent` + `lockAllByTaxIds` + update of `data`), `BoaVistaFullCoordinator.recordApplyWorkerFinished` → `ApplyDone` when the last worker finishes → `alertDeliveryFinished(cohortId, month, applied)`; progress in `key_value` `bvsfull-apply-progress-<id>` (`BoaVistaFullApplyProgress`: `cachedAt`, `shards`, `totalRows` from the parquet footers, `finishedAt`, `verdict`) | The one place that knows "delivery complete". Rows are immutable once the delivery is loaded; a reprocess rewrites the same keys. Rows were inserted in parquet-shard order, so the heap order is unrelated to `tax_id` |
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
                                                 record (LOADED, loaded_at: the start of the hold)
                                               ColdTieringBatchServiceComponent: EXPORT batch (cron per
                                                 table): GET = next LOADED/STALE partition past its quiet
                                                 period, EXECUTE = boundaries pass, then enqueue the shards
                                                 to cold-tiering/export
                                               export listener (N per instance): exportShard → .blocks,
                                                 .index; last shard of a partition → manifest, count and
                                                 sample verification → VERIFIED
tiering.findByIdTiered / findNewestTiered /    ColdTieringServiceComponent dispatches on the table's
  findFirstTiered / findTimelineTiered /         archive unit: ROW → TieredRepository (as today),
  findAllByIdsTiered (new)                      PARTITION → PartitionTieredRepository (manifest, index,
                                                 range GET, block decode) then HotTierRepository.rehydrate
                                               ColdTieringBatchServiceComponent: PARTITION_SWEEP batch
                                                 (VERIFIED, past the hold and the TTL →
                                                 one paced pass in heap order: delete expired, mark kept),
                                                 EVICTION batch (step 1's row delete, unchanged: the
                                                 rehydrated and touched rows as they expire)
                                               ── repository package ──
                                               PartitionHotTierRepository (JDBC: leaf partitions and
                                                 bounds from the catalog, key-only pass for boundaries,
                                                 keyset page per shard, counts, heap-order sweep chunk)
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
| Boundaries | A list of lower bounds (canonical segments of the shard key, the first is `""`) on `PartitionManifest.boundaries`, `shardOf(segment)` by binary search; produced by the export's boundaries pass and **recorded in the manifest**, so readers never recompute them and resolve the shard with no extra S3 call |
| `ShardStrategy` enum + `EquiDepthShardCutter` | `EQUI_DEPTH` (default: the cutter closes a shard at the first key change after `target` rows during the key pass, below), `PREFIX` (`EquiDepthShardCutter.prefixBoundaries(digits)`, for a key known to be uniform; not the default, see the distribution above). Selected by `table.<id>.export.shard-strategy` |
| `BlockCodec` SPI | `compress(byte[])`, `decompress(byte[], maxLength)`, `name()`; `gzip` (JDK) is the only built-in codec. A consumer may declare another (`block-codec=<name>` plus its own bean); zstd was dropped from this change because a new native dependency (`zstd-jni`) needs the lead's approval first. The index names the codec, so a partition exported with one is readable after the default changes |
| `PartitionManifest`, `ShardIndex`, `BlockEntry` | Records of the formats below, with `formatVersion`; Jackson, through the existing `GzipJsonPayloadCodec` for the bytes |
| `PartitionArchive` | The DB record: `tableId`, `partitionName`, `rangeFrom`, `rangeTo`, `state`, `generation`, `loadedRows`, timestamps (`loadedAt`, `exportStartedAt`, `exportedAt`, `verifiedAt`, `sweptAt`), `shardCount`, `shardsDone`, `rowCount`, `sizeBytes`, `formatVersion`, `manifestKey`, `sweepCursor`, `sweepDeleted`, `sweepKept` |
| `PartitionArchiveState { LOADED, EXPORTING, VERIFIED, STALE, NEEDS_ATTENTION }` | The guard. Cold reads follow the record's `manifest_key` whatever the state (the previous generation stays readable through a reload and a re-export); only `VERIFIED` may be swept; a row may be deleted only when the export covers it (`updated_at <= export_started_at`). `NEEDS_ATTENTION`: a partition swept before and reloaded with fewer hot rows than its archive; never exported nor swept until an operator decides |
| `TieredRowKey`, `ColdRow`, `TieredHit` | Unchanged, reused |

Repository (`tiering.repository`):

| Type | Role |
|---|---|
| `ColdStorageRepository` (extended, **default methods throwing `UnsupportedOperationException`** so a consumer's own implementation keeps compiling) | `Optional<byte[]> loadRange(key, offset, length)`; `Optional<ColdObject> head(key)` (size, metadata); `ColdUpload beginUpload(key, metadata)` with `part(byte[])`, `complete() → ColdObject`, `abort()`. S3: `GetObjectRequest.range("bytes=a-b")`, `HeadObject`, multipart upload (parts ≥ 5 MB, `ChecksumAlgorithm.CRC32C` on every part); folder: `RandomAccessFile`, parts appended to the temporary file, atomic move on complete; memory: array slices |
| `PartitionHotTierRepository` | `leafPartitions(table)` from `pg_inherits` + `pg_get_expr(relpartbound)` (name validated by `TieredTable.IDENTIFIER` and by membership in the catalog, never by string building); `nextKeys(partition, lastKey, pageSize)` (key columns only, for the boundaries pass); `nextPage(partition, shardRange, lastKey, pageSize)` (the keyset export query below); `countInRange(partition, shardRange)`; `countWrittenAfter(partition, moment)`; `blockCount(partition)` (`pg_relation_size / current_setting('block_size')`); `sweepChunk(partition, fromBlock, toBlock, before, exportedAt)` (the heap-order delete-and-mark below); `evictExpired(partition, before, chunk)` (step 1's predicate, `FROM ONLY`) |
| `PartitionArchiveRepository` | CRUD on `cold_tiering_partition_archive` with `SELECT … FOR UPDATE` on state transitions |
| `PartitionTieredRepository` | `manifest(table, partition)` (cached), `shardIndex(table, partition, shard)` (cached), `readMatching(table, partition, prefixSegments)` (the rows whose key starts with the prefix: shard from the manifest, candidate blocks from the index, one range GET per block, a retry after dropping a stale index), `loadIndex`, `writeIndex`, `writeManifest`, `beginBlocks`, `forget`; composes `ColdStorageRepository`, `BlockCodec`, `ObjectKeyStrategy` |
| `ShardedBlockWriter`, `ShardedBlockReader` | Format code only: lines in, frames out, index entries; frames in, lines out; CRC32C check |

Policy (`tiering`):

| Type | Role |
|---|---|
| `PartitionTieringServiceComponent` | `partitionLoaded(tableId, momentInPartition)` (the consumer's signal, starts the hold); `computeBoundaries(table, partition)`; `exportShard(ShardExportRequest)` (JMS listener on `cold-tiering/export`, `export.concurrency`); `finishPartition(table, partition)` (manifest, verification, state); `markStale(tableId, momentInPartition)` |
| `ColdLookupStrategy` SPI + `RowColdLookupStrategy` | One bean per archive unit (`findCold`, `findNewestCold`, `findFirstCold`, `findTimelineCold`); `PartitionTieringServiceComponent` is the `PARTITION` one. The component runs the hot side, validates key prefixes (known columns, leading and gap-free) and rehydrates; it never tests the unit nor casts a definition |
| `ColdTieringServiceComponent` (extended) | every tiered read goes hot first, then the unit's strategy; new `findFirstTiered(Entity.class, keyPrefix, after, upTo)`, `findTimelineTiered(Entity.class, keyPrefix, asOf) → List<TieredHit>`, `findAllByIdsTiered(Entity.class, Collection<id>) → List<TieredHit>`; `afterWrite` on a `PARTITION` table only touches (no upload request); `delete(key)` on a `PARTITION` table deletes the hot row only and throws if the partition is archived (a cold row cannot be deleted alone from a shard; business deletes do not exist on these tables) |
| `ColdTieringBatchServiceComponent` (extended) | `TieringBatch.EXPORT` and `TieringBatch.PARTITION_SWEEP`; the row `EVICTION` on a `PARTITION` table runs step 1's `evictExpired` on the parent (the partitioned eviction index prunes to the partitions with warm rows); `BACKFILL` never runs nor schedules on a `PARTITION` table, and `EXPORT`/`PARTITION_SWEEP` never on a `ROW` table, whatever the properties say (the export is the backfill) |
| `ColdTieringSchema` (extended) | `partitionArchiveStatements()` (the record table); `statements(table, …)` is unchanged (columns and a plain index: for tests); the consumer's Flyway writes the leaf-by-leaf partitioned eviction index, see *Schema* |
| `TieredTableRegistry` (extended) | For a `PARTITION` table: the table is range-partitioned on the partition column (`pg_partitioned_table`), the shard key is the first key column and the first column of the primary key index of the parent, the shard key is digit-only text (verified at export, which fails loudly on any other value) or a non-negative integer, so its SQL order is the canonical segment order under any collation; the partition column is the last key column, of type `timestamp` without time zone (`date` and `timestamptz` are rejected: the bounds and the row keys are compared as local moments); `LAST_ACCESS` + `INSERT` only; the record table exists. `validate(table)` runs the checks without registering |

Properties, under `org.coldis.library.service.tiering.`:

| Property | Default | Meaning |
|---|---|---|
| `export.concurrency` | `1` | Listeners per instance on `cold-tiering/export`; one shard in flight per listener, so memory ≈ listeners × (`part-size` + one block + one page) |
| `block-codec` | `gzip` | Codec of new exports (`gzip` built in; another name needs a consumer bean); reads follow the index |
| `index-cache.ttl` | `PT5M` | Process-local TTL cache of manifests and shard indexes; bounded by the TTL, not by a size (an entry is ~40 KB: at 10 cold reads a second with no repeated shard, 3,000 entries ≈ 120 MB worst case, far less in practice since a shard serves ~240 k keys). A shared Redis level is future work |
| `table.<id>.export.{enabled,cron,finish-within}` | `false`, `0 15 * * * *`, `P2D` | The export batch (boundaries pass, then enqueues shards) |
| `table.<id>.export.max-pending` | `10000` | Most shard requests `cold-tiering/export` may hold for a step to start another partition's export; above it (or when the depth cannot be read) the step starts nothing and the run waits for its next step |
| `table.<id>.export.quiet-period` | `P2D` | Heuristic eligibility: a partition with no `loaded_at` signal is exported when its newest `updated_at` is older than this and its range end is in the past (backfill of existing months) |
| `table.<id>.export.exclude-partitions` | `*_legacy` | Leaf partitions never exported nor swept (glob on the name); open-ended ranges (`MAXVALUE`) are always excluded |
| `table.<id>.export.shard-strategy` / `shards` / `shard-digits` | `equi-depth` / `1000` / `3` | Boundaries for new exports; `shards` is the equi-depth target, `shard-digits` the prefix option |
| `table.<id>.export.block-size` / `page-size` / `key-page-size` / `part-size` | `1MB` (compressed, 256 KB–4 MB) / `2000` rows / `1000000` keys / `16MB` | Block cut when its estimated compressed size reaches the target (the previous block's ratio, 4:1 at first); rows per keyset page of the data pass; keys per page of the boundaries pass; multipart part |
| `table.<id>.export.verify-sample-every` | `50000` | One row in every N written (the first, the N+1-th, …; `1` samples every row) is read back through the cold path after the shard completes |
| `table.<id>.partition-sweep.{enabled,cron,chunk-size,delay-between-chunks,finish-within}` | `false`, `0 0 22 * * *`, `1024`, `PT1S`, `P3D` | The sweep: heap pages per chunk (1,024 × 8 KB = 8 MB of heap, ~2,700 rows), the pause between chunks (sets the WAL rate), the run's horizon (a month's sweep lasts hours, resumed across runs) |
| `table.<id>.partition-sweep.hold-after-load` | `P5D` | The month stays fully hot for this long after `loaded_at` (the consumer's signal) or, for a partition exported without a signal (backfill), after `verified_at`; the sweep starts after `max(hold, ttl + safety-margin)` from that moment, so its first pass finds the bulk expired |
| `table.<id>.prefix` | the global `prefix` | Object key prefix of every object of the table (blocks, indexes, manifests; a row table's objects too), so the full tables live under `boa-vista/full/` while the online ones keep `boa-vista/online/` |
| `table.<id>.ttl` / `touch-threshold` / `safety-margin` | inherited (`P15D` / `P1D` / `P2D`) | For the full tables the consumer sets `ttl=P5D` (developer range 3–5 days, per table), `safety-margin=P1D`: with the default hold, the sweep of a month starts 6 days after its load |

### Cold format

Object keys through `ObjectKeyStrategy` (`PathObjectKeyStrategy` shown), segments `[partitionName,
file]`, so a custom layout (hashed segments, another prefix shape) applies to both units:

```
<prefix><tableId>/<partition>/<generation>/manifest.json.gz          PartitionManifest
<prefix><tableId>/<partition>/<generation>/<shard %04d>.index.json.gz ShardIndex
<prefix><tableId>/<partition>/<generation>/<shard %04d>.blocks        frames, back to back
e.g. boa-vista/full/boa-vista-report-full/boa_vista_report_full_2026_08/20261110T031500123/0042.blocks
```

**Generations.** Every export of a partition writes under its own generation (the export start,
`yyyyMMddTHHmmssSSS`), never over the previous objects; the archive record's `manifest_key` is
the pointer readers follow and it is switched **last**, in the same update that sets the final
state. A failed or refused re-export therefore never damages the readable archive, and the
previous generation stays readable while a partition is `LOADED` or `EXPORTING`. Old generations
are not deleted by the library (a lifecycle rule on the prefix, or a later cleanup batch: future
work).

**`.blocks`**: a concatenation of independently decompressible frames. A frame is the `BlockCodec`
compression of `N` lines, each line the `CAST(row_to_json(part) AS text)` of one row, `\n`
separated, UTF-8, in primary-key order. No header inside the object; all structure is in the
index, so a block is readable with one range GET and one `decompress`. Object metadata:
`format-version`, `table`, `partition`, `shard`, `block-codec`, `rows`, `export-started-at`,
`content-type application/octet-stream`.

**`.index.json.gz`** (`ShardIndex`, gzip JSON through the existing codec):

```json
{ "formatVersion": 1, "tableId": "boa-vista-report-full", "partition": "boa_vista_report_full_2026_08",
  "shard": 42, "blockCodec": "gzip",
  "keyColumns": ["tax_id", "version", "cached_at"], "rows": 240113, "sizeBytes": 612345678,
  "blocks": [
    { "first": ["04213398710", "6B%2C6H", "2026-08-05T00:00:00.000000"],
      "last":  ["04213412233", "6B%2C6H", "2026-08-05T00:00:00.000000"],
      "offset": 0, "length": 1048231, "uncompressedLength": 7340032, "rows": 412, "crc32c": "a1b2c3d4" },
    { "first": ["04213412301", …], "last": [...], "offset": 1048231, "length": 1047990, "rows": 409, "crc32c": "…" }
  ] }
```

Keys are the canonical segments of `ColumnValues.toSegment` (fixed width, URL-encoded text), so the
string order is the key order and a lookup is a `String` binary search. `first`/`last` per block let
a miss between two blocks cost no GET, and let the reader fetch the next block when a tax id's rows
run across a boundary (`last` of block *i* has the same shard-key segment as the looked-up key).
At 1 MB blocks a shard of 240 k rows has ~600 blocks, an index ~35–40 KB compressed; 1,000 shards
≈ 40 MB of index per partition and table, loaded one shard at a time on demand, never all at once.
`crc32c` is checked after every range GET (in-transit integrity of a range GET is not covered by the
SDK's object checksum), and a frame is refused if it expands past `uncompressedLength` (a bound
on memory per read).

**`manifest.json.gz`** (`PartitionManifest`): `formatVersion`, `tableId`, `partition`, `generation`, `rangeFrom`,
`rangeTo` (the bounds as `pg_get_expr` prints them), `keyColumns`, `shardStrategy` (`{type:
"equi-depth", target: 1000, rowsPerShard: 240000}` or `{type: "prefix", digits: 3}`),
`boundaries` (the lower bound of every shard: the authority for `shardOf`; the first is the empty
string), `shards[]` (`shard`, `rows`, `blocks`, `sizeBytes`, `indexKey`, `blocksKey`, `etag`),
`rows`, `exportStartedAt`, `exportedAt`, `verification` (`sampledRows`, `verifiedAt`). It is written
**last**, after every shard; a reader that finds no manifest treats the partition as not archived.
The DB record copies the totals and the state; the manifest holds the layout. A reader resolves
the shard of a key from the cached manifest alone: no listing, no extra GET.

**Versioning**: `formatVersion` in the three objects and in the metadata. The reader accepts a set
of versions (`1` at first); a new block or index layout bumps it, new exports write it, old
partitions stay readable. A change of shard boundaries or block size needs no version bump: the
manifest and the index describe themselves.

### Shard boundaries: equi-depth from the primary key, in one pass

Fixed prefixes are out as the default: the 1 % sample of `_2025_10` gives rows per 3-digit prefix
from 1 to 124 around a median of 12 (CV 1.17), i.e. shards from ~20 k to ~2.5 M rows. Equi-depth
boundaries give every shard the same row count, so every shard object and index has the same size
and every read the same cost.

The boundaries are computed **per partition, at export time, from the primary key, in a single
ordered pass that reads no data**, before the data pass starts (`computeBoundaries`, the `EXPORT`
batch's EXECUTE for the partition):

1. `target = ceil(estimate / shards)`, with `estimate` = `pg_class.reltuples` of the partition when
   it is positive (set by `VACUUM` or `ANALYZE`; both tables show `n_live_tup = 0` today, so the
   first exports fall back to) the loader's `totalRows` when the record has one (passed in
   `partitionLoaded`), else a one-off `SELECT count(*) FROM ONLY <partition>` (minutes; done once,
   outside a request, and written to the record).
2. Pages of keys over the PK, each page its own short read-only statement (so no statement or
   transaction timeout applies, on the primary or on a replica with `statement_timeout`):
   ```sql
   SELECT tax_id FROM ONLY <partition>
   WHERE tax_id > :lastTaxId ORDER BY tax_id LIMIT :keyPageSize            -- 1,000,000 keys ≈ 1–2 s
   ```
   (`WHERE (tax_id, version, cached_at) > (:last…)` with the full key when the shard key is not
   unique within a partition, which is the case when a month holds two deliveries.) The scan is
   an index range scan on the PK and reads only the index when the visibility map allows, heap
   headers otherwise, never TOAST: ~15 GB of index per big partition against ~4 TiB of JSON in the
   data pass. 240 keys per page is one row; 240 pages of 1 M keys ≈ 5–10 minutes per month.
3. Running count; when it reaches `target`, close the shard **at the next change of `tax_id`** (all
   rows of a tax id stay in one shard) and record the new shard's first `tax_id` as its lower
   bound. The last shard takes the remainder; the actual shard count is `target ± 1` around
   `shards` when the estimate is right, and simply different when it is not (the manifest holds
   the truth, nothing else assumes 1,000).
4. The boundaries go into the record (`boundaries` JSON) and into a provisional manifest key with
   `state = EXPORTING`; the shards are then enqueued with their `[from, to)` bounds.

Why not a separate quantile query (`percentile_disc` over the whole partition or over a
`TABLESAMPLE`): the exact version is one long sort of 240 M keys (a single statement, hours, and
it trips `statement_timeout` on a replica), the sampled version is a second scan for an estimate
the pass above gets exactly and cheaply. The pass is resumable (cursor = last key, the partial
boundary list is kept on the record every N pages). The `prefix` strategy skips the pass and is
kept for tables with a known-uniform key.

### Export

Eligibility (GET of the `EXPORT` batch, one partition at a time per table): leaf partitions of the
table from the catalog, excluding the `exclude-partitions` names (`_legacy`) and open-ended ranges
(`_plus`), whose record is `LOADED` or `STALE`, or that have no record and pass the heuristic
(`quiet-period`). The consumer's signal (`partitionLoaded`) is preferred and exact; the heuristic
exists for the months already in the tables. Nothing is exported while the partition is still
being written: a `LOADED` record carries the loader's `loaded_at`; the heuristic requires
`max(updated_at) < now - quiet-period` (a cheap index-free check is `SELECT 1 … WHERE updated_at >
:since LIMIT 1`, a sequential scan with an early exit; acceptable once per hour per candidate
partition).

Row count of a delivery, to size and to verify: exact, `SELECT count(*) FROM ONLY <partition>`
(minutes to tens of minutes on 1.1 TB without a visibility map; run once, outside a request);
from the loader, `BoaVistaFullApplyProgress.totalRows` (the parquet footers) and `applied`; the
boundaries pass counts every key exactly and is what the manifest records; the sum of the
per-shard index range counts (verification) must agree with it. `pg_stat_user_tables.n_live_tup`
is useless here until `ANALYZE` runs.

EXECUTE of the batch: set the record `EXPORTING` (`export_started_at`), run the boundaries pass,
then enqueue one `ShardExportRequest(tableId, partition, generation, shard, from, to,
exportStartedAt)` per shard to `cold-tiering/export`, sent **after the record's commit** (the
batch step is one transaction); a request that still overtakes the record (an unseen generation)
is redelivered by the broker. The listeners do the work, N per instance, so parallelism is `export.concurrency ×
instances`, independent of the batch.

**Backpressure** (as the row backfill's): when the GET finds a partition to export, it first asks
`PartitionTieringServiceComponent.findExportBacklog()` for the depth of `cold-tiering/export`
(Artemis core `queueQuery` message count, cached 100 ms). Above the table's `export.max-pending`
(default `10000`) no export starts: the GET returns `BatchPostponed.of()`, and the batch service
postpones the run (it does not finish; the next step comes after `delay-between-chunks`),
and the partition stays a candidate. A depth that cannot be read counts as full (`WARN`, fail
safe). The queue is shared by every partition-tiered table. One step still enqueues a whole
partition's shards (`export.shards`, ~1,000), so the bound is about `max-pending + shards`.

`exportShard`, one shard, no long transaction:

1. If the shard's index object of this generation exists with the request's `exportStartedAt`
   (a redelivered or re-enqueued shard after a restart), nothing is written; the shard is still
   recorded from the index's summary and the finish check runs, so a partition never stays
   `EXPORTING` with every shard written.
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
4. `complete()` the blocks object, write the index (it carries the generation and
   `exportStartedAt`), then record the shard: `completeShard` (idempotent per shard and
   generation) and, when it was the last one, the manifest and the record's final state run in
   **one transaction** (`recordShard`), so the record never names a manifest that does not exist
   and the final state is a single update. No row lock on the hot table at any point.
5. Verification of the shard, in the same listener: `countInRange(partition, from, to)` (index
   range count) must equal the rows written; every `verify-sample-every`-th key seen (kept in a
   small list) is read back through `PartitionTieredRepository.readRow` and compared byte for byte
   with the line written. A mismatch fails the shard (exception → retry → DLQ) and the partition
   never reaches `VERIFIED`.
6. The listener that completes the last shard writes the manifest under the generation, checks
   that no row has a watermark after `export_started_at` (nothing changed under the export) and
   sets `VERIFIED` with the manifest pointer, or `STALE` (see *Reprocess*), in one update.

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
~300 MB/s aggregate from 8 listeners that is ~4 h; gzip at ~50 MB/s per core adds ~3 h of service
CPU unless spread across 8+ cores (a zstd codec, ~400 MB/s per core, would make compression
negligible: it waits for the dependency's approval). Expect 4–8 h per big month per table with
`export.concurrency=4` on 2 instances, plus 5–10 minutes for the boundaries pass; plan the first
backfill month at night. S3 requests: 1,000 shards × (~40 parts + complete + index) + manifest ≈
42,000 PUT-class requests ≈ US$ 0.21 per month and table. Storage: JSON compresses 6–8× (gzip) → ~0.5–0.7 TB per big month and table (vs 1.1 TB on EBS); the existing
4.9 TB of both tables minus `_legacy` ≈ 2–2.5 TB in S3 ≈ US$ 50–60 a month at Standard, the only
storage class in this step.

### Read path

Entry points on `ColdTieringServiceComponent`, dispatching on `ArchiveUnit.PARTITION`:

| Method | Hot | Cold | Result |
|---|---|---|---|
| `findByIdTiered(Entity, id)` | JPQL by key (the parent, pruned to one partition by `cached_at`) | partition of `cached_at` → record readable? → `readRow` | rehydrate, hot read again, touch |
| `findNewestTiered(Entity, {tax_id[, version]}, asOf)` | `findNewestHot` (one probe per partition index, 46) | readable partitions with `rangeFrom <= asOf`, **newest first, only those whose range starts after the hot candidate's `cached_at`**; stop at the first hit | the newer of hot and cold; cold → rehydrate |
| `findFirstTiered(Entity, {tax_id}, after, upTo)` (new) | `findFirstHot` in the interval | readable partitions intersecting `(after, upTo]`, oldest first, only those before the hot candidate | same |
| `findTimelineTiered(Entity, {tax_id}, asOf)` (new) | all hot rows of the prefix | every readable partition `<= asOf` (one block read each; a partition is not skipped because a hot row falls in it: a month may hold two deliveries) | hot ∪ cold merged by the full key, the hot row winning, sorted by the last key column then the whole key, newest first; cold rows rehydrated |
| `findAllByIdsTiered(Entity, ids)` (new) | one lookup per key | one cold lookup per missing key (grouping by block and coalescing adjacent blocks: future work) | hits in input order; cold rows rehydrated |

`delete(key)` on a partition-tiered table refuses a row whose partition has a readable manifest
(`coldTiering.delete.archived`: the archive holds whole partitions) and deletes the hot row of a
partition not archived.

"Readable" = the record points at a manifest (`manifest_key`), whatever its state: the previous
generation serves cold reads through a reload and a re-export. A partition with no manifest is
**hot by definition** (every row of it is still in Postgres: nothing deletes a row before
`VERIFIED`), so a hot miss on such a partition is a true miss and costs no GET. The guard is
explicit: `sweepChunk` and `evictExpired` take the partition only from `VERIFIED` records, and a
monitoring query (below) flags a partition whose live row count is below its manifest count while
its record is not `VERIFIED`.

One cold read: `manifest` (cache hit after the first read of the partition on the instance) →
`boundaries.shardOf(segment)` → `shardIndex` (cache; else one GET of ~40 KB) → binary search on
`first` → `last` check (a gap is a miss without a GET) → one range GET of ≤ `block-size` bytes →
CRC32C → decompress → scan the lines for the key (Jackson streaming parser reading the key fields
only; a line is ~19 KiB, a block ~400 lines) → `ColdRow`. Latency budget, same region: S3 range GET
TTFB 30–70 ms + 1 MB transfer ~10 ms + decompress 5–10 ms (gzip) +
`json_populate_record` INSERT ~5 ms + the touch request: **~100–150 ms p50, ~250 ms p99** for a
key hit, ~0 ms extra for a key miss in a gap, ~70 ms for a miss inside a block. A cold index fetch
adds one GET (~40 ms). The hot hit path is unchanged (one JPQL, one touch when stale).

The hot entry is always tried first, even for `findByIdTiered`, so a read during an export, or of
a `STALE` partition, serves the database's current row and never an older cold copy.

Rehydration: `HotTierRepository.rehydrate` as built: `INSERT INTO <parent> SELECT * FROM
json_populate_record(NULL::<parent>, :json) ON CONFLICT DO NOTHING`, then `archived_at =
updated_at`, `last_accessed_at = now`. The `INSERT` goes through the parent and Postgres routes it
by `cached_at` into the month partition, which **always exists**: no partition is ever detached or
dropped in this design, and `_plus` catches a range beyond the created months. If routing fails
anyway (a partition dropped by hand), the error is logged and the `ColdRow` is still served in the
`TieredHit` (the read must not fail because the cache is unavailable; the consumer maps the cold row
like the online `NONE` case). A rehydrated row lands with `archived_at = updated_at`, which is
what makes it visible to step 1's eviction index and predicate, so the row-delete batch evicts it
after the TTL with no change.

Touch: unchanged (`cold-tiering/touch`, `GREATEST`, `touch-threshold`).

Races: *read during export*: the partition is fully hot, the read hits hot; the export's pages are
each consistent and the rows are immutable, so a concurrent read changes nothing. *Read during a
sweep chunk*: the chunk deletes rows by `ctid` under `FOR UPDATE SKIP LOCKED` after re-checking the
predicate; a touch landing between the pick and the delete bumps `last_accessed_at`, the
EvalPlanQual re-check keeps the row. *Two instances rehydrate the same key*: `ON CONFLICT DO
NOTHING`. *Index cache stale after a re-export*: a re-export changes offsets; the CRC32C check
fails on a stale index → the reader drops the cached index and manifest for the partition and
retries once.

### Eviction: row delete only

Two batches, both deletes, no DDL, no change of the partition layout:

**Partition sweep** (`PARTITION_SWEEP`, new): one paced pass per verified month, in **heap order**,
that deletes every row of the month past its TTL and marks the ones it keeps. Its first chunk
re-counts the partition (`count(*)`, an index range count over the whole key) and requires it to
equal the manifest's rows: a difference means rows the archive does not cover, the record goes
`STALE` (ERROR log, committed by the chunk's own step) and nothing is deleted; the next candidate
query skips it. Every chunk also counts the rows it could not lock
(`FOR UPDATE SKIP LOCKED`, another transaction touching them) and logs them as `skipped`; the
cursor advances by block, so they are not revisited: they stay hot (and were being read or
written, so inside the TTL anyway) and leave through the row-delete batch once marked, or on a
later `partitionLoaded` + export cycle. GET = the next
`VERIFIED` partition with `swept_at IS NULL` whose hold start (`loaded_at`, else `verified_at`)
plus `max(hold-after-load, ttl + safety-margin)` is in the past; the item is a block range, cursor
`sweep_cursor` on the record (resumes across runs and restarts). EXECUTE, one chunk per
transaction (`long-timeout`):

```sql
-- Postgres 14+: a TID Range Scan reads exactly these heap pages, once, sequentially.
WITH picked AS (
  SELECT ctid, (COALESCE(last_accessed_at, updated_at) < :before) AS expired
  FROM ONLY <partition>
  WHERE ctid >= :fromTid AND ctid < :toTid                    -- '(fromBlock,0)' .. '(toBlock,0)'
    AND updated_at <= :exportStartedAt                         -- covered by the export (immutable rows)
  FOR UPDATE SKIP LOCKED
), deleted AS (
  DELETE FROM ONLY <partition> part USING picked
  WHERE part.ctid = picked.ctid AND picked.expired
    AND COALESCE(part.last_accessed_at, part.updated_at) < :before   -- re-checked on the row itself
  RETURNING 1
), kept AS (
  UPDATE ONLY <partition> part SET archived_at = part.updated_at
  FROM picked WHERE part.ctid = picked.ctid AND NOT picked.expired AND part.archived_at IS NULL
  RETURNING 1
)
SELECT (SELECT count(*) FROM deleted), (SELECT count(*) FROM kept);
```

Why heap order and not key order: the rows were inserted in parquet order, so a key-order sweep
would touch the 1.1 TB of heap and TOAST pages at random, revisiting each page many times across
checkpoints, and every first touch of a page after a checkpoint writes a full-page image (8 KB) to
the WAL: WAL volume would be a multiple of the table's size. In heap order each page is touched
once, TOAST chunks (allocated at insert, in the same order) nearly so, and the WAL is bounded by
roughly one full-page image per heap and TOAST page plus the delete records: **≈ 1.0–1.3× the
partition's size, ~1.1–1.4 TB per big month and table**, spread over the sweep's duration.
Production runs Postgres 18.6, so the TID range scan is available; no key-order fallback is built.

Rows the sweep keeps (read or written inside the TTL: `kept`) get `archived_at = updated_at`, which
puts them into the partial eviction index (`WHERE archived_at IS NOT NULL`) alongside the
rehydrated rows, so the second batch finds them when they expire. Rows with `updated_at >
exported_at` (a reprocess) are not covered by the export and are neither deleted nor marked; they
are what turns the record `STALE`.

Volume and pacing (per big month and table): ~240 M rows minus the warm ones (a few thousand to a
few hundred thousand, by read traffic) ≈ **the whole month**. Heap deletes with TOAST run at
~20–50 k rows/s on one connection, so the raw work is 1.5–3.5 h; the WAL is what to pace. With
`chunk-size=1024` (8 MB of heap, ~2,700 rows, ~8–10 MB of WAL with the TOAST pages) and
`delay-between-chunks=PT1S`, the sweep writes ~10 MB/s of WAL and finishes a month in ~35–40 h
across the nightly runs (`finish-within=P3D`); `PT0.5S` halves that at ~20 MB/s. Tune to what the
replicas and the WAL archive absorb; the Slack message of each run reports blocks, rows deleted,
rows kept and duration. Run one table at a time; never during a delivery's apply (the apply's
WAL comes on top).

Vacuum and space: the sweep leaves ~240 M dead tuples (plus their TOAST chunks and PK entries)
per month. Autovacuum handles them (developer's decision: no manual vacuum step): it triggers at
`autovacuum_vacuum_scale_factor` × 240 M (48 M at the default 0.2) and vacuums the 1.1 TB
partition in hours under its cost delay; the PK shrinks only by page reuse, not in size. **Freed
space is reused only by inserts into the same partition**
(the rehydrations of that month, a few MB a day): the files stay at 1.1 TB each until the
database is replaced through a new replica; only trailing empty pages are truncated by vacuum.
That is the developer's call and out of scope here.

**Row delete** (`EVICTION`, step 1's batch, unchanged): on a `PARTITION` table it runs
`evictExpired` on the parent with the same predicate as the online tables (`archived_at IS NOT NULL
AND archived_at >= updated_at AND COALESCE(last_accessed_at, updated_at) < :before`, keys picked
`FOR UPDATE SKIP LOCKED`, chunked). The partitioned eviction index on the parent prunes it to the
partitions holding warm rows (rehydrated or kept by a sweep); the never-marked bulk is outside the
partial index and outside the predicate, so an unswept month is never touched by it. `archived_at
>= updated_at` keeps a row rewritten after rehydration (a reprocess) out of the delete.

Guards, restated: sweep only `VERIFIED` partitions past their hold and TTL; delete only rows with `updated_at <= exported_at` and past the clock;
never export or sweep an excluded or open-ended partition; never touch a partition whose name is
not in the catalog's children of the table.

### Reprocess of an archived delivery

**Guard against replacing a complete archive with a partial one.** Once a sweep has started on a
partition (`swept_at` or `sweep_cursor` set), most of its rows live only in S3. A reload signal
(`partitionLoaded`) then puts the record back to `LOADED` but keeps the manifest pointer and the
sweep marks; when the export batch picks the partition, `startExport` counts the hot rows and
refuses **any** re-export (a count would not tell a second delivery from a reload): the record
goes `NEEDS_ATTENTION` with an ERROR log, nothing is written, the previous generation keeps
serving cold reads, and an operator decides. `confirmReexport(tableId, moment)` (the consumer's
QC endpoint) records the decision and puts the record back to `LOADED`; the next export then
runs under a new generation over the hot rows only (the rows only the previous generation held
are orphaned, which is what the operator confirmed; the flag is cleared when that export
finishes). A re-export of a partition never swept (every row still hot) needs no guard.

A delivery reprocessed after its month is `VERIFIED` rewrites rows with a newer `updated_at`;
after the sweep it re-inserts the whole population into the partition. The consumer's import
endpoint calls `markStale(tableId, moment)` when the month already has a record (a reprocess of a
month older than its TTL is a manual operation: the operator calls `markStale` through the QC
endpoint first). The library also detects it (`countWrittenAfter > 0` at verification; rows with
`updated_at > exported_at` seen by the sweep). A `STALE` partition stays readable cold (the hot
row wins when both exist, so a reprocessed row is served from the database), is not swept, and is
re-exported by the next export run once the loader signals `partitionLoaded` again; the re-export
rewrites every shard and the manifest (new boundaries if the population changed) and sets
`VERIFIED`, and the sweep follows after the TTL. Between the reprocess and the next sweep the month
is at full size again.

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
  swept_at           timestamptz,
  format_version     integer,
  shard_count        integer,
  shards_done        integer,
  row_count          bigint,
  loaded_rows        bigint,
  generation         text,
  size_bytes         bigint,
  reexport_confirmed boolean NOT NULL DEFAULT FALSE,
  boundaries         jsonb,
  sweep_cursor       bigint,
  sweep_deleted      bigint,
  sweep_kept         bigint,
  manifest_key       text,
  created_at         timestamptz NOT NULL,
  updated_at         timestamptz NOT NULL,
  PRIMARY KEY (table_id, partition_name)
);
```

and `statements(table, watermarkType, concurrently)` for a `PARTITION` table:

```sql
ALTER TABLE boa_vista_report_full ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ,
                                  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
-- the eviction index, partitioned: CONCURRENTLY is not allowed on the parent, so one per leaf.
-- The consumer's Flyway migration spells these out (the library ships no generator for them).
CREATE INDEX IF NOT EXISTS boa_vista_report_full_cold_evict_idx
  ON ONLY boa_vista_report_full ((COALESCE(last_accessed_at, updated_at))) WHERE archived_at IS NOT NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS boa_vista_report_full_2026_08_cold_evict_idx
  ON boa_vista_report_full_2026_08 ((COALESCE(last_accessed_at, updated_at))) WHERE archived_at IS NOT NULL;
ALTER INDEX boa_vista_report_full_cold_evict_idx ATTACH PARTITION boa_vista_report_full_2026_08_cold_evict_idx;
-- … one pair per leaf partition (46 per table), listed in the migration
```

Adding nullable columns without a default to a partitioned table propagates to every partition
as a catalog change (no rewrite, milliseconds, but it takes `ACCESS EXCLUSIVE` on each: run it
outside the loader's window). The partial index is empty on every partition today (`archived_at`
is NULL everywhere) but building it `CONCURRENTLY` still scans each leaf once: seconds on the
empty and small ones, ~1 h on each 1.1 TB month, without blocking reads or writes. As in V3, the
migration runs outside a transaction (`.sql.conf` with `executeInTransaction=false`,
`spring.flyway.postgresql.transactional-lock=false`); the `ALTER INDEX … ATTACH` statements may
run in it too. Partitions created later under the parent inherit the index automatically (the
`_plus` rotation in `doc/boa-vista-base-full.md` keeps working). `create-schema=true` runs it all
at startup in tests, non-concurrently.

The registry validates, for a `PARTITION` table, the lifecycle columns, the partitioning
(`pg_partitioned_table.partstrat = 'r'`, the partition column), the PK's leading column, the
eviction index on the parent, and that `cold_tiering_partition_archive` exists.

## Consumer changes (data-gathering)

| Change | Where |
|---|---|
| Two `PartitionTieredTable` beans: `boa-vista-report-full` (`tax_id, version, cached_at`; shard key `tax_id`; partition column `cached_at`; `LAST_ACCESS`, `INSERT`, entity + `BoaVistaSnapshotKey`), `boa-vista-cadastro-positivo-report-full` (`tax_id, cached_at`) | `BoaVistaOnlineTieringConfiguration` → renamed `BoaVistaTieringConfiguration` (keeps `coldTieringS3Client`) |
| Reads: `readReportSnapshot` → `findNewestTiered(BoaVistaReportFull.class, {tax_id}, bounds.asOf())` and, on empty with a fallback, `findFirstTiered(…, bounds.asOf(), bounds.fallbackUpTo())`; `readReportTimeline` → `findTimelineTiered`; `readCadastroPositivoSnapshot` the same; the validator's `findAllByTaxIdInAndCachedAt` → `findAllByIdsTiered`. The six JPA finders stay for tests and tooling but nothing in the read path calls them | `BoaVistaFullReadServiceComponent`, `BoaVistaFullValidator` |
| Delivery signals: when `recordApplyWorkerFinished` returns `ApplyDone`, call `partitionLoaded` for both table ids with the delivery's `cachedAt` and `totalRows` (next to `alertDeliveryFinished`); the import endpoint (`POST …/deliveries/{id}/import`) calls `markStale` for both tables when the month already has a record | `BoaVistaFullApplyExecutor.finishWorker`, `BoaVistaFullCoordinator` |
| Flyway `V4__boa_vista_full_cold_tiering.sql` + `.sql.conf`: the record table, the two `ALTER TABLE … ADD COLUMN`s, the partitioned eviction index with its 46 concurrent leaf builds per table | `src/main/resources/db/migration` |
| Properties: `table.boa-vista-report-full.*` and `…cadastro-positivo-report-full.*`: `enabled`, `cold-read-enabled`, `ttl=P5D`, `safety-margin=P1D`, `touch-threshold=P1D`, `export.enabled`, `export.cron`, `partition-sweep.enabled`, `partition-sweep.hold-after-load=P5D`, `partition-sweep.delay-between-chunks`, every flag from an env var defaulting to `false`; `export.concurrency=${…:4}`; `block-codec=gzip` (zstd once its dependency is approved) | `application.properties` |
| S3 prefix: the online tables use `boa-vista/online/`; the full tables need their own. `table.<id>.prefix` (built; falls back to the global `prefix`) set to `boa-vista/full/` on the two tables | `application.properties` |
| QC endpoint on `BoaVistaFullDeliveryService`: `GET …/deliveries/archive` listing the partition records; `POST …/archive/{partition}/export`, `/sweep`, `/stale`, `/confirm-reexport` to run or mark on demand (scope `VENDOR_REPORT_GATHER`) | `service/…/full` |
| Docs: `doc/boa-vista-base-full.md` gains the tiering section; this plan's consumer part becomes `doc/boa-vista-full-report-tiering.md` | `doc/` |

Rollout, every flag off at deploy:

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy; V4 adds the record table, the columns and the eviction indexes (hours of concurrent builds on the big months, no blocking) | leave them |
| 2 | `export.enabled` on `boa-vista-report-full`, run on demand for `boa_vista_report_full_2025_03` (~2 GB): boundaries pass time, rows/s, S3 bytes, shard sizes (equal, by construction), verification; then the big `_2026_08` at night | delete the objects; records back to `LOADED` |
| 3 | `cold-read-enabled` on: no behaviour change yet (every partition is still hot, cold reads only happen on hot misses of `VERIFIED` partitions, which have none) | flag off |
| 4 | `partition-sweep.enabled`; the backfilled months count their hold from `verified_at`; sweep the small 2025 month on demand once its hold passed; read a CPF of that month through the gate with an explicit `referenceDate`: rehydrated, served, touched; the row `EVICTION` removes it after the TTL | flag off: deleted rows are served from S3, that is the design |
| 5 | Backfill: export the remaining months of both tables (≈ 1–2 days of listener time at 4–8 h a big month), then sweep each at ~10–20 MB/s of WAL (1.5–3 days a month, one table at a time, autovacuum follows); watch replica lag and the WAL archive | pause the sweep (`enabled=false`, resumes from the cursor) |
| 6 | Next delivery: the loader's signal exports it on load; the month stays fully hot for the `P5D` hold and the sweep follows 6 days after the load (`P5D + P1D`); then lower the TTL towards `P3D` if the warm hit rate allows | raise the TTL or the hold |

Monitoring: the batch Slack messages (per partition: shards, rows, bytes, duration; a `STALE`
verdict; per sweep run: blocks, deleted, kept); queue depth and oldest age of
`cold-tiering/export`; `[COLD_TIERING_EXPORT]`, `[COLD_TIERING_SWEEP]`,
`[COLD_TIERING_COLD_READ] table partition tier=cold|gap|miss latency` log lines (no key values);
replica lag and WAL generation during sweeps; and two queries for a dashboard:

```sql
-- partitions that should have been swept (VERIFIED, past hold and TTL + margin) or are inconsistent
SELECT table_id, partition_name, state, loaded_at, verified_at, swept_at, row_count,
       sweep_cursor, sweep_deleted, sweep_kept
FROM cold_tiering_partition_archive WHERE state IN ('VERIFIED', 'STALE', 'EXPORTING');
-- rows written after the export inside a verified month (reprocess, or an unexpected writer)
SELECT count(*) FROM ONLY boa_vista_report_full_2026_08 WHERE updated_at > '<exported_at>';
```

Alarm on: export queue age > 1 h; `artemis_message_count{queue="cold-tiering/export"}` above
`export.max-pending` plus one partition's shards (the guard should keep it below); repeated
`[COLD_TIERING_EXPORT] … depth unreadable` warnings (the export is stalled, failing safe); a `STALE` record older than a day; a sweep run that deleted
nothing on a partition with `sweep_cursor < blocks`; cold read p99 > 500 ms; any cold read on a
partition in state `LOADED`/`EXPORTING` (should be zero: it means a hot row went missing);
replica lag above its usual ceiling during a sweep.

## Testing

Library, `src/test/java/org/coldis/library/test/service/tiering/`:
- `PartitionTieringServiceTest` on `ContainerTestHelper` (Postgres, Redis, Artemis), `storage=folder`,
  `create-schema=true`, crons `-`, `block-size=4KB`, `page-size=50`, `key-page-size=100`,
  `shards=8`, `verify-sample-every=7`, `chunk-size=2`, `ttl=PT1H`. A consumer-side entity
  `TieredMonthlySnapshot` (`@IdClass` owner + `cached_at`, JSONB payload) whose table the test
  creates itself as `PARTITION BY RANGE (cached_at)` with three monthly partitions and a `_plus`
  (Hibernate cannot create a partitioned table: a `@Sql` DDL before the context, PK leading with
  `owner`, owners drawn from a skewed distribution so equi-depth and prefix differ). Cases: seed
  3,000 rows over two months, `partitionLoaded` on one; run `EXPORT`: the boundaries pass gives
  ~8 shards within ±1 row of each other, the manifest records them, the folder holds the indexes
  and `.blocks`, counts match, record `VERIFIED`, the sample reads passed, the other month
  untouched; `findByIdTiered` of a hot row costs no cold read (counter on the folder storage);
  `PARTITION_SWEEP` before the TTL does nothing; clock +2 days → the sweep deletes the month
  except the rows read or written within the TTL, which get `archived_at`; a rehydration `INSERT`
  lands in the month's partition; `findByIdTiered` of a swept key → rehydrated with the original
  `updated_at`, `archived_at = updated_at`, a second read touches; `findNewestTiered` with a hot
  newer month costs no GET; `findFirstTiered` in an interval; `findTimelineTiered` mixes tiers;
  `findAllByIdsTiered` with keys across shards, blocks and a missing key; a key in a gap between
  blocks costs no block GET; clock +1 h → row `EVICTION` deletes the rehydrated and the kept rows,
  nothing else; a write into the verified month → `STALE`, sweep refused, re-export after
  `partitionLoaded`; a month inside its hold is not swept although its rows are expired; a corrupted
  block byte → CRC failure → cache dropped, retry, then `coldTiering.cold.block.corrupt`; an index
  with `formatVersion=99` is refused; a redelivered shard request writes nothing; `_plus` and an
  excluded partition are never exported; with the export listener stopped and a runtime
`export.max-pending` below one partition's shards, January's shards stay queued, the February
candidate is not started (record still `LOADED`, run not finished) until the listener drains the
queue, then it is exported and `VERIFIED`; the registry rejects a table whose PK does not lead with
  the shard key, a non-digit shard key value at export, a shard key of another type and a
  partition column that is not the last key column.
- `ShardedBlockFormatUnitTest` (plain JUnit): writer/reader round trip for gzip, block cut at the size, index `first`/`last`, CRC, a tax id spanning two
  blocks, binary search edge cases, `EquiDepthShardCutter` on a stream of skewed keys
  (cuts at tax-id changes, remainder in the last shard, wrong estimate → different shard count),
  prefix boundaries, code-point order assertion.
- `FolderColdStorageRepository` range and multipart paths are exercised by the above; the S3
  adapter (`range`, multipart, `HeadObject`) is covered by the consumer's `s3-integration`
  profile test (`BoaVistaBatchS3IntegrationTest` already runs against the real bucket).

Consumer: no new integration test class. `BoaVistaFullPipelineTest` (the full-delivery anchor)
gains: after the apply, the two partition records are `LOADED`;
`run(tableId, EXPORT)` → `VERIFIED` on the in-memory storage; clock past the hold → `run(…,
PARTITION_SWEEP)` → the delivery's rows are gone from the hot table except the ones the test read
(asserted through the client: a read still answers, now from cold and rehydrated);
`BoaVistaFullReadGateTest` keeps its cases (reads go through the same component).
`AbstractTestHelper.TRUNCATE_TABLES` gets `cold_tiering_partition_archive`; the test schema's
partitioned tables come from Flyway `V1` already (`spring.flyway.enabled=true` in tests).

## Risks

- **Database read cost of the export**: ~4 TiB of JSON serialised per big month and table, on
  the primary. Mitigations: measure on a small month first, `export.concurrency` low by day and
  high by night, run the backfill one table at a time; the keyset pages never hold a transaction
  across S3 calls.
- **WAL of the sweep**: ~1.1–1.4 TB per big month and table even in heap order; paced by
  `delay-between-chunks` (default ~10 MB/s), visible on replica lag and the WAL archive. The
  deleted space is not returned to the OS (out of scope by decision).
- **Autovacuum after the sweep**: hours per partition under its cost delay; the PK of a swept month
  keeps its size.
- **Cold read latency on every first read of a CPF** after the month is swept (the whole population
  is cold after 6 days, by design). ~100–150 ms per cold read on top of the request; the warm
  window keeps repeated CPFs hot. Measure the read QPS on the full tables before step 6.
- **The validator's 500 k sample** compared against the served date's rows, which live in an
  already swept month: ~55 % of the blocks of each shard hold a sampled key at 1 MB blocks (0.2 %
  of rows sampled) → ~330 k range GETs ≈ 330 GB read, ~20 min at 32-way parallelism, ~US$ 0.15 of
  requests, free egress inside the region. Workable; the hold keeps the delivery itself hot for
  its validation, not the month it is compared with.
- **Collation vs code-point order**: asserted by the writer; a non-digit tax id would surface
  there, not in a wrong read.
- **Reprocess after a sweep** re-grows a month to 1.1 TB until it is re-exported and re-swept
  (above).
- **Heap on the export listeners**: pages of 2,000 × 19 KiB; lower `page-size` on small pods.

## Decisions (developer, 2026-11-09)

- Archive unit = leaf partition, read unit = block, S3-only sharding, equi-depth boundaries
  computed per partition at export in one key-only pass and recorded in the manifest (`prefix`
  stays an option); no per-row archive marks except on warm rows (set by the sweep on what it
  keeps and by rehydration), so step 1's row-delete batch evicts them unchanged.
- **No partition swap, no drop**: eviction is row delete only (the heap-order sweep per verified
  month, then step 1's batch); every month keeps its single partition; disk reclaim is out of
  scope (database replaced through a new replica later).
- Block codec `gzip` built in; zstd is future work pending the lead's approval of `zstd-jni`
  (the `BlockCodec` SPI takes a consumer bean meanwhile).
- Index cache in memory, TTL-bounded; Redis later if S3 GET counts call for it.
- Time-based hold: a month stays fully hot for `hold-after-load` (`P5D`) after the loader's
  signal (after `verified_at` for a backfilled month); no validator release signal.
- Warm TTL `P5D` on the last-access clock for the full tables (range 3–5 days, per table).
- Sweep pacing ~10 MB/s of WAL by default (`chunk-size=1024`, `PT1S`), property-tunable; the
  sweep uses the TID range scan (production Postgres 18.6).
- Autovacuum only after a sweep; no manual vacuum step.
- Reprocess: `markStale` on re-import; a reprocess past the TTL is manual (`markStale` first).
- `_legacy` stays hot, never exported.
- S3 Standard only.
- `/all` (timeline) rehydrates every cold row it serves: a loan application runs the credit model
  dozens of times, so the rows must be warm again after the first read.
- Micrometer deferred.
- Line-delimited `row_to_json` blocks, not Parquet: rehydration stays `json_populate_record`
  and the library keeps knowing nothing about the consumer's columns.
- `ColdStorageRepository` grows range reads and multipart uploads as default methods.

## Operational notes

- **Bucket**: the objects of a partition-tiered table are the only copy of the swept rows, and
  they hold PII. The bucket must enforce default server-side encryption (SSE-S3 or SSE-KMS) and
  block public access; versioning or object lock is a consumer's choice (the library never
  overwrites a generation).
- **Release note (library)**: `ColdStorageRepository` gained abstract methods `loadRange`,
  `head`, `beginUpload` and `minimumPartSize`; a consumer with its own implementation must add
  them (the folder and in-memory storages show the contract).

## Implementation notes (2026-11-09)

What was built, and where it departs from the plan above:

- Classes: `model/` `ArchiveUnit`, `PartitionTieredTable`, `ShardStrategy`, `EquiDepthShardCutter`,
  `BlockCodec` + `GzipBlockCodec` + `ZstdBlockCodec`, `ColdUpload`, `BlockEntry`, `ShardIndex`,
  `PartitionManifest`, `PartitionArchive`, `PartitionArchiveState`, `LeafPartition`,
  `ShardExportRequest`; `repository/` `PartitionHotTierRepository`, `PartitionArchiveRepository`,
  `PartitionTieredRepository`, `ShardedBlockWriter`, `ShardedBlockReader`, range/head/upload
  defaults on `ColdStorageRepository` and the three storages, `findFirstHot`/`findAllHot` on
  `TieredRepository`; `tiering/` `PartitionTieringServiceComponent`, `TieringBatch.EXPORT` and
  `PARTITION_SWEEP`, `ExportProperties`/`SweepProperties`, the dispatch and the three new reads
  on `ColdTieringServiceComponent`, the registry checks, `ColdTieringSchema`'s record table and
  leaf-by-leaf index statements. Tests: `PartitionTieringServiceTest` (4 cases),
  `ShardedBlockFormatUnitTest` (2), on a partitioned `tiered_monthly_snapshot` the test
  configuration recreates after Hibernate's DDL.
- The shard strategy is an enum with a cutter, not an SPI: two strategies exist and nothing else
  is foreseen. `ShardIndex` carries `exportStartedAt`, which is what makes a redelivered shard
  request a no-op (the index object is compared, not object metadata, so the folder storage
  behaves like S3).
- Blocks are cut by an estimated compressed size (the previous block's ratio), not by compressing
  incrementally.
- `findAllByIdsTiered` does one hot lookup and, on a miss, one cold lookup per key; grouping the
  misses by block and coalescing adjacent blocks is future work (the validator's batch reads
  would benefit first).
- `readablePartitions` reads the record table and the catalog on every cold path (two small
  queries); a short record cache is future work if it shows on the cold read latency.
- `completeShard` appends the shard summary to a `shards` jsonb column under a per-shard guard
  (idempotent) and returns null when the record is no longer exporting, so a late or duplicate
  shard never finishes a partition twice.
- A partition whose rows changed under the export gets its manifest written and the record set
  `STALE` (readable, not sweepable) instead of failing: the next export run re-exports it once it
  is quiet again.
- The sweep's GET returns one chunk per step and the component keeps the cursor on the record
  (`sweep_blocks`, `sweep_cursor`, `sweep_deleted`, `sweep_kept`), so a run restarted from
  scratch resumes where it stopped.
- The registry creates `cold_tiering_partition_archive` with `create-schema=true` and refuses a
  partition table with `backfill.enabled=true`, a non-range partitioning, a partition column that
  is not temporal, or a primary key not led by the shard key.
- Review round (2026-11-10): exports write under generations with the pointer switched last;
  a reload of a swept partition with fewer hot rows is refused (`NEEDS_ATTENTION`); the shard
  record and the partition finish are one transaction and one final update; the sweep's first
  chunk re-counts coverage and the cutoff is `export_started_at`; `blocksFor` finds every block a
  prefix spans; frames are bounded by the index's `uncompressedLength`; zstd dropped (dependency
  approval); `ColdStorageRepository`'s range, head, upload and minimum part size are abstract;
  cold lookups moved behind `ColdLookupStrategy`; `timestamptz` partition columns rejected;
  `S3ColdStorageRepositoryTest` runs the S3 adapter against LocalStack (pinned by digest).
- Second review pass (2026-11-10): `date`/`timestamptz` partition columns rejected (`timestamp`
  only); any re-export of a swept partition needs `confirmReexport`; `delete` refuses rows of
  archived partitions; the sweep's coverage mismatch commits `STALE` without throwing; shard
  requests leave after the record's commit and an unseen generation is redelivered; the
  timeline merges by the full key; `partitionedEvictionIndexStatements` removed (the consumer's
  Flyway lists the leaf indexes); tests for the two data-loss guards, the schedule decision, the
  delete rule and the column-type rejection.
- Third review pass (2026-11-10): an upload completed with no part is stored as an empty object
  (S3 refuses a zero-part multipart completion), so an empty shard yields an index with no block
  and misses cost no range read; the row strategy refuses first-in-interval and timeline reads
  (`coldTiering.lookup.unsupported`) instead of answering hot-only; `findFirstCold` is strict
  against the hot candidate; the registry's validation writes nothing (the record table is
  created in `register` with `create-schema`); `verifyShard` is public for re-verification;
  tests cover the rewritten-row sweep guard, the verification failures, the empty shard and the
  resolved production defaults.
- Fourth review pass (2026-11-10): the shard key is restricted to digit-only text or non-negative
  integers (registration checks the type, the export the values), which is what makes the SQL
  order equal the canonical segment order under any collation. `COLLATE "C"` was **not** added to
  the shard queries on purpose: an expression collation cannot use the primary key index, and the
  per-shard pages and range counts of a 240 M-row partition would become sequential scans; the
  restriction gives the same guarantee without it. An aborted S3 upload after completion is a
  no-op, and a failed abort is logged, so the original export error is the one rethrown. The
  batch split by archive unit is enforced by the code, not by properties (a partition table's
  backfill may be enabled and scheduled in the properties; it never runs).
- Not built (no test exercises it): the registry's `require-leading-index` option named in the
  plan (the check always fails the boot), the QC endpoints (consumer side), Micrometer counters.

## Future work

- Glacier Instant Retrieval (or Intelligent-Tiering) lifecycle rule for partitions older than N
  months: range GET works there; retrievals cost US$ 0.03/GB, ~1 MB per cold read.
- A shared Redis level for manifests and shard indexes (`index-cache.cache-manager`).
- Micrometer counters (`cold.tiering.read{tier}`, `cold.tiering.export.rows`,
  `cold.tiering.sweep{result}`).
- A zstd `BlockCodec` (`zstd-jni`), once the dependency is approved; cleanup of superseded
  generations; a restart of exports stuck in `EXPORTING` past a timeout.
- A Bloom filter per shard (~300 KB at 10 bits per key) to make a miss inside a block cost no GET.
- Disk reclaim of swept partitions, if the replica replacement does not happen: `pg_repack` per
  partition, or the swap procedure of this plan's first draft (`git log` of this file).

## Open questions

1. **Sweep pacing in production**: the default 10 MB/s of WAL (≈ 35–40 h per big month) is a
   starting point; confirm against replica lag and the WAL archive during the first big sweep.
