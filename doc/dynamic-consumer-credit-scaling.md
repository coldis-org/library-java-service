# Dynamic Consumer Credit Scaling

A client-side Apache Artemis interceptor that grows a JMS consumer's prefetch
window when its queue is backed up, and keeps it at one message at a time when
the queue is shallow.

## Overview

Artemis consumers use **credit-based flow control**: the client tells the broker
how many *bytes* of messages it is willing to receive (the consumer window), and
the broker dispatches messages until that credit is exhausted. Two window sizes
matter here:

- `consumerWindowSize > 0` — the broker buffers up to that many bytes per
  consumer (prefetch). Fast, but a greedy consumer can grab a large batch while
  siblings/instances starve.
- `consumerWindowSize = 0` — **slow-consumer mode**: the client pulls one message
  at a time. This distributes messages fairly across consumers and instances, but
  a deep backlog drains slowly because each consumer only ever holds one message.

This feature gets the best of both: run in slow-consumer mode (`= 0`) for fair
distribution under light load, but when a queue's depth crosses a threshold, let
each consumer prefetch a proportionally larger batch — up to a hard per-consumer
cap — so deep queues drain fast.

It does this by intercepting the client's outgoing flow-credit packets and
rewriting the credit value based on the live queue depth. No broker-side change
and no `@JmsListener` change is required.

## Enabling

The interceptor is registered on the Artemis `ConnectionFactory`'s `ServerLocator`
automatically **when `spring.artemis.dynamic-credits-depth-threshold` is set**
(see `JmsConfigurationHelper.setConnectionExtendedProperties`). It is enabled by
default in `service.properties`.

For the intended behaviour also set `spring.artemis.consumer-window-size=0`: the
interceptor **disables itself when the broker window is `> 0`**, since in that
case the broker already manages prefetch and scaling on top would double-stack.

## Configuration

All properties are under `spring.artemis.*` (`ExtendedArtemisProperties`) and may
be overridden by environment variables. Defaults shown are from
`service.properties`.

| Property | Default | Meaning |
| --- | --- | --- |
| `consumer-window-size` | `0` | Broker consumer window (bytes). `0` = slow-consumer mode; must be `0` for the interceptor to be active (it disables itself when `> 0`). |
| `dynamic-credits-depth-threshold` | `200` | Pending message count above which scaling kicks in. **`null` disables the feature entirely** (no interceptor registered). |
| `dynamic-credits-multiplier` | `2.0` | Ramp aggressiveness. The window reaches its cap once `(depth/threshold − 1) × multiplier ≥ 1`; a larger multiplier reaches the full window at a shallower depth. |
| `dynamic-credits-max-credits` | `131072` (128 KB) | Hard cap on outstanding credits **per consumer** (bytes). |
| `dynamic-credits-cache-ttl` | `5000` | How long (ms) a queue's depth reading is cached, to bound broker round-trips. |

> If a value is missing at wiring time, `JmsConfigurationHelper` logs a WARN and
> falls back to inline defaults (`multiplier=1.0`, `maxCredits=10 MB`,
> `cacheTtl=5000`). A WARN here means the property pipeline failed to resolve the
> value from `service.properties`/overrides — diagnose that, don't rely on the
> fallback.

## Key class

| Class | Role |
| --- | --- |
| `DynamicCreditClientInterceptor` | `org.apache.activemq.artemis.api.core.Interceptor` registered as an **outgoing** client interceptor. Tracks consumers, reads queue depth, and rewrites flow-credit packets. |
| `JmsConfigurationHelper` | Wires the interceptor onto the connection factory's `ServerLocator` when the depth threshold is set. |
| `ExtendedArtemisProperties` | Binds the `spring.artemis.dynamic-credits-*` and `consumer-window-size` properties. |

## How it works

The interceptor inspects three Artemis core packet types on the wire:

- **`CREATE_CONSUMER` (40)** — records the consumer's queue and initialises its
  outstanding-credit counter.
- **`CONSUMER_CREDITS` (70)** — the flow-credit request; this is where scaling
  happens (see below).
- **`CLOSE_CONSUMER` (74)** — drops the consumer's tracking state.

### Per-consumer, per-session tracking

Artemis numbers consumers from a **per-session** generator
(`SessionContext.idGenerator = new SimpleIDGenerator(0)`), so the first consumer
of *every* session is id `0`. A single interceptor on the shared `ServerLocator`
sees many consumers reporting the same id — e.g. Spring's
`DefaultMessageListenerContainer` at concurrency `1-N` opens N sessions, each with
its own consumer `0`.

Tracking is therefore keyed by **`connectionId | channelId | consumerId`** (the
channel identifies the session). This is essential: keying by the bare consumer id
would collapse every session's consumer `0` onto one shared, resettable window, so
the whole listener group would share a single `maxCredits` budget and adding
consumers would not increase throughput.

### Grant computation

On each flow-credit packet, inside a per-consumer lock:

1. `requestedCredits` = the credits the client is asking for (the bytes it just
   freed by consuming a message).
2. `outstandingAfterFreed = max(0, currentOutstanding − requestedCredits)`.
3. `depthTargetWindow = computeTargetWindow(pendingDepth)` (see below).
4. `targetOutstanding = max(outstandingAfterFreed + requestedCredits, depthTargetWindow)`
   — never grant less than requested (that would throttle below the baseline),
   and never fall short of what the depth warrants.
