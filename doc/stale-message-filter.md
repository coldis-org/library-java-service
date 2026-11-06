# Stale message filter

Opt-in filter that drops (acknowledges without processing) a JMS message when
a message with the same key started successful processing after this one was
posted: that processing already reflected the state the older message carried.

## Enabling

- `org.coldis.library.service.jms.stale-filter.enabled=true` (default `false`).
- Producers mark messages with a key (`JmsMessage` stale filter key property).
  Only keyed messages are ever dropped.

## How a drop is decided

- **Posted at:** the explicit posted-at property, else the scheduled delivery
  time, else the JMS timestamp. This is the producer's clock.
- **Last processing start:** recorded per destination and key by consumers
  (any instance, shared through the database). This is the consumer's clock.
- The message is dropped when `postedAt + clockSkewMargin < lastProcessingStart`.

Producer and consumer hosts must be clock-synchronized well below
`clock-skew-margin` (default 1 second). A producer clock running behind by more
than the margin makes newer messages look stale, and they are lost.

## Observability

- Each drop increments the counter `jms.stale.filter.dropped` (tag
  `destination`), and logs at debug.
- Compare the drop rate per destination with the duplicate rate the filter is
  meant to remove.

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
- Records older than the window are deleted by the filter itself.
- Services that manage every table through migrations can set
  `create-schema=false` and add the statements above to their own migrations.
  A schema change in a future version will be announced in the release notes,
  since the library does not migrate existing tables.
