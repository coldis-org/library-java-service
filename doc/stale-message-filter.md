# Stale message filter

Filter that drops (acknowledges without processing) a JMS message when
a message with the same key started successful processing after this one was
posted: that processing already reflected the state the older message carried.

## Enabling

- `org.coldis.library.service.jms.stale-filter.enabled` (default `true`; `false`
  turns the filter off for a service).
- Producers opt in per message with a key (`JmsMessage` stale filter key
  property). Only keyed messages are ever dropped, and an instance that never
  sees a keyed message does no synchronization work.

## How a drop is decided

- **Posted at:** the explicit posted-at property, else the scheduled delivery
  time, else the JMS timestamp. This is the producer's wall clock.
- **Last processing start:** recorded per destination and key by consumers
  (any instance, shared through the database). This is the consumer's wall
  clock.
- The message is dropped when `postedAt + clockSkewMargin < lastProcessingStart`.

Both sides are wall-clock epoch millis (`System.currentTimeMillis()`), never the
application clock (`DateTimeHelper`). A simulated application clock, e.g.
`DateTimeHelper.setClock` or `TestHelper.moveClockBy` in tests, does not affect
the filter. A producer that sets the posted-at property (`staleFilterPostedAt`)
itself must set it in wall-clock epoch millis too.

Producer and consumer hosts must be clock-synchronized well below
`clock-skew-margin` (default 1 second). A producer clock running behind by more
than the margin makes newer messages look stale, and they are lost.

## Synchronization between instances

Each instance keeps its records in memory and, every
`synchronization-interval` (default 10 s), synchronizes them with the shared
table:

1. **Push:** the records of messages this instance processed since the last
   push, written in key order (so instances lock shared rows in the same order
   and do not deadlock each other).
2. **Pull:** the records other instances pushed since the last pull, only for
   the destinations this instance has seen keyed messages on.
3. **Delete:** records synchronized longer than `window` ago, by the database
   clock (the clock `synchronized_at` is written with). Only one instance runs
   it at a time (a transaction-level advisory lock that is never waited for: an
   instance that does not get it skips the cleanup for that cycle).

The instance learns which destinations it consumes keyed messages from as the
messages arrive; there is nothing to configure. Until it sees one, the
synchronization does not touch the database (no push, pull or delete), so
instances that never consume keyed messages cost the database nothing. After a
restart, other instances' records of a destination only count here from the
synchronization after its first keyed message (until then the filter fails
open for it). A pull also returns the instance's own rows; it ignores them.

The synchronization runs on its own daemon thread (`jms-stale-filter-sync`),
started with the service when the filter is enabled at startup, so it never
delays the service's other scheduled jobs. Each statement has a timeout of
`query-timeout` (default 5 s, rounded up to whole seconds, `0` for none). A failed
synchronization is logged and retried on the next cycle; the filter fails open
meanwhile.

### Choosing the interval

Another instance's processing is only known here after its push and this
instance's pull, so a cross-instance drop needs the newer processing to be
shared before the older message is consumed: up to two intervals after it
started. A shorter interval means more cross-instance drops but more database
work (each instance pushes, pulls and attempts the cleanup on every cycle); a
longer one means less work and fewer drops.

Compare the interval with how long keyed messages wait in their queues. When
they are consumed well within the interval, most duplicates are only caught by
the instance that processed the newer message (`source=local`), and a shorter
interval would help; when they wait much longer than the interval, a shorter
interval adds database work for few extra drops. The metrics below show both
sides: the `source` split of the drops and the cost of each phase.

## Observability

Micrometer metrics, in the same registry as the other JMS metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `jms.stale.filter.checked` | counter | `destination`, `outcome` = `dropped` \| `processed` | Keyed messages checked, once per delivery that is dropped or handed to the listener (a delivery refused by the listener bulkhead is not counted; it is redelivered and checked again). The drop ratio is `dropped / (dropped + processed)`. |
| `jms.stale.filter.dropped` | counter | `destination`, `source` = `local` \| `pulled` | Dropped messages, by where the newer record came from: this instance, or another instance through the shared table. |
| `jms.stale.filter.sync` | timer | `phase` = `push` \| `pull` \| `delete`, `outcome` = `success` \| `failure` \| `skipped` | Duration of each synchronization phase. `skipped`: nothing to push, cleanup run by another instance, or no keyed destination seen yet. |
| `jms.stale.filter.sync.rows` | counter | `phase` | Rows pushed, pulled or deleted. |
| `jms.stale.filter.records` | gauge | | Records held in memory. |

- Each drop also logs at debug.
- Compare the drop rate per destination with the duplicate rate the filter is
  meant to remove; the `pulled` share of drops is what the database
  synchronization contributes.

## Storage (PostgreSQL)

When persistence is enabled, processing records are shared through a table in
the service's primary data source. With `create-schema=true` (default) the
library creates it at startup, outside the service's migrations:

```sql
CREATE UNLOGGED TABLE IF NOT EXISTS stale_message_filter (
	message_key VARCHAR(512) PRIMARY KEY,
	processed_at BIGINT NOT NULL,
	synchronized_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS stale_message_filter_synchronized_at_idx
	ON stale_message_filter (synchronized_at);
```

- The table name is `table-name` (default `stale_message_filter`).
- It is `UNLOGGED`: its content is a cache of recent processing starts and may
  be lost on a database crash (the filter then simply drops nothing until it
  fills again).
- `processed_at` is the consumer's wall clock (epoch millis);
  `synchronized_at` is the database clock (epoch millis) and is the only value
  the pull watermark and the cleanup compare with.
- Records older than the window are deleted by the filter itself, with a
  cutoff read from the database clock and bound as a parameter so the
  `synchronized_at` index is used.
- Services that manage every table through migrations can set
  `create-schema=false` and add the statements above to their own migrations.
  A schema change in a future version will be announced in the release notes,
  since the library does not migrate existing tables.
