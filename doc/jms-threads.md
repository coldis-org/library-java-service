# JMS threads

How this library sizes the threads of the JMS client and the listeners, what each setting does, which
settings are deprecated, and how to migrate. The concurrency of the handlers themselves is bounded by
the [listener bulkhead](jms-listener-bulkhead.md), not by threads.

## CPUs

The bulkhead's `max-in-flight-cpu-multiplier` and `spring.artemis.remoting-threads-cpu-multiplier`
are multiplied by the service's [CPU source](cpu-source.md). The deprecated legacy
`listener.executor.concurrency-cpu-multiplier`, the bounded listener pool (`unbounded=false`) and the
Artemis global executors keep their previous sizing from `availableProcessors()`. A remoting-threads
multiplier that is not positive falls back to the default.

## Settings

### Listener executor (`org.coldis.library.service.jms.listener.executor.*`)

The threads of every listener container's polling consumers; they also run the handlers.

| Setting | Default | Meaning |
|---|---|---|
| `unbounded` | `true` | A cached pool: no core threads, no maximum, one thread per polling consumer, idle threads released after `keep-alive-seconds`. `false`: the previous bounded pool (below), with a WARN. |
| `keep-alive-seconds` | `60` | Idle time before a thread is released. |
| `virtual` | `false` | Virtual threads (respected by both pools). |
| `name` | `jms-listener-thread` | Thread name prefix. |
| `core-size`, `core-size-cpu-multiplier` | unset | **Deprecated.** Ignored (WARN) when unbounded. With `unbounded=false`: the pool size (20 per processor when unset), as before. |
| `parallelism*`, `min-runnable*` | unset | **Deprecated.** Ignored (WARN) when unbounded; with `unbounded=false`, `parallelism*` still makes a fork-join pool (it does not compensate for a blocking receive and ignores `virtual`). |
| `concurrency`, `concurrency-cpu-multiplier` | unset | **Deprecated.** Still honoured, with a WARN: a shared `SimpleAsyncTaskExecutor` limited to that many polling consumers, which can starve or deadlock them (see [the bulkhead doc](jms-listener-bulkhead.md#why-not-listenerexecutorconcurrency)). |
| `priority` | unset (5) | **Deprecated**, WARN when set: see [Priority](#priority). |

Why unbounded: a capped pool (the previous default was 20 threads per processor) lets idle pollers
and consumers waiting at the bulkhead gate hold the threads busy queues need. And a
`ThreadPoolExecutor` below its core size starts a new thread on every submit, even with idle threads;
consumers resubmit themselves every `maxMessagesPerTask` (10) receives, so a bounded pool grew to
its core size and never shrank (keep-alive did not apply). The cached pool grows with the polling
consumers and shrinks to no thread when idle.

### Artemis global executors (`org.coldis.library.service.jms.global.*`)

The Artemis client's global thread pools (`custom-pools=true`). Sizes are unchanged: the general pool
has 1 core thread and no maximum, the scheduled and flow-control pools 1 core thread per processor.
`priority`, `parallelism*` and `min-runnable*` are **deprecated** (WARN when set, still honoured).

### Artemis remoting threads

`remotingThreads` in `spring.artemis.broker-url-query-default` (the client's Netty threads), first
match: `spring.artemis.remoting-threads`; `spring.artemis.remoting-threads-cpu-multiplier` × CPUs;
the legacy `AVAILABLE_CPUS_30X` environment variable (kept working); 30 × CPUs. The effective default
is unchanged (30 per CPU). A lower multiplier (around 4) is likely enough, but confirm with a thread
dump of the Netty client threads under load before lowering it.

### Listener concurrency presets (`org.coldis.library.service.jms.listener.concurrency.*`)

`shortest` … `highest` (`1` … `1-110`). **Deprecated**: no listener is known to reference them. They
stay defined so a placeholder that still uses one resolves; give each listener its own concurrency
property instead.

### Unchanged

`spring.artemis.max-messages-per-task` (10), the container receive timeout (1 s), idle consumer and
task limits (Spring defaults), `spring.artemis.cache-level` (3), `spring.artemis.consumer-window-size=0`
and the dynamic consumer credits, the back-off settings, and the producer pool
(`spring.artemis.pool.max-connections`, `max-sessions-per-connection`).

### Priority

`priority` has no effect on Linux unless the JVM runs with `-XX:ThreadPriorityPolicy=1`, and lowering
a thread's priority below the process's usually needs `CAP_SYS_NICE`; virtual threads have no
priority. Prioritise with bulkhead reservations (a guaranteed minimum) and groups (separate limits),
or deploy low-priority listeners separately.

## Metrics

Micrometer executor metrics (`executor.pool.size`, `executor.pool.max`, `executor.active`,
`executor.queued`, `executor.completed`, …), tag `name`:

| `name` | Executor |
|---|---|
| `jms.listener` | listener executor (not for the deprecated `concurrency*` executor) |
| `jms.global` | Artemis global executor |
| `jms.global.scheduled` | Artemis global scheduled executor |
| `jms.global.flow-control` | Artemis global flow-control executor |

## Behaviour change and migration

The listener executor is unbounded by default, with or without the bulkhead. A service that relied
on the old thread cap (20 per processor, or its own `core-size*`) as an implicit handler limit can
run more handlers at once after upgrading.

1. Remove `listener.executor.core-size*`, `parallelism*`, `min-runnable*`, `priority` and
   `concurrency*` lines.
2. The bulkhead is on by default: without `max-in-flight` it allows 6 handlers per CPU
   (`max-in-flight-cpu-multiplier`; tune toward 2–4 for CPU-bound modules and 8–12 for database-,
   I/O- or broker-heavy ones). `listener.bulkhead.enabled=false` turns it off.
3. If the module relied on the old cap, set `listener.bulkhead.max-in-flight` to its old effective
   cap first, then lower it. `listener.executor.unbounded=false` restores the old pool temporarily
   (with a WARN).

Rollout: one module first; compare before and after live and waiting threads (`executor.*`,
JVM threads), in-flight handlers and waiting consumers (`jms.listener.bulkhead.*`), CPU throttling, the
database pool's pending connections (Hikari `pending`) and queue message age; then the next modules.

## Follow-ups

- Route the bounded listener pool, the Artemis global executors and the non-JMS users of
  `availableProcessors()` through the [CPU source](cpu-source.md).
- Lower the remoting threads multiplier once a thread dump confirms the Netty usage.
