# Cold storage tiering (hot Postgres table → S3 cold tier)

Status: step 1 implemented (row tiering, library-owned scheduling, repository fragment, in-memory and
S3 storage), 2026-11-08, branch `feature/cold-storage-tiering`, package
`org.coldis.library.service.tiering`. Steps 2 to 4 pending.

## Summary

A reusable feature of this library that turns any Postgres table whose rows are read by key into a
**sliding-TTL hot tier** in front of an **S3 cold tier** that keeps every row ever written. The
object key is **derived from the row's primary key**, so a cold read is one `GET` and a "newest on
or before" read is one `LIST` on a prefix: no index table. The object holds the **whole row**
(`row_to_json`), so rehydration (`json_populate_record`) needs nothing else. Rows are archived on
write (asynchronously, after the commit) and by a backfill batch that enqueues the keys to its own
queue, archived the same way; a row leaves Postgres only after
S3 confirmed its *current* content; a cold read rehydrates the row for another TTL window when the
table says so (`INSERT`), or serves it cold only (`NONE`, the log service). Two
eviction strategies: **row delete** (chunked, on the coldis `BatchService`) and **partition drop**
(leaf partitions of a range-partitioned table, dropped only when every row in them is archived).
The library owns the scheduling: every batch (backfill, eviction, later window export) runs on
`BatchService` inside the library with a cron per table; a consumer registers a table definition
and properties, and writes no batch component of its own.

Two kinds of tiering are distinguished (*Row and window tiering*): **row tiering** (one object per
row, random access by key, rehydrate; data-gathering) is v1 in full; **window tiering** (a time
window of rows exported as one file, read by scanning, partition dropped; the log service) is
specified here, shares most of v1's parts, and is built right after v1 as the log service's step.

It is the generalisation of data-gathering's Boa Vista online tiering
(`supersim-service-data-gathering-pom`, branch `feature/boa-vista-online-report-tiering`,
`doc/boa-vista-online-report-tiering.md`), which is copied into the library and parameterised. The
first consumers, in order: data-gathering's Boa Vista online reports (row delete), the log
service's conversion steps (partition drop), data-gathering's Boa Vista *full* reports
(`boa_vista_report_full*`, monthly partitions, ~240 M rows a month: partition drop).

## Goals / non-goals

Goals:
- One implementation of archive, touch, eviction, backfill and rehydration, configured per table
  by a small definition bean and properties. A consumer adds a table with a definition, two columns
  and properties, not with code copied from another service. Nothing in the library knows a
  consumer's columns: Boa Vista only supplies key columns, the version column, the clock and the
  rehydration.
- Every guarantee of the reference implementation kept: never evict unconfirmed content, idempotent
  re-uploads, rewrite-after-archive safe. Integrity in transit is the SDK's transfer checksums.
- All switches off by default; a consumer with none of the properties set sees no bean, no
  listener, no scheduled task, no S3 dependency on its classpath.
- Built on `BatchService`/`BatchExecutor` (cursor in `key_value`, one active run, Slack messages,
  resume after restart), not on a new scheduler.

Non-goals:
- The consumer's read API (latest/as-of endpoints), its DTOs and its `@HistoricalEntity` setup.
- Analytics over the cold tier (Parquet, Athena, DuckDB). The log service's Parquet export stays
  its own (see *Log service*); the library stores whatever bytes the codec hands it.
- A generic partition manager (creating partitions ahead). Only dropping leaf partitions.
- Migrating the consumers' data. The library ships code; each consumer's rollout is its own MR.

## Reference implementation (what gets generalised)

