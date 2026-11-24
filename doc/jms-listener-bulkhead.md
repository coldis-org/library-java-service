# JMS listener bulkhead

Bound (on by default) on how many JMS handlers run at once in one instance.
While a group has no capacity for a listener, the listener's consumers wait at
a gate **before they receive**, in arrival order: the messages wait on the
broker queue, not in consumers, and idle queues hold no permit. A flooded queue
therefore cannot keep the other queues from progressing, the handlers never use
more of a shared resource (typically database connections) than the limit
allows, and a backlog the instance cannot process yet costs the broker no more
than a queue nobody consumes.

The bulkhead is on by default: without any property, every listener container
shares one group limited to 6 handlers per CPU (see
[Settings](#settings)). Set `enabled=false` to keep the listener path exactly
as before. The listener executor is unbounded by default either way, so on
upgrade a service's concurrent handlers are bounded by the bulkhead instead
of the old thread cap; set `max-in-flight` or the multiplier if the default
does not fit (see
[JMS threads](jms-threads.md#behaviour-change-and-migration)).

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
| `enabled` | `true` | Master switch, read at startup. `false`: no group is limited. |
| `max-in-flight` | unset | Maximum concurrent handlers of the group. Wins over the multiplier when positive. |
| `max-in-flight-cpu-multiplier` | `6` | Handlers per CPU, used when `max-in-flight` is not set: `ceil(CPUs × multiplier)` (CPUs: [JMS threads](jms-threads.md#cpus)). Handlers that also wait on the database or other services run well above one per CPU at normal peaks; the default sits near those peaks so it stops pile-ups without throttling normal load. Tune toward 2–4 for CPU-bound modules and 8–12 for database-, I/O- or broker-heavy ones. A first production rollout at 4 per CPU throttled message-heavy services well below their normal concurrency; the default was 8 while waiting consumers held their messages, and is 6 since the gate keeps waiting messages on the queue. `0`: the group is not limited. |
| `max-wait` | unset | Applies to a consumer that waits for a permit **holding a received message** (see [The gate](#the-gate-before-receive)), not to the gate. Unset or `0`: wait until a permit, the container stop or shutdown. Set: on timeout the message is rolled back to the broker (see [Delivery attempts](#delivery-attempts)). |
| `max-wait-deliveries` | `3` | `max-wait` applies only while the message's `JMSXDeliveryCount` is at most this value; later deliveries wait without timeout. |
| `reserved.<name>.permits` | — | Permits only the matching listeners may use (at least 1). |
| `reserved.<name>.destinations` | — | Comma-separated listener destinations (as in `@JmsListener(destination)`, resolved; a fully qualified `address::queue` also matches its queue name). |
| `reserved.<name>.factories` | — | Comma-separated container factory bean names. A destination match wins over a factory match. |
| `groups.<group>.factories` | — | Container factory bean names whose listeners use this group instead of `default`. |
| `groups.<group>.max-in-flight`, `.max-in-flight-cpu-multiplier`, `.max-wait`, `.max-wait-deliveries`, `.reserved.*` | as above | The same settings for a group. Nothing is inherited from `default`. |

The listener executor is **unbounded by default**, with or without the
bulkhead: the bulkhead, not the thread count, is the concurrency limit. A
consumer waiting at the gate holds its thread, and an idle poller holds one
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
factory name: they use `default`, without reservations by factory. Their
containers have **no gate**: their consumers receive and then wait for a permit
holding the message, as before the gate. A group set on a factory but missing
from the properties is not limited (WARN).

### Changing the limit at runtime

`JmsListenerBulkheadProperties` is a `@ConfigurationProperties` bean
(`jmsListenerBulkheadProperties`) and the bulkhead reads its limit on use, so
`PUT /properties/integer/jmsListenerBulkheadProperties/maxInFlight` changes the
`default` group's limit live (it wins over the multiplier;
`maxInFlightCpuMultiplier` is changed the same way through the `double` endpoint). A raised limit is seen by waiters (gated or holding a message) within 250 ms; a lowered one as
handlers finish. Whether a group is limited at all is decided at its first use
and needs a restart; `enabled` is read at startup. Other groups are only
reachable through the properties at startup.

## The gate before receive

Containers built by `JmsListenerContainerFactoryBuilder` are
`ExtendedMessageListenerContainer`s. Before each receive, a consumer of a
limited listener passes its group's gate:

- **Open** (nobody waits and the listener is admissible: a free reserved permit
  of its reservation or a free shared permit): it receives at once.
- **Closed**: it waits, in arrival order, without receiving. Its messages stay
  on the broker queue, where any instance may take them.

What the consumer takes at the gate depends on its last receive:

- **Its last receive returned a message, or it has not received yet** (its
  queue is backlogged): it takes the permit at the gate and receives with a
  short timeout (100 ms at most, or the container's receive timeout when
  shorter). The message's handler runs on that permit. An empty receive gives
  the permit back at once.
- **Its last receive returned nothing** (its queue is idle): it passes when a
  permit is free but takes none, and receives with the container's receive
  timeout. An idle queue therefore never holds a permit while it polls, and
  never keeps a busy listener out. When such a receive returns a message, the
  handler acquires its permit then, normally at once.

**The race left.** Consumers of idle queues pass the gate without taking a
permit, so when several of their queues receive messages at the moment the
group fills up, the ones that lose the race wait for a permit holding their
message (`waiting.with.message`). Each such consumer holds at most one message,
once: its next receive takes the permit at the gate. Consumers of backlogged
queues never race, and no consumer is started while the gate is closed, so the
race does not grow with the backlog.

**Scaling.** A consumer that must wait at the gate counts as idle for the
container, and the container starts no new consumer while the gate is closed
for its listener. A backlogged container therefore does not climb to its
maximum consumers while the group is saturated; surplus consumers retire as
before once their tasks run idle.

## Semantics

- **Where the permit is used.** In the listener adapter, after the stale
  message filter check and the async hops check (neither waits) and before
  payload conversion and the listener method: the permit taken at the gate,
  or one acquired then. The permit is released when the listener method
  returns or throws, before the adapter's error handling (exceeded-hops queue,
  non-retriable drop) and before the container commits or rolls back the JMS
  session. A permit taken at the gate whose message is not handled (no
  message, superseded, exceeded hops) is released after the receive.
- **Before any transaction.** The gate is passed before the container starts
  its own transaction (when a transaction manager is set on the container
  factory, or a `JtaTransactionManager` bean exists), and the listener
  method's `@Transactional` starts inside the adapter: a gated consumer holds
  no transaction and no database connection. With a container transaction
  manager, the consumer that took its permit at the gate starts the
  transaction after it; a consumer that waits holding a message (the race
  above) waits inside that transaction.
- **A message that waited** for its permit holding a consumer is checked
  against the stale filter again after the grant, and dropped if a newer
  same-key processing started meanwhile. A message that waited on the queue
  behind the gate is checked when it is received.
- **Arrival order.** Waiters are served first come, first served, consumers
  holding a message before gated ones (they hold broker deliveries): a release
  hands its permit straight to the oldest waiter that may take it, so an
  arriving consumer never overtakes a queued one. The order is per consumer,
  not per queue: under saturation each backlogged container's share of
  throughput is proportional to its consumers, so a queue's share follows its
  concurrency range. Reservations are the tool to guarantee a minimum to a
  listener.
- **Reserved permits** are strict: a listener of reservation `k` passes the
  gate (or gets a reserved permit) at once whenever fewer of its handlers run
  than the reservation's `permits`, whatever the queue holds; beyond that it
  competes for shared permits in arrival order. An idle reserved permit is
  never lent.
- **The limit is never below** the reserved permits plus one; a lower value is
  raised to it, with a WARN.

### Stop, shutdown and failures

- `stop()` (also the first step of the context close): each waiter notices it
  within 250 ms. A gated consumer leaves the gate without receiving: nothing is
  rolled back and nothing is counted. A consumer holding a message throws
  `JmsListenerBulkheadException(CONTAINER_STOPPED)`; the container rolls the
  message back. The consumer pauses. Handlers already running finish normally.
- `shutdown()`: the same, and the container interrupts consumers that are still
  busy after its receive timeout; a waiter then ends with `INTERRUPTED`. The
  interrupt flag is not re-asserted, so the rollback (a broker call) of a
  consumer holding a message succeeds, and the container exits on its own
  state flags.
- A handler failure releases the permit and then follows the usual paths
  (redelivery, non-retriable drop, exceeded hops).
- A process crash acknowledges nothing: the broker redelivers.
- `JmsListenerBulkheadException` is logged by `EnhancedJmsErrorHandler` as one
  WARN line for a wait timeout and one DEBUG line for a stop or shutdown.

### Delivery attempts

Only a consumer waiting for a permit holding a message (the race after the
gate, or a container without the gate) can roll a message back. Every such
rollback (a wait timeout, a stop or a shutdown that catches a message
waiting) counts as a delivery attempt for the broker, which redelivers after
its `redelivery-delay` and dead-letters after `max-delivery-attempts` (the
container back-off plays no part). `max-wait-deliveries` caps the attempts a
timeout can consume: keep it well below the broker's `max-delivery-attempts`,
so waiting alone never dead-letters a message. Rolling restarts under
saturation still cost one attempt per message held by a waiting consumer (few
with the gate), and `max-wait-deliveries` does not cap those. Size the broker's `max-delivery-attempts` for the queues of
a limited group as at least `max-wait-deliveries` (when `max-wait` is set) plus
the restarts a backlog may live through, plus the retries handlers need, with
margin. Alert on the dead letter queue depth and on a non-zero
`jms.listener.bulkhead.wait.aborted` with `reason=stopped` or `interrupted`.

Recommendation: leave `max-wait` unset. A timeout only puts the message back
on the queue so another instance may take it, which the gate already does for
every message the instance cannot process yet; with the default prefetch of 0
the same instance often gets it again.

## Sizing

- `max-in-flight` ≈ the database pool size minus what request threads,
  scheduled work and batches need at the same time. The bulkhead covers JMS
  handlers only; it is not a database limiter.
- **Rollout:** enable it with `max-in-flight` at today's effective handler
  concurrency, watch `jms.listener.bulkhead.wait` and
  `jms.listener.bulkhead.waiting`, then lower the limit step by step.
- **Thread count.** With the unbounded executor each consumer has its own
  thread and JMS session. Gated consumers keep theirs (holding no message),
  and no consumer is started while the gate is closed, so under saturation a
  container keeps about the consumers it had when the group filled up, not its
  maximum. Worst case: the sum of the maximum concurrency of every listener
  container. Concurrency ranges also set a queue's weight in the arrival
  order. Virtual threads (`listener.executor.virtual=true`) reduce the thread
  cost; the bulkhead itself never blocks inside `synchronized`.

## Metrics

Registered when a `MeterRegistry` bean exists, for limited groups only.

| Name | Type | Tags | Meaning |
|---|---|---|---|
| `jms.listener.bulkhead.in.flight` | gauge | `group` | Permits in use (handlers, plus consumers that took their permit at the gate and are receiving). |
| `jms.listener.bulkhead.waiting` | gauge | `group` | Consumers waiting: at the gate before they receive, plus those holding a received message. |
| `jms.listener.bulkhead.waiting.with.message` | gauge | `group` | Of those, consumers holding a received message while they wait for a permit (the race after the gate, or containers without the gate). Each holds a broker delivery. |
| `jms.listener.bulkhead.max.in.flight` | gauge | `group` | Current limit. |
| `jms.listener.bulkhead.reserved.in.flight` | gauge | `group`, `reservation` | Running handlers of the reservation's listeners (may exceed its permits: the rest use shared permits). |
| `jms.listener.bulkhead.reserved.permits` | gauge | `group`, `reservation` | Reserved permits. |
| `jms.listener.bulkhead.wait` | timer | `group`, `destination` | Wait of every handled message: at the gate before the receive that got it, plus holding it for a permit (0 when both were immediate): count = handled messages, total = time waited. |
| `jms.listener.bulkhead.wait.aborted` | counter | `group`, `destination`, `reason` = `timeout`, `stopped` or `interrupted` | Waits holding a message that ended without a permit (the message was rolled back). A gate wait that ends on a stop or shutdown is not counted: nothing was received. |

Series: 4 per group, 2 per reservation, one timer per listener destination,
and one counter per destination and reason that occurred. No histogram by
default; add `management.metrics.distribution.*` for
`jms.listener.bulkhead.wait` if percentiles are needed.

Upgrading from a version without the gate: `waiting` now also counts gated
consumers (much higher under saturation for the same load) and `wait` adds the
gate wait. Re-baseline thresholds on them, and move alerts about messages held
by consumers to `waiting.with.message`.

What to watch: `waiting` high for long periods (the limit is too low for the
load, or a handler is slow; the messages wait on the queue, so watch the queue
age too), `waiting.with.message` above a few for long (consumers holding
messages: a container without the gate, or prefetch, see [Prefetch](#prefetch)),
the `wait` mean per destination (the delay the bulkhead adds), any
`wait.aborted{reason=timeout}`.

## Why no queue starves

Assumption: handlers end within a bound `T` (transaction timeouts), and the
listener executor is unbounded.

- Idle queues hold no permit: their consumers take none at the gate; a
  permit is taken at the gate only by a consumer whose queue just delivered,
  and given back after one short empty receive.
- A consumer waits once per receive, so a container contributes at most its
  consumers as waiters, and a consumer that finishes queues again at the
  tail. A waiter at position `p` passes after at most `p` shared releases (a
  gated consumer without permit passes on any free permit, taking nothing):
  its wait is at most `ceil(p / shared permits) × (T + short receive)`, with
  `p` at most the sum of every container's maximum consumers.
- A thread holds at most one permit and no lock while its handler runs: no
  self-deadlock.
- With a capped pool, waiters hold pool threads and other queues' consumers
  wait in the executor queue, hence the listener executor is unbounded by
  default.

Container details (spring-jms 6.2): a consumer task ends after
`maxMessagesPerTask` receive attempts and resubmits itself, waiting only makes
a task longer. A consumer that waits at the gate is marked idle, which stops
the container from starting more consumers; it is not retired while it waits
(its task does not end), and surplus consumers retire as before once the
backlog is gone.

### Prefetch

- `spring.artemis.consumer-window-size=0` (library default): a consumer asks
  the broker for a message only when it receives, so a gated consumer holds no
  message, and a consumer waiting holding a message holds exactly that one.
  Keep it: with a positive window the broker pushes messages into consumers
  whatever the gate says.
- Dynamic consumer credits ([doc](dynamic-consumer-credit-scaling.md)): above
  the per-consumer depth threshold a consumer may buffer up to
  `dynamic-credits-max-credits` bytes, and a gated consumer keeps that buffer
  (those messages are deliveries the broker tracks, as before the gate). It
  happens only with a deep backlog. Where the backlog should wait on the queue,
  or instances have very different capacity, lower
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
2. The bulkhead is on by default (6 handlers per CPU). To start without
   throttling, set `listener.bulkhead.max-in-flight` (or the multiplier) to
   today's effective concurrency, then lower it.
3. Watch the metrics, then lower the limit; add reservations for listeners
   that need a guaranteed minimum.

## Classes

- `JmsListenerBulkhead`: the limiter (lock, the gate and the permits,
  arrival-ordered waiters, reservations, live limit). No Spring dependency.
- `JmsListenerBulkheadAdmission`: one pass through the gate, with or without
  the permit.
- `JmsListenerBulkheadRegistry`: validation, group and reservation resolution,
  one bulkhead per limited group, gauges.
- `JmsListenerBulkheadBinding`: what one listener uses (bulkhead, reservation,
  destination, wait timer and aborted counters).
- `ExtendedMessageListenerContainer`: a `DefaultMessageListenerContainer`
  whose consumers pass the gate before they receive, and that starts no
  consumer while the gate is closed. Its log category is its own class name:
  a level set for `DefaultMessageListenerContainer` does not apply to it.
- `ExtendedMessagingMessageListenerAdapter`: runs the handler on the permit
  (taken at the gate, or acquired) and releases it.
- `ExtendedJmsListenerContainerFactory`: built by
  `JmsListenerContainerFactoryBuilder`; creates `ExtendedMessageListenerContainer`s
  and tags its listeners with the factory bean name and group.
