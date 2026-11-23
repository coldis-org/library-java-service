# JMS listener bulkhead

Opt-in bound on how many JMS handlers run at once in one instance. Consumers
keep polling as before; a consumer that received a message waits for a permit
before its handler runs, in arrival order, and idle queues hold no permit. A
flooded queue therefore cannot keep the other queues from progressing, and the
handlers never use more of a shared resource (typically database connections)
than the limit allows.

Everything is off by default: with `enabled=false` the listener path and the
listener executor are exactly as before.

## When to enable it

- Handlers share a scarce resource (database pool, downstream rate limit) and
  bursts on some queues exhaust it.
- `listener.executor.concurrency*` is set today to protect that resource. It
  must be replaced (see [Why not `listener.executor.concurrency`](#why-not-listenerexecutorconcurrency)).

## Properties

Prefix `org.coldis.library.service.jms.listener.bulkhead`. The top-level
settings configure the group named `default`.

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Master switch, read at startup. `false`: no group is limited. |
| `max-in-flight` | unset | Maximum concurrent handlers of the group. Wins over the multiplier when positive. |
| `max-in-flight-cpu-multiplier` | `2` | Handlers per CPU, used when `max-in-flight` is not set: `ceil(CPUs × multiplier)` (CPUs: [JMS threads](jms-threads.md#cpus)). Tune between 1.5 and 3, toward 3 for database-heavy modules. `0`: the group is not limited. |
| `max-wait` | unset | Unset or `0`: wait until a permit, the container stop or shutdown. Set: on timeout the message is rolled back to the broker (see [Delivery attempts](#delivery-attempts)). |
| `max-wait-deliveries` | `3` | `max-wait` applies only while the message's `JMSXDeliveryCount` is at most this value; later deliveries wait without timeout. |
| `reserved.<name>.permits` | — | Permits only the matching listeners may use (at least 1). |
| `reserved.<name>.destinations` | — | Comma-separated listener destinations (as in `@JmsListener(destination)`, resolved; a fully qualified `address::queue` also matches its queue name). |
| `reserved.<name>.factories` | — | Comma-separated container factory bean names. A destination match wins over a factory match. |
| `groups.<group>.factories` | — | Container factory bean names whose listeners use this group instead of `default`. |
| `groups.<group>.max-in-flight`, `.max-in-flight-cpu-multiplier`, `.max-wait`, `.max-wait-deliveries`, `.reserved.*` | as above | The same settings for a group. Nothing is inherited from `default`. |

The listener executor is **unbounded by default**, with or without the
bulkhead: the bulkhead, not the thread count, is the concurrency limit. A
consumer waiting for a permit holds its thread, and an idle poller holds one
too, so a thread cap lets those threads keep other queues' consumers from
running at all. Executor settings, deprecations, the CPU source, metrics and
the migration for services that relied on the old thread cap:
[JMS threads](jms-threads.md).

The startup fails (`IllegalArgumentException` naming the property) when the
bulkhead is enabled and: a group's reserved permits leave no shared permit
(`sum(reserved) >= max-in-flight`, or reservations on a group whose multiplier is 0); a
destination or factory is in two reservations of a group; a factory is in two
groups; `permits < 1`; `max-wait-deliveries < 1`; `max-wait` is negative;
`groups.default` is used; or the top-level `factories` is set.

### Group of a listener

First match:

1. the group set on its container factory with
   `JmsListenerContainerFactoryBuilder.bulkheadGroup(name)`;
2. the group whose `groups.<group>.factories` lists the factory bean name;
3. `default`.

Factories not built by `JmsListenerContainerFactoryBuilder` (directly
instantiated `DefaultJmsListenerContainerFactory`) give their listeners no
factory name: they use `default`, without reservations by factory. A group
set on a factory but missing from the properties is not limited (WARN).

### Changing the limit at runtime

`JmsListenerBulkheadProperties` is a `@ConfigurationProperties` bean
(`jmsListenerBulkheadProperties`) and the bulkhead reads its limit on use, so
`PUT /properties/integer/jmsListenerBulkheadProperties/maxInFlight` changes the
`default` group's limit live (it wins over the multiplier;
`maxInFlightCpuMultiplier` is changed the same way through the `double` endpoint). A raised limit is seen by waiters within 250 ms; a lowered one as
handlers finish. Whether a group is limited at all is decided at its first use
and needs a restart; `enabled` is read at startup. Other groups are only
reachable through the properties at startup.

## Semantics

- **Where the permit is taken.** In the listener adapter, after the stale
  message filter check and the async hops check (neither takes a permit) and
  before payload conversion and the listener method. The permit is released
  when the listener method returns or throws, before the adapter's error
  handling (exceeded-hops queue, non-retriable drop) and before the container
  commits or rolls back the JMS session.
- **Before any transaction.** The listener method's `@Transactional` starts
  inside the adapter, after the permit: a waiting consumer holds no database
  connection. This does not hold if a transaction manager is set on the
  container factory (or a `JtaTransactionManager` bean exists): the container
  then starts the transaction before the listener, before the permit.
- **A message that waited** is checked against the stale filter again after the
  grant, and dropped if a newer same-key processing started meanwhile.
- **Arrival order.** Waiters are served first come, first served; a release
  hands its permit straight to the oldest waiter that may take it, so an
  arriving message never overtakes a queued one. The order is per consumer, not
  per queue: under saturation each backlogged container's share of throughput
  is proportional to its consumers, so a queue's share follows its concurrency
  range. Reservations are the tool to guarantee a minimum to a listener.
- **Reserved permits** are strict: a listener of reservation `k` gets a
  reserved permit at once whenever fewer of its handlers run than the
  reservation's `permits`, whatever the queue holds; beyond that it competes for shared permits in
  arrival order. An idle reserved permit is never lent.
- **The limit is never below** the reserved permits plus one; a lower value is
  raised to it, with a WARN.

### Stop, shutdown and failures

- `stop()` (also the first step of the context close): each waiter notices it
  within 250 ms and throws `JmsListenerBulkheadException(CONTAINER_STOPPED)`;
  the container rolls the message back and the consumer pauses. Handlers
  already running finish normally.
- `shutdown()`: the same, and the container interrupts consumers that are still
  busy after its receive timeout; a waiter then throws `INTERRUPTED`. The
  interrupt flag is not re-asserted, so the rollback (a broker call) succeeds.
- A handler failure releases the permit and then follows the usual paths
  (redelivery, non-retriable drop, exceeded hops).
- A process crash acknowledges nothing: the broker redelivers.
- `JmsListenerBulkheadException` is logged by `EnhancedJmsErrorHandler` as one
  WARN line for a wait timeout and one DEBUG line for a stop or shutdown.

### Delivery attempts

Every rollback (a wait timeout, a stop or a shutdown that catches a message
waiting) counts as a delivery attempt for the broker, which redelivers after
its `redelivery-delay` and dead-letters after `max-delivery-attempts` (the
container back-off plays no part). `max-wait-deliveries` caps the attempts a
timeout can consume: keep it well below the broker's `max-delivery-attempts`,
so waiting alone never dead-letters a message. Rolling restarts under
saturation still cost one attempt per waiting message, and `max-wait-deliveries`
does not cap those. Size the broker's `max-delivery-attempts` for the queues of
a limited group as at least `max-wait-deliveries` (when `max-wait` is set) plus
the restarts a backlog may live through, plus the retries handlers need, with
margin. Alert on the dead letter queue depth and on a non-zero
`jms.listener.bulkhead.wait.aborted` with `reason=stopped` or `interrupted`.

Recommendation: leave `max-wait` unset. A timeout only puts the message back
on the queue so another instance may take it; with the default prefetch of 0
the same instance often gets it again.

## Sizing

- `max-in-flight` ≈ the database pool size minus what request threads,
  scheduled work and batches need at the same time. The bulkhead covers JMS
  handlers only; it is not a database limiter.
- **Rollout:** enable it with `max-in-flight` at today's effective handler
  concurrency, watch `jms.listener.bulkhead.wait` and
  `jms.listener.bulkhead.waiting`, then lower the limit step by step.
- **Thread count.** Worst case with the bulkhead on: the sum of the maximum
  concurrency of every listener container, in threads and JMS sessions. With
  the unbounded executor each polling consumer has its
  own thread. A backlogged container climbs to its maximum consumers even
  though only `max-in-flight` handlers run (a consumer that received a message
  counts as busy), so expect up to the sum of the maximum consumers of every
  backlogged container in threads and JMS sessions, each parked with one
  message. Review large concurrency ranges before enabling: they also set a
  queue's weight in the arrival order. Virtual threads
  (`listener.executor.virtual=true`) reduce the thread cost; the bulkhead
  itself never blocks inside `synchronized`.

## Metrics

Registered when a `MeterRegistry` bean exists, for limited groups only.

| Name | Type | Tags | Meaning |
|---|---|---|---|
| `jms.listener.bulkhead.in.flight` | gauge | `group` | Permits in use. |
| `jms.listener.bulkhead.waiting` | gauge | `group` | Consumers holding a received message and waiting for a permit. |
| `jms.listener.bulkhead.max.in.flight` | gauge | `group` | Current limit. |
| `jms.listener.bulkhead.reserved.in.flight` | gauge | `group`, `reservation` | Running handlers of the reservation's listeners (may exceed its permits: the rest use shared permits). |
| `jms.listener.bulkhead.reserved.permits` | gauge | `group`, `reservation` | Reserved permits. |
| `jms.listener.bulkhead.wait` | timer | `group`, `destination` | Wait of every granted permit (0 when immediate): count = admitted messages, total = time waited. |
| `jms.listener.bulkhead.wait.aborted` | counter | `group`, `destination`, `reason` = `timeout`, `stopped` or `interrupted` | Waits that ended without a permit. |

Series: 3 per group, 2 per reservation, one timer per listener destination,
and one counter per destination and reason that occurred. No histogram by
default; add `management.metrics.distribution.*` for
`jms.listener.bulkhead.wait` if percentiles are needed.

What to watch: `waiting` high for long periods (the limit is too low for the
load, or a handler is slow), the `wait` mean per destination (the latency the
bulkhead adds), any `wait.aborted{reason=timeout}`.

## Why no queue starves

Assumption: handlers end within a bound `T` (transaction timeouts), and the
listener executor is unbounded.

- Idle queues hold no permit: a permit is taken only after a message was
  received and released before the listener returns.
- A consumer has at most one message in its listener, so a container
  contributes at most its maximum consumers as waiters, and a consumer that
  finishes queues again at the tail. A waiter at position `p` gets a shared
  permit after at most `p` shared releases: its wait is at most
  `ceil(p / shared permits) × T`, with `p` at most the sum of every
  container's maximum consumers.
- A thread holds at most one permit and no lock while its handler runs: no
  self-deadlock.
- With a capped pool, waiters hold pool threads and other queues' consumers
  wait in the executor queue, hence the listener executor is unbounded by
  default.

Container details (spring-jms 6.2): a consumer task ends after
`maxMessagesPerTask` receive attempts and resubmits itself, waiting only makes
a task longer; a waiting consumer is not idle, so waiting never scales a
container down, and surplus consumers retire as before once the backlog is
gone.

### Prefetch

- `spring.artemis.consumer-window-size=0` (library default): a waiting consumer
  holds exactly the one message it received; other instances cannot take that
  message until it is processed, rolled back or the consumer closes. Keep it.
- Dynamic consumer credits ([doc](dynamic-consumer-credit-scaling.md)): above
  the per-consumer depth threshold a consumer may buffer up to
  `dynamic-credits-max-credits` bytes, and a consumer waiting for a permit keeps
  that buffer. It happens only with a deep backlog, when every instance is
  saturated anyway. Where instances have very different capacity, lower
  `dynamic-credits-max-credits` or raise the threshold.
- Do not combine with a broker `slow-consumer-policy=KILL`: it would disconnect
  waiting consumers.

## Why not `listener.executor.concurrency`

`listener.executor.concurrency*` builds a `SimpleAsyncTaskExecutor` with a
concurrency limit. That limit counts polling consumers, not handlers, and a
consumer thread that already holds a slot asks for a second one in two places:
when it receives a message and the container starts another consumer, and when
its task ends (`maxMessagesPerTask`) and resubmits itself. Both happen under the
container's lifecycle lock, and the throttle waits without timeout. Once every
slot is held by such a thread, nothing is released again: the flooded queue
stops after a few messages, every other queue makes no progress at all, and
only a restart recovers. With a limit below the number of containers, the
thread starting the containers hangs too. `JmsListenerExecutorStarvationTest`
reproduces it.

A bounded pool (the default before this version) has a milder form of the
same problem: idle pollers occupy pool threads, and with more consumers than
threads busy queues wait behind them.

### Migration

1. Remove `listener.executor.concurrency` and
   `listener.executor.concurrency-cpu-multiplier` (and any `executor.core-size*`
   or `executor.unbounded=false` used as a limit).
2. Set `listener.bulkhead.enabled=true` and `listener.bulkhead.max-in-flight`
   (or the multiplier) to today's effective concurrency.
3. Watch the metrics, then lower the limit; add reservations for listeners
   that need a guaranteed minimum.

## Classes

- `JmsListenerBulkhead`: the limiter (lock, arrival-ordered waiters,
  reservations, live limit). No Spring dependency.
- `JmsListenerBulkheadRegistry`: validation, group and reservation resolution,
  one bulkhead per limited group, gauges.
- `JmsListenerBulkheadBinding`: what one listener uses (bulkhead, reservation,
  destination, wait timer and aborted counters).
- `ExtendedMessagingMessageListenerAdapter`: takes and releases the permit.
- `ExtendedJmsListenerContainerFactory`: built by
  `JmsListenerContainerFactoryBuilder`; tags its listeners with the factory bean
  name and group. Its containers are plain `DefaultMessageListenerContainer`.

## Tests

- `JmsListenerBulkheadTest`: the limiter alone (limit, order, timeout, stop,
  interrupt, a grant racing an interrupt, reservations, live limit).
- `JmsListenerBulkheadRegistryTest`: properties, validation, resolution, gauges.
- `JmsListenerExecutorStarvationTest`: the legacy limit stopping every queue,
  and the same scenario progressing with the bulkhead.
- `JmsListenerBulkheadContainerTest`: no loss or duplicate on wait timeout, stop
  and shutdown; failures; reservations; the stale filter around the permit.
- `JmsListenerBulkheadIntegrationTest`: real `@JmsListener` methods, the permit
  taken before the transaction, metrics.