Data-gathering, branch `feature/boa-vista-online-report-tiering` (master has since dropped the
`_new` suffix and added Flyway; names below are the branch's):

| Piece | Where | Does |
|---|---|---|
| `BoaVistaOnlineTieringServiceComponent` | `component/person/finance/boavista/online/` | `saveCadastroPositivo` (touch on write, JPA save, upload request after commit); `archive` (lock row `FOR UPDATE`, `data::text` → sha256 → skip PUT if the index has the same sha → PUT → upsert index → `markArchived WHERE updated_at = read`); `@JmsListener upload` and `touch`; `findReport`/`findCadastroPositivo` (newer of index and hot; hot hit → touch if stale; miss → `GET`, digest check, native `INSERT … ON CONFLICT DO NOTHING` with the original timestamps) |
| `BoaVistaOnlineTieringBatchServiceComponent` | same | Hourly `@Scheduled` eviction (`BatchExecutor<Long>`, GET = `existsEvictable`, EXECUTE = `evictExpired(before, chunk)`); hourly backfill (`BatchExecutor<SnapshotKey>`, GET = `findNextUnarchivedKeys` after the cursor in PK order, EXECUTE = `archive`); bean name `boaVistaOnlineTieringBatchServiceComponent`, `BatchExecutor.withFixedRate`, `cleansWithin` 30 d |
| `BoaVistaOnlineSnapshotKey` | same | `record(table, taxId, version, cachedAt)`: JMS message and batch item |
| `BoaVistaOnlineReportColdRepository` / `BoaVistaOnlineReportS3Repository` | `repository/person/finance/boavista/` | `store(table, existingKey, payload) → StoredObject(key, sha256, sizeBytes)`, `load(key, expectedSha256)`, `digest`; gzip; key `<prefix><table>/<uuid[0:2]>/<uuid>.json.gz`; uses the batch mirror's `BoaVistaBatchObjectStorage` (`S3Client`, SDK v2 `software.amazon.awssdk:s3`, BOM 2.55.11) |
| `BoaVistaReportNewIndex` + `*IndexRepository` | `model/person/boavista/`, `repository/…` | JPA index entity: PK columns, `s3_key`, `sha256`, `size_bytes`, `row_created_at`, `row_updated_at`, `row_expired_at`, `archived_at` (+ `origin`); `findFirstBy…OrderByCachedAtDesc`, as-of variant, timeline |
| Native queries on the hot repositories | `BoaVistaReportNewRepository`, `…CadastroPositivo…` | `findForArchive` (`PESSIMISTIC_WRITE`), `findDataText`, `markArchived`, `touch` (`GREATEST`), `rehydrate`, `existsEvictable`, `evictExpired` (`ctid = ANY(ARRAY(SELECT ctid … LIMIT))`), `findNextUnarchivedKeys` (row-value cursor); `BoaVistaOnlineUnarchivedKey` projection |
| Entity columns | `BoaVistaReportNew`, `…CadastroPositivoReportNew` | `last_accessed_at`, `archived_at` (`@JsonIgnore`, `@DtoAttribute(ignore)`), `touchOnWrite` |
| `scripts/migration/boa_vista_online_tiering.sql` | | `ADD COLUMN`s, partial index `((COALESCE(last_accessed_at, updated_at))) WHERE archived_at IS NOT NULL`, index tables |
| `BoaVistaOnlineReportService` | `service/…/online` | Read endpoints; stays in the consumer |
| Tests | `BoaVistaCadastroPositivoReportNewMigrationClientTest` | write → archived; clock +20 d → `evict()` → row gone; read → rehydrated; clock +2 d read → touched; backfill; corrupted digest fails; `BoaVistaBatchInMemoryObjectStorage` (`@Primary`, `@Profile("!s3-integration")`) instead of S3 |
| Properties | `br.com.supersim.service.datagathering.boavista.online-tiering.*` | `enabled`, `ttl P15D`, `touch-threshold P1D`, `s3.prefix`, `upload/touch.concurrency`, `cold-read.enabled`, `eviction.{enabled,cron,chunk-size,delay-between-chunks,finish-within,safety-margin}`, `backfill.{enabled,cron,chunk-size,delay-between-chunks,finish-within}` |

Everything is duplicated per table (two `archive*`, two `read*`, two `find*`, two index
entities, two sets of native queries). That duplication is what the generic version removes. The
index table, the random object ids and the sha256 of the reference are dropped as well: the object
key is derived from the primary key, the object holds the whole row, and the SDK checks transfers.

## Log service

`supersim-service-log-pom`, `origin/master` (branch `feature/conversion-s3-tier-down`, merged;
design in `doc/CONVERSION_LOGS.md`, "Tier-down batch" and "Lifecycle of a day's partition"):

- `conversion_journey_step` is `PARTITION BY RANGE (journey_started_on)`, one partition per day,
  insert-only, raw retention `P30D` (per goal up to `P120D`). A day's partition is *final* once
  older than every goal's window.
- `ConversionLogTierDownServiceComponent.tierDown()` (`@Scheduled`, **not** on `BatchService`
  although the doc planned it): lists the leaf partitions from `pg_inherits`, and for each expired
  day, in `REQUIRES_NEW` under `pg_try_advisory_xact_lock('conversion_journey_step_tier_down')`:
  per goal past its retention, stream the rows `ORDER BY journey_id, occurred_at` to a temp
  Parquet file (`ConversionJourneyStepParquetWriter`, parquet-java), `PUT`
  `conversion-logs/goal=<goal>/date=<day>/part-0.parquet` (`ConversionJourneyStepS3Repository`,
  `HEAD` size check, refuses an empty export), `DELETE` that goal's rows of the day; then, if the
  day is empty, `DROP TABLE <partition>` (name validated by regex). A failure keeps the day for the
  next run; a re-export overwrites the same key.
- No index table, no rehydration, no touch: an exported row is only reachable through DuckDB on S3.
  Retention in S3 is a lifecycle rule per goal prefix.
- Tests: LocalStack `localstack/localstack:4.4.0` (`AbstractLogTest.LOCALSTACK_CONTAINER`, port
  4566, `SERVICES=s3`) and the real SDK v2 `S3Client` from `integration/s3/S3Configuration`.

What it shares with the generic design: discover leaf partitions, archive, confirm, drop only when
nothing unarchived is left, serialise runs, retry a day. What differs: its archive unit is a
(partition, goal) group as one Parquet object, not a row; and it needs no index, touch or
rehydration. The generic design therefore makes **archive unit**, **rehydration** and **touch**
extension points with a "none" option (below), and the log service plugs in with a partition-unit
codec in a later step (*Migration path*, step 3). Its advisory lock becomes `BatchService`'s own
batch lock.

## Design

### Shape

```
consumer                                library (org.coldis.library.service.tiering)
────────                                ─────────────────────────────────────────────
TieredTable bean per hot table ───────► TieredTableRegistry (validates against information_schema and
                                          the properties; create-schema for tests)
JPA save of the hot row, then           ColdTieringServiceComponent (policy only)
  tiering.afterWrite(key) ────────────►   afterWrite (table enabled?) → HotTierRepository.afterWrite,
                                           upload request after commit (JMS cold-tiering/upload)
                                          upload and backfill listeners → archive(key): lockForArchive, store,
                                           markArchived at the uploaded watermark
                                          touch listener → HotTierRepository.touch
tiering.findByIdTiered(Entity.class, id)  hot? touch if stale : cold-read and INSERT? load + rehydrate
tiering.findColdRow(Entity.class, id)     cold-read enabled? loadCold
tiering.findNewestTiered(Entity.class,    newest hot vs newest cold key; cold wins → rehydrate (INSERT)
  prefix, asOf) → TieredHit                or serve the ColdRow (NONE)
tiering.delete(key)                       both tiers
                                        ColdTieringBatchServiceComponent (BatchService, CronTrigger per
                                          table and batch): backfill (enqueues keys to
                                          cold-tiering/backfill), eviction
                                        ── repository package (access only, no policy, no component) ──
                                        TieredRepository: keyOf, objectKeyOf, findHot / findNewestHot
                                          (JPQL), loadCold (GET + decode), findNewestColdKey (LIST +
                                          pick), rehydrate (key check + json_populate_record), delete
                                        HotTierRepository (JDBC, SQL generated from TieredTable)
                                        ColdStorageRepository: S3 (consumer's S3Client) | folder | memory
```

**Split by nature, three packages, one direction.** `tiering` (policy: the components, the
properties, the registry, the schema helper, `TieredHit`, `TieringBatch`) → `tiering.repository`
(access: `HotTierRepository`, `TieredRepository`, `ColdStorageRepository` and its S3, folder and
in-memory stores) → `tiering.model` (`TieredTable`, `TieredRowKey`, `ColdRow`, `ColdObject`,
`TtlClock`, `Rehydration`, `ColumnValues`, `TieredEntityKeys`, the `PayloadCodec` and
`ObjectKeyStrategy` SPIs with their defaults, `ColdTieringPropertyNames`). Nothing in a lower
package imports a higher one: repositories read what they need from the definition (the column
types the registry binds at startup) and from `@Value` properties (object key prefix, folder
root, storage selection through `ColdTieringPropertyNames`), never from `ColdTieringProperties`,
the registry, a component or `StaticContextAccessor`; `TieredRepository` composes the other
repositories. A first data access never triggers registration or DDL. The component holds the policy: table enabled, cold reads enabled,
rehydration per table, touch threshold, archive and eviction orchestration, the queues. The consumer-side repository fragment of the first cut (default methods reaching the
component through `StaticContextAccessor`) is gone for that reason: a consumer's repository stays a
plain JPA repository and the tiered reads are on the component, by entity class.

Data access is JDBC (`NamedParameterJdbcTemplate`) with SQL generated from the definition: the
library cannot know the consumer's entities, and every statement must stay native (no watermark
bump, no history listener). Table and column names are validated against `^[a-z_][a-z0-9_]*$`
before any SQL or DDL. The consumer keeps JPA for its own writes and reads.