5. `grantedCredits = targetOutstanding − outstandingAfterFreed`; store
   `targetOutstanding` as the new outstanding.
6. If `grantedCredits != requestedCredits`, write it back onto the packet (via
   reflection into the packet's private `credits` field). The broker adds the
   larger credit and bursts out `grantedCredits / messageSize` messages.

`computeTargetWindow(pendingDepth)`:

```
if pendingDepth <= depthThreshold:       return 0          # pass-through, one at a time
fraction = min(1, (pendingDepth/depthThreshold - 1) * multiplier)
return min(maxCredits, ceil(maxCredits * fraction))         # grows with depth, capped
```

The window (not the raw request) is scaled deliberately: in slow-consumer mode the
client often sends the sentinel credit `1`, which — if scaled directly — would
produce a meaningless grant.

### Protocol control packets (reset / disable)

Not every flow-credit packet is a byte refill. In slow-consumer mode a
`receive(timeout)` that times out on an empty queue makes the client send a
**credit-`0` reset** (`resetIfSlowConsumer`), and the broker zeroes the consumer's
window (`availableCredits.set(0)`); credit `-1` disables flow control entirely.
The interceptor passes both through **untouched** (rewriting a reset into a grant
would strand messages in idle consumers' buffers) and zeroes its tracked window to
mirror the broker. Without that mirroring the tracker believes the window is still
full and never scales again — the consumer permanently collapses to ~1 message in
flight after a single empty poll.

A `CREATE_CONSUMER` on an already-tracked key (a failover recreate) also restarts
the tracked window, since the recreated server-side consumer starts with zero
credits. Scaling toward a target *window* sidesteps that.

### Queue depth signal

Depth is read with a client `queueQuery(queueName).getMessageCount()` on a lazily
created, cached `ClientSession`, guarded by a lock (the session is not
thread-safe). Readings are cached per queue for `cacheTtlMillis`. Each real
(non-cached) read is logged at DEBUG with the queue name, `exists`, and
`messageCount`, so a persistently-zero depth from a name mismatch is diagnosable.

## Interaction with listener concurrency

The credit window controls **prefetch**, not processing parallelism — one Artemis
consumer processes its messages serially. Total messages in flight is roughly:

```
activeConsumers × (maxCredits / averageMessageSize)
```

So two levers scale a backlog down:

- **`dynamic-credits-max-credits`** — how deep each consumer prefetches.
- **listener concurrency** (`org.coldis.library.service.jms.listener.concurrency.*`,
  Spring DMLC) — how many consumers run. Spring ramps consumers up under sustained
  load; each gets its own `maxCredits` budget thanks to the per-session keying.

Prefetching more without enough consumers only buffers messages; it does not
increase throughput. Batch listeners that run at concurrency `1` rely primarily on
the depth-proportional window to drain faster.

## Observability

- `getConsumersRegistered()` / `getCreditPacketsIntercepted()` /
  `getCreditPacketsScaled()` — counters (used by tests and available for metrics).
- DEBUG `DynamicCredit — depth query queue='…' exists=… messageCount=…` — every
  real depth read.
- DEBUG `DynamicCredit — queue=… requested=… granted=… outstanding=…` — every
  scaled packet.
- INFO `DynamicCredit — queue=… depth=… requested=… granted=… totalOutstanding=…`
  — throttled to once per queue per 5 minutes, summing outstanding credits across
  all consumers on the queue.

If scaling never seems to engage, check: the INFO line never appears →
`getPendingDepth` is reading `≤ threshold`; the DEBUG depth line shows
`exists=false` → the queried queue name does not match a real queue.

## Design notes / gotchas

- **`maxCredits` is a per-consumer hard cap.** Outstanding bytes for a single
  consumer never exceed it (barring a single message larger than the cap, which is
  still honoured so the consumer never stalls).
- **Requires slow-consumer mode.** With `consumer-window-size > 0` the interceptor
  is a no-op by design.
- **Depth is a client `queueQuery`,** which is a broker round-trip; the cache
  (`cacheTtlMillis`) bounds its frequency.
- **`getPendingDepth` is `protected`** so tests can stub the depth without a
  broker.

## Tests

| Test | What it covers |
| --- | --- |
| `DynamicCreditClientInterceptorUnitTest` | `computeTargetWindow` pure function: pass-through below threshold, proportional ramp, cap, multiplier effect, monotonicity. |
| `DynamicCreditClientInterceptorScalingTest` | Broker-free: grant grows with depth, caps at `maxCredits`, passes through below threshold; reset (`0`) and disable (`-1`) packets pass through untouched, the window re-inflates after a reset, and a failover recreate restarts the window (stubs `getPendingDepth`). |
| `DynamicCreditClientInterceptorSessionScopingTest` | Broker-free: two sessions' consumer `0` are tracked independently; a new consumer does not reset a sibling's window. |
| `DynamicCreditClientInterceptorTest` | Container-based: deep queue scales, shallow queue passes through, untracked consumers are ignored, concurrent intercepts don't trip Artemis' `AMQ212051` (shared query session is synchronized), and scaling recovers after a real slow-consumer reset (a timed-out empty poll on the same consumer). |
