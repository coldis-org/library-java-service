# Statistics Event System

Embeddable, context-agnostic statistics engine for capturing dimension-based observations and computing aggregated summaries, anomaly comparisons, and probability analysis.

## Overview

The statistics module provides three core concepts:

- **StatisticsEvent** — A single observation: one owner reports a dimension value (and optional weight) within a context.
- **StatisticsEventSummary** — Aggregated counts and weights per dimension value, bucketed by configurable time intervals.
- **StatisticsContextConfiguration** — Per-context settings (e.g., time truncation interval).

Events are persisted immediately. Summary deltas are buffered in-memory, flushed periodically via JMS, and applied in batches for scalability.

## Enabling

Set the following property to activate the statistics module:

```properties
org.coldis.configuration.service.statistics-enabled=true
```

When disabled, no statistics beans, scheduled tasks, or JMS listeners are created.

## Package

```
org.coldis.library.service.statistics
```

## Key Classes

| Class | Role |
|-------|------|
| `StatisticsEvent` | JPA entity. Composite key: `(context, ownerKey, dimensionName)`. Implements `Expirable` and `Reduceable`. |
| `StatisticsEventSummary` | JPA entity. Composite key: `(context, dimensionName, dateTime)`. Stores `valueCounts`, `totalCount`, `valueWeights`, `totalWeight`. |
| `StatisticsEventSummaryDelta` | Buffered delta POJO. Implements `Reduceable` for in-memory aggregation before flush. |
| `StatisticsContextConfiguration` | JPA entity. Key: `context`. Stores `truncationMinutes`. |
| `StatisticsEventServiceComponent` | Business logic: upsert, delete, batch upsert, expired event purge. |
| `StatisticsEventSummaryServiceComponent` | Summary logic: delta buffering/flushing/applying, period queries, comparison, probability. |
| `StatisticsContextConfigurationServiceComponent` | Cached context configuration lookup, context-aware time truncation. |
| `StatisticsAutoConfiguration` | Spring `@Configuration` that enables scheduling. Conditional on `statistics-enabled`. |

## Data Model

### StatisticsEvent

| Field | Type | PK | Description |
|-------|------|----|-------------|
| `context` | String | Yes | Groups events (e.g., "fraud-detection", "conversion-tracking") |
| `ownerKey` | String | Yes | Identifies the entity being observed (e.g., customer ID) |
| `dimensionName` | String | Yes | What is being measured (e.g., "city", "device") |
| `dateTime` | LocalDateTime | No | When the observation occurred (truncated per context config) |
| `dimensionValue` | String | No | The observed value (e.g., "sao-paulo", "mobile") |
| `weight` | BigDecimal | No | Numeric value to accumulate (defaults to 1) |
| `expiredAt` | LocalDateTime | No | Optional expiration for data cleanup |
| `emittedAt` | LocalDateTime | No | When the event was emitted by the caller; orders concurrent buffered upserts (latest-emission-wins). Defaults to `now()` if unset; falls back to `updatedAt` for legacy rows. |

**Index:** `(context, dimension_name, date_time)` for summary aggregation queries.

### StatisticsEventSummary

| Field | Type | PK | Description |
|-------|------|----|-------------|
| `context` | String | Yes | Context |
| `dimensionName` | String | Yes | Dimension name |
| `dateTime` | LocalDateTime | Yes | Truncated time bucket |
| `valueCounts` | Map<String, Long> | No | JSONB. Count per dimension value |
| `totalCount` | Long | No | Sum of all value counts |
| `valueWeights` | Map<String, BigDecimal> | No | JSONB. Accumulated weight per dimension value |
| `totalWeight` | BigDecimal | No | Sum of all value weights |

### StatisticsContextConfiguration

| Field | Type | PK | Description |
|-------|------|----|-------------|
| `context` | String | Yes | Context name |
| `truncationMinutes` | Long | No | Time bucket size in minutes (1-1440, default 15) |

## Architecture

### Event Flow