### Object keys (no index table)

`ObjectKeyStrategy` (default `PathObjectKeyStrategy`):

```
<prefix><tableId>/<segment 1>/.../<segment n-1>/<segment n><extension>
  segment i = canonical form of key column i (ColumnValues.toSegment):
    text          URL-encoded (never contains "/"; order not kept, so never a version column)
    bigint/int    %019d (non-negative values keep their order)
    timestamp     yyyy-MM-ddTHH:mm:ss.SSSSSS (fixed width; timestamptz in UTC with a trailing Z)
    date, uuid    ISO
  extension     = the codec's (".json.gz")
e.g. cold-tiering/tiered-snapshot/owner-1/2026-01-10T12:00:00.000000.json.gz
     cold-tiering/tiered-archive-entry/0000000000000000042.json.gz
```

The last key column is the file name. A table declared `versioned()` (its last key column is a
time or version) gets "newest on or before X" reads: `LIST` the prefix of the other key columns
(every page), keep the greatest file name `<= canonical(X)`; fixed-width segments make the string
order the value order. Unversioned tables get by-key reads only. The strategy may transform
segments (keyed HMAC of the business ids, say) as long as `lastSegmentOf` gives the file name
back; not built in v1. The window tiering of step 3 uses the same idea (window and group as
segments).

### Payload: the whole row

`archive` reads `CAST(row_to_json(hot) AS text)` under `FOR UPDATE`; `PayloadCodec` (default
`GzipJsonPayloadCodec`) encodes that text. Rehydration is
`INSERT INTO hot SELECT * FROM json_populate_record(NULL::hot, :json) ON CONFLICT DO NOTHING`,
then `archived_at = now` (and `last_accessed_at = now`): every column comes back as archived, the
watermark included (no re-export by the data pipeline, no history row), and the library never
lists the consumer's columns. Object metadata: `table`, `updated-at` (the watermark when archived),
`content-type`.

### Extension points

