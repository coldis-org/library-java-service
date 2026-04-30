# Event Batch Upsert — Work In Progress

Plan and progress for moving `StatisticsEvent` upserts off the synchronous DB
write path, mirroring (but not identical to) the summary delta buffering
pattern. Goal: reduce DB pressure from individual event upserts by buffering
in-memory and flushing as JMS-delivered batches that lock + persist multiple
rows per transaction.

The summary path (`StatisticsEventSummaryServiceComponent`) is **out of scope**
and stays as-is.

## Decisions

- **Async upsert.** `upsertStatisticsEvent` becomes `void`. It validates and
  truncates synchronously (so callers still get input errors immediately) and
  pushes the event into an in-memory `BufferedReducer`.
- **Read-your-writes within the flush window is acceptable.** A caller that
  reads back an event right after upserting may not see it until the buffer
  flushes (default cron `0 * * * * *` — 1 minute).
- **Delete stays synchronous.** `deleteStatisticsEvent` is rare and keeping it
  sync avoids ordering complexity vs. concurrent buffered upserts.
- **Per-batch JMS message.** Each flush drains the buffer, splits into chunks
  of `org.coldis.library.service.statistics.event.buffer.batch-size` (default
  100), and sends each chunk as a `List<StatisticsEvent>` to
  `statistics-event/upsert/batch`.
- **Listener concurrency starts at 1**, configurable via
  `org.coldis.library.service.statistics.event.buffer.processupsertbatch.concurrency`.
- **Order preservation via `emittedAt`.** New nullable column on
  `StatisticsEvent`. Caller may set it; if null, the buffer-entry path defaults
  it to `now()`. The getter falls back to `updatedAt` when null (covers legacy
  rows pre-dating the column).
  - `StatisticsEvent.reduce(...)` keeps the entry with the larger `emittedAt`
    (latest-emission-wins).
  - The listener drops any incoming event whose `emittedAt` is older than the
    persisted row's — handles JMS reordering / retries / multi-instance races.
- **DDL via `ddl-auto`.** No migration needed.
- **Batch lock for the write path.** New repo query `findAllByKeysForUpdate`
  using JPQL row-value `IN` + `ORDER BY` (deterministic lock order to prevent
  deadlocks) with `@Lock(PESSIMISTIC_WRITE)`. Locks every existing row in one
  round-trip. Pessimistic write locks work fine on multi-row result sets.
- **Cross-instance race for new keys.** `findAllByKeysForUpdate` only locks
  rows that already exist; for keys with no row yet, two instances could race
  on insert. The original `findOrCreate` (try `create` in `REQUIRES_NEW`,
  fall back to `findByIdForUpdate`) handles this. Listener uses
  `findOrCreate` for keys missing from the batch lock result.
  - **Open question (paused here):** how to compute the correct summary delta
    when `findOrCreate` returns a row that was inserted by another instance
    between our batch lock and our `create` attempt. In that case the
    concurrent row's state should be the "old state" for delta purposes, but
    we can't tell from `findOrCreate`'s current return type whether it
    created or found-after-conflict. A return-flag variant was tried and
    rejected as not-making-sense; revisit the listener-side strategy.

## Status

Repo / code state at the time of writing:

| Step | Status |
|------|--------|
| Add `emittedAt` field on `StatisticsEvent`, lazy-fill from `updatedAt` in getter, latest-emission-wins `reduce()`, include in `equals`/`hashCode`. | Done |
| Add `findAllByKeysForUpdate` to `StatisticsEventRepository` (JPQL row-value `IN` + `ORDER BY` + `@Lock(PESSIMISTIC_WRITE)`). | Done |
| Add `BufferedReducer<StatisticsEventKey, StatisticsEvent>`, `UPSERT_BATCH_QUEUE`, batch-size config in `StatisticsEventServiceComponent`. | Done |
| Replace sync `upsertStatisticsEvent` body with truncate → default `emittedAt` → validate → `eventBuffer.reduce(...)`. Returns `void`. | Done |
| Add `flushEventBuffer()` (`@Scheduled` cron + `@PreDestroy`) that drains the buffer and dispatches JMS batches. | Done |
| Restore `create` (`REQUIRES_NEW`) and original `findOrCreate` (returns `StatisticsEvent`). | Done |
| Wire the JMS listener `processEventUpsertBatch(List<StatisticsEvent>)`: `findAllByKeysForUpdate`, fall back to `findOrCreate` for missing keys, capture old-state snapshot, apply staleness check, compute summary deltas, mutate locked rows, `saveAll`. | **In progress — paused** |
| Decide listener strategy for distinguishing "freshly created" vs. "found after concurrent insert" when computing summary deltas for missing keys. | **Open** |
| Update `StatisticsEventServiceComponentTest`: add `waitForEvent` helper that polls `findById` while flushing the event buffer; update post-upsert sync `findById` calls; have `waitForSummary` also flush the event buffer first. | Pending |

## Files touched so far

- `src/main/java/org/coldis/library/service/statistics/StatisticsEvent.java`
  - New `emittedAt` field, getter (lazy-fills from `updatedAt`), setter,
    `reduce()` updated, `equals`/`hashCode` include `emittedAt`.
- `src/main/java/org/coldis/library/service/statistics/StatisticsEventRepository.java`
  - New `findAllByKeysForUpdate(Collection<List<String>>)`.
- `src/main/java/org/coldis/library/service/statistics/StatisticsEventServiceComponent.java`
  - Buffer field, batch JMS queue constant, batch-size config.
  - `upsertStatisticsEvent` rewritten as buffer-only (void).
  - `flushEventBuffer()` added.
  - `create` and original `findOrCreate` restored.
  - `processEventUpsertBatch` JMS listener still **needs to be written** —
    the previous in-progress version was reverted along with `findOrCreate`.

## Pending work

1. Write `processEventUpsertBatch(List<StatisticsEvent>)`:
   - Build key tuples, call `findAllByKeysForUpdate`.
   - For each key not in the result, call `findOrCreate(incoming)`.
   - Snapshot old state (`dimensionValue`, `weight`, `dateTime`, `emittedAt`)
     into local primitives **before** mutating the row.
   - Drop stale events (`incoming.emittedAt < existing.emittedAt`).
   - Compute summary deltas (extract the existing inline logic into a helper
     that takes the captured primitives + the incoming event).
   - Mutate the locked row (`setDimensionValue`, `setWeight`, `setDateTime`,
     `setExpiredAt`, `setEmittedAt`) and add to `saveAll` list.
   - Resolve the open question above on how to distinguish freshly-created
     from found-after-conflict rows for correct delta computation.
2. Update `StatisticsEventServiceComponentTest`:
   - Add `waitForEvent(...)` mirroring `waitForSummary(...)` — polls
     `findById` while calling `flushEventBuffer()` each iteration.
   - Replace post-upsert direct `findById` calls with the polling helper.
   - Have `waitForSummary` also call `flushEventBuffer()` first so events
     drain before the summary deltas they trigger.
3. Run the full test suite.
4. Verify `hibernate.jdbc.batch_size` (and `order_inserts` / `order_updates`)
   are set, otherwise `saveAll` won't actually batch at the JDBC layer.

## New config properties

| Property | Default | Purpose |
|----------|---------|---------|
| `org.coldis.library.service.statistics.event.buffer.cron` | `0 * * * * *` | Flush interval for the event upsert buffer. |
| `org.coldis.library.service.statistics.event.buffer.batch-size` | `100` | Max events per JMS upsert message. |
| `org.coldis.library.service.statistics.event.buffer.processupsertbatch.concurrency` | `1` | JMS listener concurrency for upsert batches. |