```
Event arrives
  -> StatisticsEventServiceComponent.upsertStatisticsEvent()  (returns void)
       -> Truncates dateTime using context configuration (cached)
       -> Defaults emittedAt to now() if unset
       -> Validates and reduces into in-memory event buffer
          (latest-emission-wins on duplicate keys)

Batch upsert
  -> StatisticsEventServiceComponent.upsertAllStatisticsEvents()
       -> Calls upsertStatisticsEvent() for each event (same buffer)

Periodic flush (configurable cron, default every minute)
  -> Drains event buffer, splits into chunks, dispatches each chunk
     as a List<StatisticsEvent> to JMS queue
     (statistics-event/upsert/batch)

JMS listener (processEventUpsertBatch)
  -> Acquires per-key locks via LockServiceComponent (advisory or table)
  -> Runs single CTE upsert: existing snapshot + MERGE / INSERT ON CONFLICT
     with latest-emission-wins predicate, returning was_inserted +
     pre-update state
  -> Buffers summary deltas computed from the result (skipping stale rows)

Summary deltas flush (configurable cron, default every 5 minutes)
  -> Drains summary delta buffer, splits into chunks, dispatches each
     chunk as a List<StatisticsEventSummaryDelta> to JMS queue
     (statistics-event/summary/delta/batch)

Summary listener (processSummaryDeltaBatch)
  -> Applies each delta in the batch with pessimistic per-row locking
     (default concurrency 1)
```

### Buffered Event Upserts

Event upserts are also **eventually consistent**, mirroring the summary path:

1. `upsertStatisticsEvent()` is `void` — the caller's data lands in an in-memory `BufferedReducer<StatisticsEventKey, StatisticsEvent>`. Validation and `dateTime` truncation happen synchronously, so input errors surface immediately; the DB write does not.
2. Same-key upserts within the buffer window are reduced via `StatisticsEvent.reduce()` (latest-emission-wins by `emittedAt`).
3. The buffer flushes on a cron (default every minute) and on JVM shutdown (`@PreDestroy`). Each flush splits the drained set into chunks of `batch-size` (default 100) and sends each chunk as a `List<StatisticsEvent>` JMS message.
4. The listener:
   - Acquires per-key locks via `LockServiceComponent` to serialize cross-instance writers (`ADVISORY` by default, `TABLE` when collision-free string-key locking is required).
   - Runs a single CTE statement that snapshots the existing rows, upserts the batch with a latest-emission-wins predicate, and joins the pre-update state into the result.
   - Skips rows the predicate filtered out as stale (incoming `emittedAt` older than persisted) — they end up `applied = false` in the result and produce no summary delta.
   - Computes summary deltas from the per-row pre-update state and the incoming event, and buffers them.

Two upsert SQL flavors are supported, selected by `event.upsert-strategy`:

- `merge` (default) — `MERGE ... RETURNING merge_action()`. Requires Postgres 17+.
- `on-conflict` — `INSERT ... ON CONFLICT DO UPDATE ... RETURNING (xmax = 0)`. Works on Postgres 9.5+.

**Read-your-writes within the flush window is not guaranteed.** A caller that reads back an event right after upserting may not see it until the next flush.

**`deleteStatisticsEvent` stays synchronous** — deletes are rare and keeping them sync avoids ordering complexity vs. concurrent buffered upserts.

### Buffered Summary Deltas

Summary updates are **eventually consistent** for scalability:

1. Each event upsert/delete produces a `StatisticsEventSummaryDelta` (count and weight changes per dimension value).
2. Deltas are reduced in-memory by `BufferedReducer` — multiple changes to the same summary key are merged.
3. The buffer is flushed periodically to an internal JMS queue.
4. A JMS listener applies each delta to the summary with pessimistic locking.
5. Totals (`totalCount`, `totalWeight`) are recomputed from the value maps after each delta to prevent drift.

### Expiration and Cleanup

Events can have an optional `expiredAt` timestamp. A scheduled job (default 3 AM daily) deletes expired events in configurable batches via a JMS loop:

1. Cron triggers a JMS message to `statistics-event/delete/expired`.
2. Listener deletes a batch of expired events (native SQL with `LIMIT`).
3. If rows were deleted, sends another message to continue the loop.

Expired event deletion does **not** update summaries — old summary data is expected to age out naturally.

### Context-Aware Time Truncation

Each context can define its own time bucket size via `StatisticsContextConfiguration`:

- Default: 15 minutes (configurable via `org.coldis.library.service.statistics.default-truncation-minutes` property).
- Per-context: stored in DB, cached with hours expiration.
- Affects how `dateTime` is truncated on events and how summaries are bucketed.

## Query Capabilities

### Period Queries

`findByPeriod(context, dimensionName, startDateTime, endDateTime)` — merges all summaries in the range into a single aggregated result with combined counts and weights.

### Comparison Analysis

`compareByPeriod(context, dimensionName, referenceDateTime, windowUnit, windowSize, stepUnit, steps)` — compares a reference time window against historical periods. Returns:

- Average and standard deviation of total counts and per-value counts
- Average and standard deviation of value ratios
- Z-scores for the reference window
- Reference window values

### Probability Analysis