| Extension point | Interface / setting | v1 | Later |
|---|---|---|---|
| Table definition | `TieredTable(id, tableName, keyColumns)` bean; `withWatermarkColumn` (`updated_at`), `withLastAccessedColumn`, `withArchivedAtColumn`, `withVersioned()`, `withTtlClock(clock, column)`, `withRehydration`, `withEntity(class, keyProperties)`; the registry binds the column types on it | yes | `fromEntity` from the JPA metamodel |
| Row key | `TieredRowKey(tableId, Map<String, String> values)`: JMS message, batch item, cursor; values as strings, bound with the column types read from `information_schema`. A single-column primary key is a one-entry map | yes | — |
| Retrieval | by full key (`readThrough`, `readCold`); by key prefix + "newest on or before" (`findNewestCold`), versioned tables only | both | — |
| TTL clock | `TtlClock.LAST_ACCESS` (`COALESCE(last_accessed_at, updated_at)`, reads touch), `LAST_WRITE` (`updated_at`, no touch column), `COLUMN` (any timestamp column) | yes | — |
| Rehydration | per table, `Rehydration.INSERT` or `NONE`, definition default overridden by `table.<id>.rehydration`; `INSERT` needs the `LAST_ACCESS` clock (a rehydrated row must be able to expire by access), rejected otherwise at registration | yes | — |
| Payload codec | `PayloadCodec` (`encode`, `decode`, `extension`, `contentType`); `payload-codec=gzip-json` selects the default; a consumer sets the property to another name and declares its own bean | yes | Parquet for window tiering |
| Object key layout | `ObjectKeyStrategy` (`keyOf`, `prefixOf`, `lastSegmentOf`), on the `ColdTable` interface (id, table name, key columns) that `TieredTable` implements and a window table will, so a custom layout never depends on row-only definition parts; `object-key-strategy=path` selects the default layout above; a consumer sets the property to another name and declares its own bean | yes | hashed segments |
| Cold storage | `ColdStorageRepository` (`store`, `load` → empty on 404, `list` every page, `delete`): `S3ColdStorageRepository` on the consumer's `S3Client` bean (production; the one named `coldTieringS3Client` when the consumer has several, else the unique or `@Primary` one); `FolderColdStorageRepository` (local folder: key → relative path, atomic write through a temporary file and a move, metadata in a `.meta` sidecar, lexicographic listing) and `InMemoryColdStorageRepository`, both library-provided test and local-run support, kept on purpose | yes | — |
| Scheduling | `ColdTieringBatchServiceComponent`: one `CronTrigger` per table and batch on the `TaskScheduler`, `-` disables, `run(tableId, batch)` on demand, `schedule`/`unschedule`/`cancelAll` and `getScheduled()` | yes | export batch (window) |
| Tiered reads | on the component, by entity class: `findByIdTiered`, `findColdRow`, `findNewestTiered` → `TieredHit` (hot entity or cold row); `delete(key)` removes both tiers | yes | — |

### Row and window tiering

| | Row tiering (v1, built) | Window tiering (step 3) |
|---|---|---|
| Consumer | Boa Vista online, Boa Vista full | Log service conversion steps |
| Archive unit | one row → one object | one leaf partition (× optional group, e.g. `goal`) → one file |
| Trigger | write-through after commit + backfill batch | export batch over *final* windows |
| Object key | `<table>/<key segments>` | `<table>/<group>/<window>` through the same `ObjectKeyStrategy` |
| Payload | the whole row, `PayloadCodec` | the window's rows streamed through a `WindowExporter` (Parquet stays the consumer's) |
| Read | by key; newest on or before; rehydrate or cold only | none in the library (DuckDB scans the files) |
| Eviction | `ROW_DELETE` (v1) or `PARTITION_DROP` (step 4) | `PARTITION_DROP` in the same chunk (export → confirm → drop) |
| Hot-table columns | `archived_at`; `last_accessed_at` only with `LAST_ACCESS` | none (a window is final by time) |

Shared: registry and validation, storage, key strategy, partition drop, batch scheduling,
properties. Not worth building: a Parquet writer in the library, scan reads of windows, a range
`DELETE` fallback for unpartitioned window tables (that is row tiering with `COLUMN` clock),
creating partitions ahead, re-attaching dropped partitions, a transparent `findById` override.

### Tiered reads

On `ColdTieringServiceComponent`, by entity class bound with `withEntity`: `findByIdTiered(Entity.class,
id)` (hot, touching when stale; else `GET` + rehydrate on an `INSERT` table; a `NONE` table misses).
Key values given as the Java types the entity maps the columns with (a `timestamptz` column as
`LocalDateTime`, `Instant` or `ZonedDateTime`) are converted to the column's type and back for the
JPQL parameters, so an entity's mapping choice does not matter.
`findColdRow(Entity.class, id)`, `findNewestTiered(Entity.class, keyPrefix, asOf)` returning a
`TieredHit` (the hot entity, or the cold row on a `NONE` table: never empty when an answer exists).
The key comes from the id (the `@IdClass` fields named by `keyProperties`, validated at
registration, or the id itself for a single key column); the hot candidate is a JPQL over the key
properties (`TieredRepository.findHot`/`findNewestHot`). The consumer's own `findById` never reaches
the cold tier: a locked read or an internal lookup must never cost a `GET`.

### Library vs consumer split

| Concern | Library | Consumer |
|---|---|---|
| Table definition, validation | registry | one `TieredTable` bean per table |
| Hot-table lifecycle columns | SQL that reads/writes them, DDL generator | maps `lastAccessedAt`/`archivedAt` on the entity (`@JsonIgnore`), runs the DDL in its migrations |
| Native SQL (lock, `row_to_json`, mark, touch, rehydrate, evict, cursor) | generated | none |
| Cold storage | S3 repository, in-memory repository, codec, key layout | `S3Client` bean, bucket, prefix; a custom codec or layout only when needed |
| Write-through, touch, listeners | yes | calls `afterWrite(key)` after its JPA save |
| Reads | `findByIdTiered`, `findColdRow`, `findNewestTiered`, `readCold`, `delete` | endpoints, DTOs, lookups beyond key and prefix; a business delete calls `delete(key)` so the cold copy goes too |
| Batches and schedules | backfill, eviction, triggers, chunking, Slack | crons, chunk sizes, flags in properties |
| Monitoring | log lines | dashboards, alarms, DLQ |
| Data migration (backfill, verify, truncate, rollout) | the backfill batch | the rollout plan |

### Schema

The consumer owns the DDL (Flyway). `ColdTieringSchema.statements(table, watermarkType, concurrently)` returns (the added columns take the watermark column's type, so the eviction index's `COALESCE` never mixes `timestamp` and `timestamptz`)
it; `create-schema=true` runs it at startup (tests; `IF NOT EXISTS` only):

Flyway and `CREATE INDEX CONCURRENTLY`: the eviction index must be built outside a transaction.
Put it in its own migration with a `V<n>__….sql.conf` holding `executeInTransaction=false`, and set
`spring.flyway.postgresql.transactional-lock=false`: Flyway's default transactional advisory lock
keeps a transaction open on its lock connection, and the concurrent build waits on it until the
lock timeout (found by the first consumer, data-gathering).

```sql
ALTER TABLE <hot> ADD COLUMN IF NOT EXISTS last_accessed_at TIMESTAMPTZ,   -- LAST_ACCESS only
                  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
CREATE INDEX CONCURRENTLY IF NOT EXISTS <hot>_cold_evict_idx
  ON <hot> ((<clock expression>)) WHERE archived_at IS NOT NULL;
```

The registry reads the hot table's column types from `information_schema` at startup, validates
the definition (key, watermark, lifecycle and clock columns present; watermark `NOT NULL`; key
types supported; a versioned table's last column temporal — date, timestamp, timestamptz — so an
as-of moment converts to it; `INSERT` only with `LAST_ACCESS`; key properties present on the
entity and its `@IdClass`) and fails the boot otherwise.

### Write, archive, read, touch, eviction, backfill

- **Write**: the consumer's JPA save, then `afterWrite(key)` in the same transaction (the library flushes the persistence context first, so its update applies to the row just written and the consumer's flush cannot overwrite it with stale lifecycle values):
  `UPDATE hot SET archived_at = NULL[, last_accessed_at = now] WHERE key`, and the key posted to
  `cold-tiering/upload` after the commit.
- **Archive** (`archive(key)`, the upload listener for write-through requests and the backfill
  listener for backfill requests, one message per row, each in its own `REQUIRED` transaction): `SELECT updated_at,
  row_to_json(hot), archived_at >= updated_at … FOR UPDATE` (missing, or its current content
  already archived → nothing to do); encode; `PUT` to the derived key; `UPDATE hot SET archived_at = :watermark WHERE key AND updated_at = :watermark`,
  the watermark being the value read and uploaded, not the wall clock (0 rows → the rewrite's own
  message archives the new content). Failures throw: broker retry, DLQ.
- **Read**: hot hit → touch request when the clock is older than `touch-threshold`; miss and
  `INSERT` → `GET`, the decoded row's key columns checked against the requested key,
  `json_populate_record`, `archived_at = updated_at`, hot lookup again; miss and `NONE` →
  `findColdRow` serves the `ColdRow`. No object → empty, never an error.
- **Touch**: `UPDATE hot SET last_accessed_at = GREATEST(<clock>, now) WHERE key`.
- **Eviction** (`BatchExecutor<EvictionChunk>`, one chunk per transaction): GET =
  `existsEvictable(now - ttl - safety-margin)`; EXECUTE = `DELETE … WHERE (key) IN (SELECT key …
  WHERE <evictable> LIMIT :chunk FOR UPDATE SKIP LOCKED) AND <evictable>`, with `<evictable>` =
  `archived_at IS NOT NULL AND archived_at >= updated_at AND <clock> < :before`: a row being
  archived or rewritten is skipped, and one rewritten between the pick and the delete
  (EvalPlanQual re-check) is kept. Works on partitioned tables (keys, not `ctid`).