`singleDimensionProbabilityByPeriod(...)` — computes the probability of a specific dimension value based on historical distribution.

`naiveMultiDimensionProbabilityByPeriod(...)` — computes joint probability of multiple dimensions assuming independence (P(A and B) = P(A) x P(B)).

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `org.coldis.configuration.service.statistics-enabled` | `false` | Feature toggle — must be `true` to activate the module |
| `org.coldis.library.service.statistics.default-truncation-minutes` | `15` | Default time bucket size when no context config exists |
| `org.coldis.library.service.statistics.event.buffer.cron` | `0 * * * * *` | Event upsert buffer flush schedule (every minute) |
| `org.coldis.library.service.statistics.event.buffer.batch-size` | `100` | Max events per JMS upsert batch message |
| `org.coldis.library.service.statistics.event.buffer.processupsertbatch.concurrency` | `1` | JMS concurrency for the upsert batch listener |
| `org.coldis.library.service.statistics.event.upsert-strategy` | `merge` | Upsert SQL flavor: `merge` (PG 17+) or `on-conflict` (PG 9.5+) |
| `org.coldis.library.service.statistics.event.lock-type` | `ADVISORY` | Lock mechanism for per-key serialization: `ADVISORY` or `TABLE` |
| `org.coldis.library.service.statistics.event.deleteexpired.cron` | `0 0 3 * * *` | Expired event cleanup schedule (3 AM daily) |
| `org.coldis.library.service.statistics.event.deleteexpired.batch-size` | `1000` | Batch size for expired event deletion |
| `org.coldis.library.service.statistics.event.container-factory` | `jmsListenerContainerFactory` | JMS listener container factory bean for event listeners (upsert + deleteExpired) |
| `org.coldis.library.service.statistics.summary.buffer.cron` | `0 */5 * * * *` | Summary delta buffer flush schedule (every 5 minutes) |
| `org.coldis.library.service.statistics.summary.buffer.batch-size` | `100` | Max deltas per JMS summary delta batch message |
| `org.coldis.library.service.statistics.summary.processsummarydelta.concurrency` | `1` | JMS concurrency for the summary delta batch listener |
| `org.coldis.library.service.statistics.summary.container-factory` | `jmsListenerContainerFactory` | JMS listener container factory bean for the summary delta listener |

## Usage

### Dependencies

Add `coldis-library-java-service` to your project and enable the module:

```properties
org.coldis.configuration.service.statistics-enabled=true
```

Your application needs:
- PostgreSQL (for event and summary tables — auto-created by Hibernate)
- Apache Artemis / JMS broker (for buffered summary delta processing)

### Upsert an Event

```java
@Autowired
private StatisticsEventServiceComponent statisticsEventServiceComponent;

final StatisticsEvent event = new StatisticsEvent();
event.setContext("my-context");
event.setOwnerKey("customer-123");
event.setDimensionName("city");
event.setDimensionValue("sao-paulo");
event.setWeight(new BigDecimal("150.00")); // optional, defaults to 1
event.setExpiredAt(LocalDateTime.now().plusDays(90)); // optional
statisticsEventServiceComponent.upsertStatisticsEvent(event);
```

### Batch Upsert

```java
statisticsEventServiceComponent.upsertAllStatisticsEvents(List.of(event1, event2, event3));
```

### Query Summaries

```java
@Autowired
private StatisticsEventSummaryServiceComponent summaryComponent;

// Single bucket
StatisticsEventSummary summary = summaryComponent.findById(
    new StatisticsEventSummaryKey("my-context", "city", dateTime), false);

// Period aggregation
StatisticsEventSummary period = summaryComponent.findByPeriod(
    "my-context", "city", startDateTime, endDateTime);

// Anomaly comparison
StatisticsEventSummaryComparison comparison = summaryComponent.compareByPeriod(
    "my-context", "city", referenceDateTime,
    ChronoUnit.HOURS, 1, ChronoUnit.DAYS, 7);

// Probability
StatisticsEventSingleDimensionProbability probability =
    summaryComponent.singleDimensionProbabilityByPeriod(
        "my-context",
        new StatisticsValuedEventDimension("city", "sao-paulo"),
        referenceDateTime, ChronoUnit.HOURS, 1, ChronoUnit.DAYS, 7);
```

### Configure Truncation

```java
@Autowired
private StatisticsContextConfigurationServiceComponent configComponent;

final StatisticsContextConfiguration config = new StatisticsContextConfiguration();
config.setContext("my-context");
config.setTruncationMinutes(60L); // 1-hour buckets
configComponent.upsertConfiguration(config);
```