- **Backfill** (`BatchExecutor<TieredRowKey>`, *queued*): GET = keys after the cursor with
  `archived_at IS NULL OR archived_at < updated_at`, row-value comparison on the key in key order;
  EXECUTE = `enqueueKey`, which sends the key to `cold-tiering/backfill`, a queue of its own so
  write-through uploads (`cold-tiering/upload`) never wait behind a backfill. The send is not
  deferred to a commit callback (it is not tied to a business write; a session-transacted JMS
  template still releases it at its transaction's commit). The backfill listener archives it, one
  row per transaction, so a row's lock covers only its own upload and the batch chunk holds no row
  lock.
  The keyset cursor never selects again a key enqueued earlier in the run, archived yet or not.
  Resumes from the batch record after a restart; a run restarted from scratch (expired, or the
  record cleaned) re-enqueues the keys not archived yet, which is harmless: a duplicate request
  finds the content archived and uploads nothing. A run's FINISH therefore means "every key
  enqueued", not "every row archived": the backfill queue may still hold keys, and the row's
  `archived_at` (or the next run's empty GET) is what confirms the archive. Throughput is set by
  the consumers (`backfill.concurrency`), the enqueue rate by `chunk-size` / `delay-between-chunks` (*Sizing the
  backfill*).
- **Scheduling**: at startup, for every registered table and each batch whose cron is not `-`,
  a `CronTrigger` task on the `TaskScheduler` calls `batchService.start(executor, false, false)`
  (`withFixedRate`, key `cold-tiering-<batch>-<tableId>`, `cleansWithin` 30 d). Every instance
  schedules; `BatchService` keeps one run active: a start while a run is active (not finished,
  not past `finish-within`) keeps its cursor and count, and the extra resume it queues is dropped
  (early or concurrent), so the hourly crons only (re)start a run when none is active. A run may
  last up to `finish-within` (`P1D` by default). No scheduler bean → a warning, `run` only.

### Concurrency and integrity

| Guarantee | Mechanism |
|---|---|
| Concurrent archives of one key never write older content over newer | Keys are deterministic, so every archive of a row writes the same object; `archive` reads the row `FOR UPDATE` and uploads while holding the lock, so archives of one key run one at a time and each uploads the content current at its turn. A writer rewriting the row waits for the upload in flight, then its own message uploads the new content. **Trade-off**: holding the row lock across the S3 `PUT` is what rules out an older-over-newer overwrite without a version in the key; its cost is that writers to that row wait on S3 latency (plus the row's own upload after theirs). Mitigation: the consumer's `S3Client` call and attempt timeouts well below `long-timeout`; the lock covers one row, so unrelated writers never wait. Revisit with S3 conditional writes (`If-Match` / `If-None-Match` on the object's ETag, the uploaded watermark in the metadata) if row-level write contention shows up; then the PUT could leave the lock |
| A rewrite during an upload is never marked archived | `archived_at` set to the uploaded watermark, only `WHERE updated_at = :watermark`; a concurrent rewrite with an earlier timestamp, or clock skew between instances, cannot satisfy `archived_at >= updated_at` with a mark that is not its own watermark |
| A row is never evicted before S3 holds its current content | `archived_at >= updated_at` in the eviction predicate, repeated on the delete itself. It covers every write that bumps the watermark, `afterWrite` or not; a write that leaves the watermark unchanged is outside the contract (the watermark is "bumped by every write" by definition) |
| Duplicate upload or touch messages (a backfill run re-enqueueing keys, a key written while the backfill enqueues it) | the archive, under the row lock, skips a row whose current content is already archived (`archived_at >= updated_at`): nothing uploaded, the mark unchanged; a request for content not yet archived uploads it once, the next one finds it archived; `GREATEST` makes the touch a no-op |
| Crash between PUT and mark | redelivery repeats the PUT (idempotent) |
| Integrity in transit | the SDK's transfer checksums on PUT and GET; no digest in the database |
| Two instances rehydrate the same key | `INSERT … ON CONFLICT DO NOTHING` |
| S3 outage | hot reads unaffected; cold reads throw; uploads retry to the DLQ; eviction only touches archived rows |
| TTL lowered | expiry is computed, applies at the next run |

### Dependency and wiring

`software.amazon.awssdk:s3` is `<optional>` in this pom (`aws.sdk.version`, 2.55.11); the
consumer declares the SDK and exposes an `S3Client` bean (unique; or, among several, one named `coldTieringS3Client` — no need to make it `@Primary` and take over the consumer's other clients);
`ColdTieringS3Configuration` is `@ConditionalOnClass(S3Client.class)`. The upload runs under the
hot row's `FOR UPDATE` lock inside the `long-timeout` transaction, so the consumer's `S3Client`
must have call and attempt timeouts well below that (a hung PUT otherwise holds the row lock for
the whole transaction timeout). Every bean is guarded by
`org.coldis.configuration.service.cold-tiering-enabled` (default off, like `batch-enabled`); the
batch component also needs `batch-enabled`. Defaults ship in `service.properties`.

### Configuration

Global, `org.coldis.library.service.tiering.`:

| Property | Default | Meaning |
|---|---|---|
| `org.coldis.configuration.service.cold-tiering-enabled` | `false` | Creates the beans, listeners and schedules |
| `storage` | `s3` | `s3`, `folder` (local runs and tests) or `memory` (tests) |
| `payload-codec` / `object-key-strategy` | `gzip-json` / `path` | Which codec and layout bean is active; another value with a consumer bean plugs a custom one |
| `folder.root` | `${java.io.tmpdir}/cold-tiering` | Root directory of the folder storage |
| `s3.bucket` | — | The bucket; the client is the consumer's `S3Client` bean named `coldTieringS3Client` when present, else its unique (or `@Primary`) one |
| `prefix` | `cold-tiering/` | Key prefix |
| `create-schema` | `false` | Run the DDL at startup (tests) |
| `upload.concurrency` / `backfill.concurrency` / `touch.concurrency` | `1` / `1` / `1-2` | JMS listeners per instance: write-through uploads (`cold-tiering/upload`), backfill archives (`cold-tiering/backfill`, they set the backfill's throughput), touches |
| `defaults.*` | below | Values every table inherits |

Per table, `defaults.` or `table.<tableId>.` (the bean gives the structure, the properties the
tunables):

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Write-through, touches, backfill and eviction |
| `ttl` / `touch-threshold` / `safety-margin` | `P15D` / `P1D` / `P2D` | Hot window; touch rate limit; extra time before eviction |
| `cold-read-enabled` | `false` | Cold reads (and rehydration) |
| `rehydration` | the definition's | `INSERT` or `NONE` |
| `eviction.{enabled,cron,chunk-size,delay-between-chunks,finish-within}` | `false`, `0 0 * * * *`, `2000`, `PT2S`, `P1D` | `-` disables the cron; the cron (re)starts a run only when none is active |
| `backfill.{enabled,cron,chunk-size,delay-between-chunks,finish-within}` | `false`, `0 30 * * * *`, `1000`, `PT30S`, `P1D` | keys enqueued per chunk and the pause between chunks (*Sizing the backfill*); the cron (re)starts a run only when none is active |

#### Sizing the backfill

The backfill only enqueues; the backfill listeners archive. Match the enqueue rate to what the
consumers drain:

`chunk-size / delay-between-chunks ≈ consumers × 1 / per-row latency`

with consumers = `backfill.concurrency` × instances and the per-row latency that of one archive
(lock, `row_to_json`, gzip, `PUT`, mark: ~30 ms in production). Enqueueing faster only grows the
queue, which is bounded by what the broker holds (a backlog of 1 M small messages is fine), not by
the database: no row is locked while its key waits. The defaults suit one consumer: `1000` keys
every `PT30S` ≈ 33 keys/s ≈ one consumer at ~30 ms per row, ~2.9 M rows a day, inside the `P1D`
`finish-within` (a longer backfill resumes in the next run). A high-volume consumer raises both,
per table, e.g.:

```properties
org.coldis.library.service.tiering.backfill.concurrency=8-16
org.coldis.library.service.tiering.table.<tableId>.backfill.chunk-size=5000
org.coldis.library.service.tiering.table.<tableId>.backfill.delay-between-chunks=PT5S
```

≈ 1,000 keys/s enqueued, drained by 16 listeners per instance across a few instances at ~30 ms
per row. Raise `backfill.concurrency` together with the backfill rate: a rate above what the
consumers drain only grows the backlog. Write-through uploads have their own queue and consumers,
so a backfill backlog never delays them. Alert on the depth and the oldest message's age of
`cold-tiering/backfill` and `cold-tiering/upload` (a growing backfill queue means the rate
outpaces the consumers; a growing upload queue means write-through lags, and eviction waits for
it).

### Observability

`[COLD_TIERING]` (registration, scheduling), `[COLD_TIERING_UPLOAD]`, `[COLD_TIERING_COLD_READ]`,
`[COLD_TIERING_EVICT] table chunk deleted` (info), the rest at debug; the batch service's own
messages per batch key name the table and the counts only. No key value reaches a log:
`TieredRowKey.toString()` shows the table and the number of columns, the batch message templates
carry no `${lastProcessed}`. Micrometer counters are not built yet (open question).

## Testing

`ColdTieringServiceTest` (`src/test/java/org/coldis/library/test/service/tiering/`), on
`ContainerTestHelper` (Postgres, Redis, Artemis), one Spring context, no Mockito, `storage=folder` under
`java.io.tmpdir` (the file, its `.meta` sidecar and the listing are asserted),
`create-schema=true`, every cron `-` (a random folder per run). Two consumer-side entities in the
test package, `TieredSnapshot` (`@IdClass` owner + `cached_at`, JSONB payload, `LAST_ACCESS`,
versioned, `INSERT`) and `TieredArchiveEntry` (single bigint id, text payload, `LAST_WRITE`,
`INSERT` in the bean overridden to `NONE` by property), with plain JPA repositories; the archive
table is also registered twice more, one definition disabled and one with cold reads off, by
property. Cases: write → archived at the derived key (`archived_at = updated_at`) → clock +20 d →
eviction deletes it and keeps a never-archived row and one rewritten without the hook (no cold
object for the former) → `findByIdTiered` rehydrates with the original `updated_at` → a read past
the threshold touches → object removed → empty; a rewrite after the archive is re-archived before
eviction, and `delete` clears both tiers; the backfill enqueues seeded rows in chunks, each key
once, and the backfill listener archives them all, as it does a key enqueued through the
deprecated `archiveKey` delegate (runs persisted before the upgrade); a duplicate archive request of an archived row
uploads nothing (a sentinel object stays in place, the mark is unchanged); a backfill start while a
run is active (slowed by a runtime table override) keeps its start, cursor and count;
`findNewestTiered` over hot and cold snapshots (newest, as-of, before the first, unknown owner);
the `NONE` table: archived, evicted, `findByIdTiered` empty, `findColdRow` holds the row, hot
table stays empty, `schedule` registers a cron, `-` registers nothing and `unschedule` forgets it;
the disabled definition archives nothing while a later write through the enabled one does, `run`
is false and a cold object is never read; cold reads off: archived, never read; `TieredEvent`
(`timestamptz` version column mapped as `LocalDateTime`, `COLUMN` clock, `NONE`): aged by the
column (old evicted, recent kept, then evicted after the clock moves), cold-only `findColdRow` and
`findNewestTiered` serving the cold row. `ColumnValuesUnitTest` (plain JUnit) covers the segment
round trip and order per type (text with `/`, `..`, spaces, non-ASCII; integers; timestamp;
timestamptz; date), the conversions, the path layout and the folder storage's dot-only segments.

`S3ColdStorageRepository` itself has no test in this library (no LocalStack): it is covered by the
first consumer's tests, the folder storage exercises the same interface contract.

## Migration path

**Step 1 — library** (done): see *Design*. Mapping from the reference:

| Reference | Library |
|---|---|
| `BoaVistaOnlineSnapshotKey` | `TieredRowKey` |
| `BoaVistaOnlineReportColdRepository`, `*S3Repository`, `BoaVistaBatchObjectStorage` | `ColdStorageRepository`, `S3ColdStorageRepository` (consumer's `S3Client`), `InMemoryColdStorageRepository`, `GzipJsonPayloadCodec`, `PathObjectKeyStrategy` |
| `BoaVista*Index` entities and repositories, `s3_key`/`sha256`/`size_bytes` | dropped: derived keys, whole-row objects, SDK checksums |
| Native queries on the hot repositories, `BoaVistaOnlineUnarchivedKey` | `HotTierRepository` |
| `BoaVistaOnlineTieringServiceComponent` | `ColdTieringServiceComponent` (policy) + `TieredRepository` (access) |
| `BoaVistaOnlineTieringBatchServiceComponent` (`@Scheduled`) | `ColdTieringBatchServiceComponent` (crons per table) |
| `findReport` / `findCadastroPositivo` | `ColdTieringServiceComponent.findNewestTiered` / `findByIdTiered` |
| Entity `touchOnWrite` | `afterWrite(key)` |
| `boa_vista_online_tiering.sql` | `ColdTieringSchema.statements` in the consumer's Flyway migration |
| `…boavista.online-tiering.*` | `org.coldis.library.service.tiering.*` |

**Step 2 — data-gathering**: bump the library; two beans (`boa-vista-report`: key `tax_id,
version, cached_at`, versioned; `boa-vista-cadastro-positivo-report`: key `tax_id, cached_at`,
versioned; both `LAST_ACCESS`, `INSERT`); Flyway migration from the schema helper (drop the
`*_index` tables of the branch); `upsertReport` calls `afterWrite`; `BoaVistaOnlineReportService`
on the component's `findNewestTiered` / `findByIdTiered`; business deletes through `delete(key)`; delete the two components, the index entities, the
cold/S3 repositories and the tiering native queries; the batch mirror's `S3Client` exposed as a
bean for `s3.client-bean`; flags off; rollout as the reference doc (backfill, verify, `TRUNCATE`,
eviction). Object keys carry the tax id as a path segment: see open question 1.

**Step 3 — window tiering and the log service**: `WindowTieredTable`, the export batch,
`WindowExporter`; the log service registers `conversion_journey_step` with its Parquet writer,
key layout `goal=<goal>/date=<day>/part-0.parquet` kept, rehydration none; its
`ConversionLogTierDownServiceComponent` and advisory lock go away.

**Step 4 — Boa Vista full**: `PARTITION_DROP` eviction (detach concurrently, drop only when no
row in the partition is unarchived or recently accessed; rehydration into a default partition
evicted by row delete), then the two full tables as row-tiered, versioned, `INSERT`.

## Decisions

- No index table, object keys derived from the primary key, whole-row objects, the SDK's
  checksums for integrity (2026-11-08).
- Business ids in object keys (the tax id as a path segment) are accepted for now; a keyed-HMAC
  segment layout is a later improvement through `ObjectKeyStrategy`, not v1.
- The S3 client is the consumer's bean (unique or `@Primary`); the library builds none.
- No LocalStack in this library: the tests run on the folder storage; the S3 adapter is covered
  by the first consumer's tests.
- Rehydration is per table (`INSERT` / `NONE`), the log service uses `NONE`.
- Table structure in a bean, tunables in properties; access logic in the repository package, policy
  in the component, reads on the component by entity class (no repository fragment).
- The backfill enqueues keys to its own queue (`cold-tiering/backfill`) instead of archiving inline in the chunk's
  transaction: inline archiving ran one row at a time (~26 rows/s in production) and held every
  row lock of a chunk until it committed; queued, the listeners set the throughput and each lock
  covers one row (2026-11-09).

## Open questions

1. **`LIST` cost of "newest on or before"** on keys with many versions (one Boa Vista CPF has at
   most a few hundred snapshots: one page). A `start-after` optimisation from the ceiling is
   possible later.
2. **Micrometer metrics** (`cold.tiering.read{tier}`, `upload{result}`, `evicted`,
   `unarchived.rows`): names proposed, not built.
3. **Negative integer keys** break the fixed-width ordering of the default layout; versioned tables
   are restricted to temporal last columns, so it only matters for listing-free tables (cosmetic).
4. **Default partition for `PARTITION_DROP` with rehydration** (step 4): add one to
   `boa_vista_report_full`, or rehydrate into a separate table.
5. **Log service move (step 3)**: worth it, or does only the row tiering move to the library?
